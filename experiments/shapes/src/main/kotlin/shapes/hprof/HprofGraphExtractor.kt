package shapes.hprof

import shapes.relaxation.FieldConcealmentRules
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** Cross-chunk extraction counters; merged, not contended. */
class ExtractionCounters {
    var rawNonNullRefs = 0L
    var classTargetRefs = 0L
    var danglingRefs = 0L
    var mechanicsDropped = 0L
    var concealedDropped = 0L
    var projectionDropped = 0L
    var dedupDropped = 0L
    var mainEmitted = 0L
    var excludedInstanceRefs = 0L
    var truncatedShallow = 0L
    var layoutMismatches = 0L

    fun add(other: ExtractionCounters) {
        rawNonNullRefs += other.rawNonNullRefs
        classTargetRefs += other.classTargetRefs
        danglingRefs += other.danglingRefs
        mechanicsDropped += other.mechanicsDropped
        concealedDropped += other.concealedDropped
        projectionDropped += other.projectionDropped
        dedupDropped += other.dedupDropped
        mainEmitted += other.mainEmitted
        excludedInstanceRefs += other.excludedInstanceRefs
        truncatedShallow += other.truncatedShallow
        layoutMismatches += other.layoutMismatches
    }

    override fun toString(): String =
        "raw=$rawNonNullRefs class=$classTargetRefs dangling=$danglingRefs " +
            "mechanics=$mechanicsDropped concealed=$concealedDropped " +
            "projDropped=$projectionDropped dedup=$dedupDropped main=$mainEmitted " +
            "excludedRefs=$excludedInstanceRefs layoutMismatch=$layoutMismatches"
}

/** Flag bits in the per-object flags byte. */
internal object ObjectFlags {
    const val LEAF = 1
    const val EXCLUDED = 2
    const val LIVE = 4
    const val SCOPE = 8
}

/**
 * Shared state of one extraction between the record walk and graph assembly.
 * All segments are indexed by dense object id.
 */
internal class ExtractionState(
    val file: HprofFile,
    val index: HprofHeapIndex,
    val plans: Array<ClassExtractionPlan>,
    val table: ObjectAddressTable,
    val objectCount: Int,
    val metaArena: Arena,
    val graphArena: Arena,
    /** Entry-node links and node-table elements; freed after chain expansion. */
    val expansionArena: Arena,
    /** Synthetic expansion rows; freed after scatter. */
    val syntheticArena: Arena,
    /** Array shallow pairs; freed after compaction. */
    val shallowArena: Arena,
    val classIdx: MemorySegment,
    val flags: MemorySegment,
    /** Per-object target counts; prefix-summed into row offsets by assembly. */
    val counts: MemorySegment,
    val spillDirectory: SpillDirectory,
    val chunkCount: Int,
) {
    /** Encoded class-object target base: targets >= objectCount are store class indices. */
    val classTargetBase: Int = objectCount

    val linkBuffers = Array(chunkCount) { IntBlockBuffer(expansionArena) }
    val expansionBuffers = Array(chunkCount) { IntBlockBuffer(expansionArena) }
    val shallowPairBuffers = Array(chunkCount) { IntBlockBuffer(shallowArena) }
    val counters = ExtractionCounters()

    private val graphClassIndexByName = ConcurrentHashMap<String, Int>()
    private val graphClassCounter = AtomicInteger()
    val graphClassNamesSlots = arrayOfNulls<String>(1 shl 16)
    val graphClassShallow = LongArray(1 shl 16) { -1 }

    val graphClassCount: Int
        get() = graphClassCounter.get()

    fun graphClassIndexOf(plan: ClassExtractionPlan): Int {
        val cached = plan.graphClassIndex
        if (cached >= 0) return cached
        val assigned = graphClassIndexByName.computeIfAbsent(plan.graphClassName) {
            val next = graphClassCounter.getAndIncrement()
            check(next < (1 shl 16)) { "more than 65536 used class names" }
            graphClassNamesSlots[next] = plan.graphClassName
            if (!plan.isArrayKind) graphClassShallow[next] = plan.shallowSize
            next
        }
        plan.graphClassIndex = assigned
        return assigned
    }

    fun flagsOf(denseId: Int): Int =
        flags.get(ValueLayout.JAVA_BYTE, denseId.toLong()).toInt()

    fun hasFlag(denseId: Int, bit: Int): Boolean = flagsOf(denseId) and bit != 0

    fun setFlag(denseId: Int, bit: Int) {
        val current = flags.get(ValueLayout.JAVA_BYTE, denseId.toLong()).toInt()
        flags.set(ValueLayout.JAVA_BYTE, denseId.toLong(), (current or bit).toByte())
    }

    fun clearFlag(denseId: Int, bit: Int) {
        val current = flags.get(ValueLayout.JAVA_BYTE, denseId.toLong()).toInt()
        flags.set(ValueLayout.JAVA_BYTE, denseId.toLong(), (current and bit.inv()).toByte())
    }

    fun classIndexOf(denseId: Int): Int =
        classIdx.get(ValueLayout.JAVA_SHORT_UNALIGNED, denseId.toLong() shl 1).toInt() and 0xFFFF

    fun countOf(denseId: Int): Int =
        counts.get(ValueLayout.JAVA_INT_UNALIGNED, denseId.toLong() shl 2)

    fun setCount(denseId: Int, value: Int) {
        counts.set(ValueLayout.JAVA_INT_UNALIGNED, denseId.toLong() shl 2, value)
    }
}

class HprofExtractionOutput(
    val graph: HeapGraph,
    val counters: ExtractionCounters,
)

/**
 * Builds the structural object graph straight from an HPROF dump: one parallel
 * record walk spills filtered and projected edges, then [HeapGraphAssembler]
 * folds liveness, leaves and hash-table chains into the final [HeapGraph].
 */
class HprofGraphExtractor(
    private val file: HprofFile,
    private val directory: HprofDirectory,
    private val index: HprofHeapIndex,
    private val concealmentRules: FieldConcealmentRules = FieldConcealmentRules(),
    private val filterReferenceMechanics: Boolean = true,
    private val foldLeaves: Boolean = true,
    private val scopeClassName: String? = null,
    private val progress: (String) -> Unit = {},
) {
    init {
        require(scopeClassName == null || !foldLeaves) {
            "class scopes require foldLeaves=false (roots may be leaves)"
        }
    }

    fun extract(): HprofExtractionOutput {
        var phaseStart = System.nanoTime()
        val plans = ClassExtractionPlans.build(
            index.classStore,
            concealmentRules,
            filterReferenceMechanics,
            progress,
        )
        val table = ObjectAddressTable.build(index.addressBuffers)
        index.addressArena.close()
        require(table.objectCount.toLong() == index.counts.objects) {
            "address table ${table.objectCount} != object count ${index.counts.objects}"
        }
        progress("address table: ${table.objectCount} objects in ${elapsed(phaseStart)} ms")

        val objectCount = table.objectCount
        val metaArena = Arena.ofShared()
        val graphArena = Arena.ofShared()
        val state = ExtractionState(
            file = file,
            index = index,
            plans = plans,
            table = table,
            objectCount = objectCount,
            metaArena = metaArena,
            graphArena = graphArena,
            expansionArena = Arena.ofShared(),
            syntheticArena = Arena.ofShared(),
            shallowArena = Arena.ofShared(),
            classIdx = metaArena.allocate(objectCount.toLong() shl 1),
            flags = metaArena.allocate(objectCount.toLong()),
            counts = mapScratchSegment(graphArena, (objectCount.toLong() + 1) shl 2),
            spillDirectory = SpillDirectory(),
            chunkCount = index.chunks.size,
        )

        phaseStart = System.nanoTime()
        walkRecords(state)
        progress("walk: spilled edges in ${elapsed(phaseStart)} ms; ${state.counters}")

        val graph = HeapGraphAssembler(state, foldLeaves, scopeClassName, progress).assemble()
        return HprofExtractionOutput(graph, state.counters)
    }

    private fun walkRecords(state: ExtractionState) {
        val identifierSize = file.identifierSize
        val chunkCounters = mapChunksParallel(index.chunks) { chunkIndex, chunk ->
            val worker = ChunkWorker(state, chunkIndex, identifierSize)
            chunk.forEachRange { start, end ->
                var offset = start
                while (offset < end) {
                    val tag = file.u1(offset)
                    offset++
                    offset = when (tag) {
                        HprofSubTag.INSTANCE_DUMP -> worker.instance(offset)
                        HprofSubTag.OBJECT_ARRAY_DUMP -> worker.objectArray(offset)
                        HprofSubTag.PRIMITIVE_ARRAY_DUMP -> worker.primitiveArray(offset)
                        else -> skipSubRecord(file, tag, offset)
                    }
                }
            }
            worker.finish()
            worker.counters
        }
        for (counters in chunkCounters) state.counters.add(counters)
    }

    private fun elapsed(startedAt: Long): Long = (System.nanoTime() - startedAt) / 1_000_000
}

/** Per-chunk record processor with reusable scratch; owns one spill file. */
private class ChunkWorker(
    private val state: ExtractionState,
    chunkIndex: Int,
    private val identifierSize: Int,
) {
    val counters = ExtractionCounters()

    private val file = state.file
    private val table = state.table
    private val classStore = state.index.classStore
    private val plans = state.plans
    private val spill = IntSpillWriter(state.spillDirectory.file("chunk-$chunkIndex"))
    private val links = state.linkBuffers[chunkIndex]
    private val expansion = state.expansionBuffers[chunkIndex]
    private val shallowPairs = state.shallowPairBuffers[chunkIndex]

    private var mains = IntArray(256)
    private var mainCount = 0
    private var extras = IntArray(64)
    private var extrasCount = 0
    private var keptValues = LongArray(64)
    private var dropValues = LongArray(16)
    private val dedupSet = it.unimi.dsi.fastutil.longs.LongOpenHashSet(64)
    private val intDedupSet = it.unimi.dsi.fastutil.ints.IntOpenHashSet(64)

    fun finish() = spill.close()

    fun instance(recordStart: Long): Long {
        val address = file.id(recordStart)
        val classAddress = file.id(recordStart + identifierSize + 4)
        val dataBytes = file.u4(recordStart + identifierSize + 4 + identifierSize)
        val dataStart = recordStart + identifierSize + 4 + identifierSize + 4

        val denseId = table.denseIdOf(address)
        check(denseId >= 0) { "instance at $recordStart missing from address table" }
        val storeClassIndex = classStore.indexOf(classAddress)
        check(storeClassIndex >= 0) { "instance of unknown class at $recordStart" }
        val plan = plans[storeClassIndex]
        writeClassIndex(denseId, state.graphClassIndexOf(plan))
        mainCount = 0
        extrasCount = 0

        val hprofClass = classStore[storeClassIndex]
        if (dataBytes < hprofClass.instanceDataBytes) {
            counters.layoutMismatches++
            state.setCount(denseId, 0)
            state.setFlag(denseId, ObjectFlags.LEAF)
            return dataStart + dataBytes
        }
        if (plan.shallowSize != state.graphClassShallow[plan.graphClassIndex]) {
            addShallowPair(denseId, plan.shallowSize)
        }

        if (plan.isExcludedClassObject) {
            state.setFlag(denseId, ObjectFlags.EXCLUDED)
            for (fieldOffset in hprofClass.referenceFieldOffsets) {
                val value = file.id(dataStart + fieldOffset)
                if (value != 0L) {
                    counters.rawNonNullRefs++
                    counters.excludedInstanceRefs++
                    addExtraValue(value, scopeTraversable = false)
                }
            }
            finishRecord(denseId, leafWhenEmpty = false)
            return dataStart + dataBytes
        }

        // Mechanics and concealed fields never become edges; keep them for liveness.
        for (fieldOffset in plan.mechanicsOffsets) {
            val value = file.id(dataStart + fieldOffset)
            if (value != 0L) {
                counters.rawNonNullRefs++
                counters.mechanicsDropped++
                addExtraValue(value, scopeTraversable = false)
            }
        }
        for (fieldOffset in plan.concealedOffsets) {
            val value = file.id(dataStart + fieldOffset)
            if (value != 0L) {
                counters.rawNonNullRefs++
                counters.concealedDropped++
                addExtraValue(value, scopeTraversable = false)
            }
        }

        when (plan.kind) {
            PlanKind.PLAIN -> {
                for (fieldOffset in plan.readOffsets) {
                    val value = file.id(dataStart + fieldOffset)
                    if (value != 0L) {
                        counters.rawNonNullRefs++
                        addMainValue(value)
                    }
                }
            }
            PlanKind.DROP_EDGES -> {
                var keptCount = 0
                for (fieldOffset in plan.readOffsets) {
                    val value = file.id(dataStart + fieldOffset)
                    if (value != 0L) {
                        counters.rawNonNullRefs++
                        keptValues = ensure(keptValues, keptCount + 1)
                        keptValues[keptCount++] = value
                    }
                }
                var dropCount = 0
                for (fieldOffset in plan.dropOffsets) {
                    val value = file.id(dataStart + fieldOffset)
                    if (value != 0L) {
                        counters.rawNonNullRefs++
                        dropValues = ensure(dropValues, dropCount + 1)
                        dropValues[dropCount++] = value
                    }
                }
                // Alias-protected removal + full output dedup (a target kept
                // through a surviving field never drops via a projected one).
                for (dropIndex in 0 until dropCount) {
                    val value = dropValues[dropIndex]
                    if (!contains(keptValues, keptCount, value)) {
                        counters.projectionDropped++
                        addExtraValue(value, scopeTraversable = true)
                    }
                }
                for (keptIndex in 0 until keptCount) {
                    val value = keptValues[keptIndex]
                    if (containsBefore(keptValues, keptIndex, value)) {
                        counters.dedupDropped++
                    } else {
                        addMainValue(value)
                    }
                }
                if (plan.isEntryNode) {
                    links.add(denseId)
                    var linkSlot = 0
                    for (fieldOffset in plan.linkOffsets) {
                        if (linkSlot == 3) break
                        val value = file.id(dataStart + fieldOffset)
                        links.add(if (value == 0L) -1 else table.denseIdOf(value))
                        linkSlot++
                    }
                    while (linkSlot < 3) {
                        links.add(-1)
                        linkSlot++
                    }
                }
            }
            PlanKind.KEEP_ONLY -> {
                var keptCount = 0
                for (fieldOffset in plan.keepOffsets) {
                    val value = file.id(dataStart + fieldOffset)
                    if (value != 0L) {
                        keptValues = ensure(keptValues, keptCount + 1)
                        keptValues[keptCount++] = value
                    }
                }
                if (keptCount == 0) {
                    // Replicates keepNamedTargets: nothing retained passes everything through.
                    for (fieldOffset in plan.readOffsets) {
                        val value = file.id(dataStart + fieldOffset)
                        if (value != 0L) {
                            counters.rawNonNullRefs++
                            addMainValue(value)
                        }
                    }
                } else {
                    var emittedCount = 0
                    for (fieldOffset in plan.readOffsets) {
                        val value = file.id(dataStart + fieldOffset)
                        if (value == 0L) continue
                        counters.rawNonNullRefs++
                        if (contains(keptValues, keptCount, value)) {
                            if (containsBefore(dropValues, emittedCount, value)) {
                                counters.dedupDropped++
                            } else {
                                dropValues = ensure(dropValues, emittedCount + 1)
                                dropValues[emittedCount++] = value
                                addMainValue(value)
                            }
                        } else {
                            counters.projectionDropped++
                            addExtraValue(value, scopeTraversable = true)
                        }
                    }
                }
            }
            PlanKind.NO_EDGES -> {
                for (fieldOffset in plan.readOffsets) {
                    val value = file.id(dataStart + fieldOffset)
                    if (value != 0L) {
                        counters.rawNonNullRefs++
                        counters.projectionDropped++
                        addExtraValue(value, scopeTraversable = true)
                    }
                }
            }
            else -> throw IllegalStateException("array plan ${plan.kind} on instance record")
        }

        // MAT models stack locals as outbound references of the thread object.
        val threadLocals = state.index.threadLocalsByAddress.get(address)
        if (threadLocals != null) {
            for (localIndex in 0 until threadLocals.size) {
                counters.rawNonNullRefs++
                addMainValue(threadLocals.getLong(localIndex))
            }
        }

        finishRecord(denseId, leafWhenEmpty = true)
        return dataStart + dataBytes
    }

    fun objectArray(recordStart: Long): Long {
        val address = file.id(recordStart)
        val count = file.u4(recordStart + identifierSize + 4)
        val classAddress = file.id(recordStart + identifierSize + 4 + 4)
        val elementsStart = recordStart + identifierSize + 4 + 4 + identifierSize

        val denseId = table.denseIdOf(address)
        check(denseId >= 0) { "array at $recordStart missing from address table" }
        val storeClassIndex = classStore.indexOf(classAddress)
        check(storeClassIndex >= 0) { "array of unknown class at $recordStart" }
        val plan = plans[storeClassIndex]
        writeClassIndex(denseId, state.graphClassIndexOf(plan))
        addShallowPair(
            denseId,
            alignToHeapGrain(classStore.arrayHeaderSize + count * classStore.oopSize.toLong()),
        )
        mainCount = 0
        extrasCount = 0

        when (plan.kind) {
            PlanKind.OBJECT_ARRAY -> {
                for (elementIndex in 0 until count) {
                    val value = file.id(elementsStart + elementIndex * identifierSize)
                    if (value != 0L) {
                        counters.rawNonNullRefs++
                        addMainValue(value)
                    }
                }
                finishRecord(denseId, leafWhenEmpty = true)
            }
            PlanKind.ENTRY_ARRAY_SET -> {
                dedupSet.clear()
                for (elementIndex in 0 until count) {
                    val value = file.id(elementsStart + elementIndex * identifierSize)
                    if (value == 0L) continue
                    counters.rawNonNullRefs++
                    if (dedupSet.add(value)) {
                        addMainValue(value)
                    } else {
                        counters.dedupDropped++
                    }
                }
                finishRecord(denseId, leafWhenEmpty = true)
            }
            PlanKind.NODE_TABLE -> {
                // Elements defer to assembly: entry-node chains expand there.
                var elementCount = 0
                keptValues = ensure(keptValues, count.toInt())
                for (elementIndex in 0 until count) {
                    val value = file.id(elementsStart + elementIndex * identifierSize)
                    if (value == 0L) continue
                    counters.rawNonNullRefs++
                    val target = table.denseIdOf(value)
                    if (target >= 0) {
                        keptValues[elementCount++] = target.toLong()
                    } else {
                        val classTarget = classStore.indexOf(value)
                        if (classTarget >= 0) {
                            counters.classTargetRefs++
                            addExtra(state.classTargetBase + classTarget)
                        } else {
                            counters.danglingRefs++
                        }
                    }
                }
                expansion.add(denseId)
                expansion.add(elementCount)
                for (elementIndex in 0 until elementCount) {
                    expansion.add(keptValues[elementIndex].toInt())
                }
                finishRecord(denseId, leafWhenEmpty = false) // leaf decided at expansion
            }
            else -> throw IllegalStateException("instance plan ${plan.kind} on object array")
        }
        return elementsStart + count * identifierSize
    }

    fun primitiveArray(recordStart: Long): Long {
        val address = file.id(recordStart)
        val count = file.u4(recordStart + identifierSize + 4)
        val elementType = file.u1(recordStart + identifierSize + 4 + 4)
        val elementSize = HprofType.byteSize(elementType, identifierSize)

        val denseId = table.denseIdOf(address)
        check(denseId >= 0) { "primitive array at $recordStart missing from address table" }
        val plan = plans[primitiveArrayClassIndex(recordStart, elementType)]
        writeClassIndex(denseId, state.graphClassIndexOf(plan))
        addShallowPair(denseId, alignToHeapGrain(classStore.arrayHeaderSize + count * elementSize.toLong()))
        state.setCount(denseId, 0)
        state.setFlag(denseId, ObjectFlags.LEAF)
        return recordStart + identifierSize + 4 + 4 + 1 + count * elementSize
    }

    private fun primitiveArrayClassIndex(recordStart: Long, elementType: Int): Int {
        val cached = primitiveClassCache[elementType]
        if (cached >= 0) return cached
        val name = when (elementType) {
            HprofType.BOOLEAN -> "boolean[]"
            HprofType.CHAR -> "char[]"
            HprofType.FLOAT -> "float[]"
            HprofType.DOUBLE -> "double[]"
            HprofType.BYTE -> "byte[]"
            HprofType.SHORT -> "short[]"
            HprofType.INT -> "int[]"
            HprofType.LONG -> "long[]"
            else -> throw IllegalStateException("bad primitive array type $elementType at $recordStart")
        }
        val matches = classStore.requireByName(name)
        check(matches.isNotEmpty()) { "no class dump for $name" }
        primitiveClassCache[elementType] = matches[0]
        return matches[0]
    }

    private val primitiveClassCache = IntArray(16) { -1 }

    private fun writeClassIndex(denseId: Int, graphClassIndex: Int) {
        state.classIdx.set(
            ValueLayout.JAVA_SHORT_UNALIGNED,
            denseId.toLong() shl 1,
            graphClassIndex.toShort(),
        )
    }

    private fun addShallowPair(denseId: Int, shallow: Long) {
        var stored = shallow
        if (stored > 0xFFFFFFFFL) {
            counters.truncatedShallow++
            stored = 0xFFFFFFFFL
        }
        shallowPairs.add(denseId)
        shallowPairs.add(stored.toInt())
    }

    private fun addMainValue(address: Long) {
        val target = table.denseIdOf(address)
        if (target >= 0) {
            mains = ensure(mains, mainCount + 1)
            mains[mainCount++] = target
            counters.mainEmitted++
            return
        }
        val classTarget = classStore.indexOf(address)
        if (classTarget >= 0) {
            counters.classTargetRefs++
            addExtra(state.classTargetBase + classTarget)
        } else {
            counters.danglingRefs++
        }
    }

    private fun addExtraValue(address: Long, scopeTraversable: Boolean) {
        val projBit = if (scopeTraversable) HeapGraphAssembler.PROJ_FLAG else 0
        val target = table.denseIdOf(address)
        if (target >= 0) {
            addExtra(target or projBit)
            return
        }
        val classTarget = classStore.indexOf(address)
        if (classTarget >= 0) addExtra((state.classTargetBase + classTarget) or projBit)
    }

    private fun addExtra(encoded: Int) {
        extras = ensure(extras, extrasCount + 1)
        extras[extrasCount++] = encoded
    }

    /** Each object's references form a target set; duplicate targets collapse. */
    private fun dedupeMains() {
        if (mainCount <= 1) return
        var write = 0
        if (mainCount <= 24) {
            outer@ for (readIndex in 0 until mainCount) {
                for (checkIndex in 0 until write) {
                    if (mains[checkIndex] == mains[readIndex]) {
                        counters.dedupDropped++
                        counters.mainEmitted--
                        continue@outer
                    }
                }
                mains[write++] = mains[readIndex]
            }
        } else {
            intDedupSet.clear()
            for (readIndex in 0 until mainCount) {
                if (intDedupSet.add(mains[readIndex])) {
                    mains[write++] = mains[readIndex]
                } else {
                    counters.dedupDropped++
                    counters.mainEmitted--
                }
            }
        }
        mainCount = write
    }

    private fun finishRecord(denseId: Int, leafWhenEmpty: Boolean) {
        dedupeMains()
        state.setCount(denseId, mainCount + extrasCount)
        if (leafWhenEmpty && mainCount == 0) state.setFlag(denseId, ObjectFlags.LEAF)
        if (mainCount + extrasCount == 0) return
        spill.write(denseId)
        spill.write(mainCount)
        spill.write(extrasCount)
        for (index in 0 until mainCount) spill.write(mains[index])
        for (index in 0 until extrasCount) spill.write(extras[index])
    }

    private fun ensure(values: IntArray, required: Int): IntArray =
        if (required <= values.size) values else values.copyOf(maxOf(required, values.size * 2))

    private fun ensure(values: LongArray, required: Int): LongArray =
        if (required <= values.size) values else values.copyOf(maxOf(required, values.size * 2))

    private fun contains(values: LongArray, count: Int, value: Long): Boolean {
        for (index in 0 until count) {
            if (values[index] == value) return true
        }
        return false
    }

    private fun containsBefore(values: LongArray, endIndex: Int, value: Long): Boolean {
        for (index in 0 until endIndex) {
            if (values[index] == value) return true
        }
        return false
    }
}
