package shapes.hprof

import it.unimi.dsi.fastutil.ints.IntArrayList
import it.unimi.dsi.fastutil.ints.IntOpenHashSet
import shapes.Log
import shapes.graph.ClassName
import shapes.hprof.ObjectReferences.Companion.EXTRA_FLAG
import shapes.hprof.ObjectReferences.Companion.PROJ_FLAG
import shapes.phase
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Stage 2 of extraction: one parallel record walk applies the reference
 * filter, concealment and structural projections and spills every object's
 * references per chunk; hash-table chains are expanded against the global
 * link table; the rows are then scattered into one CSR over all objects.
 */
fun HprofDump.extractReferences(rules: ProjectionRules, log: Log): ObjectReferences =
    log.phase("extract", { "${it.counters}" }) { ReferenceExtractor(this, rules, log)() }

/** Working set of one extraction, indexed by dense object id. */
internal class ExtractionState(
    val dump: HprofDump,
    val plans: Array<ClassExtractionPlan>,
) {
    val file: HprofFile = dump.file
    val table: ObjectAddressTable = dump.objects
    val classStore: HprofClassStore = dump.classes
    val objectCount: Int = dump.objectCount
    val classTargetBase: Int = dump.classTargetBase
    val chunkCount: Int = dump.chunks.size

    val metaArena: Arena = Arena.ofShared()
    val graphArena: Arena = Arena.ofShared()
    /** Entry-node links and node-table elements; freed after chain expansion. */
    val expansionArena: Arena = Arena.ofShared()
    /** Synthetic expansion rows; freed after scatter. */
    val syntheticArena: Arena = Arena.ofShared()
    /** Array shallow pairs; handed to selection. */
    val shallowArena: Arena = Arena.ofShared()

    val classIdx: MemorySegment = metaArena.allocate(objectCount.toLong() shl 1)
    val flags: MemorySegment = metaArena.allocate(objectCount.toLong())
    /** Per-object target counts; prefix-summed into row offsets in place. */
    val counts: MemorySegment = mapScratchSegment(graphArena, (objectCount.toLong() + 1) shl 2)
    val spillDirectory = SpillDirectory()

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

    fun graphClassTable(): GraphClassTable {
        val count = graphClassCount
        val storeClasses = Array(count) { IntArrayList(2) }
        for (classIndex in plans.indices) {
            val graphIndex = plans[classIndex].graphClassIndex
            if (graphIndex >= 0) storeClasses[graphIndex].add(classIndex)
        }
        return GraphClassTable(
            names = (0 until count).map { ClassName(graphClassNamesSlots[it] ?: "unused-$it") },
            shallowSizes = graphClassShallow.copyOf(maxOf(count, 1)),
            storeClassesByGraphClass = Array(count) { storeClasses[it].toIntArray() },
        )
    }

    fun flagsOf(denseId: Int): Int =
        flags.get(ValueLayout.JAVA_BYTE, denseId.toLong()).toInt()

    fun setFlag(denseId: Int, bit: Int) {
        val current = flags.get(ValueLayout.JAVA_BYTE, denseId.toLong()).toInt()
        flags.set(ValueLayout.JAVA_BYTE, denseId.toLong(), (current or bit).toByte())
    }

    fun classIndexOf(denseId: Int): Int =
        classIdx.get(ValueLayout.JAVA_SHORT_UNALIGNED, denseId.toLong() shl 1).toInt() and 0xFFFF

    fun countOf(denseId: Int): Int =
        counts.get(ValueLayout.JAVA_INT_UNALIGNED, denseId.toLong() shl 2)

    fun setCount(denseId: Int, value: Int) {
        counts.set(ValueLayout.JAVA_INT_UNALIGNED, denseId.toLong() shl 2, value)
    }
}

internal class ReferenceExtractor(
    private val dump: HprofDump,
    private val rules: ProjectionRules,
    private val log: Log,
) {
    operator fun invoke(): ObjectReferences {
        val plans = ClassExtractionPlans.build(
            dump.classes,
            rules.concealment,
            rules.filterReferenceMechanics,
            log,
        )
        val state = ExtractionState(dump, plans)

        log.phase("walk", { "spilled edges; ${state.counters}" }) { walkRecords(state) }
        val syntheticBuffers = log.phase("expand", { "hash-table chains" }) { expandNodeTables(state) }
        state.expansionArena.close()

        val totalTargets = prefixSums(state)
        val targets = mapScratchSegment(state.graphArena, totalTargets.toLong() shl 2)
        log.phase("scatter", { "$totalTargets targets" }) { scatter(state, syntheticBuffers, targets) }
        state.syntheticArena.close()
        state.spillDirectory.close()

        return ObjectReferences(
            objectCount = state.objectCount,
            classTargetBase = state.classTargetBase,
            metaArena = state.metaArena,
            graphArena = state.graphArena,
            offsets = state.counts,
            targets = targets,
            classIdx = state.classIdx,
            flags = state.flags,
            shallowOverrides = ShallowOverrideBuffers(state.shallowArena, state.shallowPairBuffers),
            graphClasses = state.graphClassTable(),
            classes = dump.classes,
            roots = dump.roots,
            classStatics = dump.classStatics,
            counters = state.counters,
        )
    }

    // ------------------------------------------------------------------ walk

    private fun walkRecords(state: ExtractionState) {
        val file = state.file
        val identifierSize = file.identifierSize
        val chunkCounters = mapChunksParallel(dump.chunks) { chunkIndex, chunk ->
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

    // ------------------------------------------------------------------ expansion

    private fun expandNodeTables(state: ExtractionState): Array<IntBlockBuffer> {
        val entryGraphClass = BooleanArray(state.graphClassCount + 1)
        val transparentGraphClass = BooleanArray(state.graphClassCount + 1)
        for (plan in state.plans) {
            if (plan.graphClassIndex >= 0) {
                if (plan.isEntryNode) entryGraphClass[plan.graphClassIndex] = true
                if (plan.isTransparentEntryNode) transparentGraphClass[plan.graphClassIndex] = true
            }
        }

        // Gather and sort the entry-node link table by node id.
        var linkCount = 0L
        for (buffer in state.linkBuffers) linkCount += buffer.size / (MAX_LINK_FIELDS + 1)
        require(linkCount <= Int.MAX_VALUE) { "too many entry nodes: $linkCount" }
        val linkNodes = IntArray(linkCount.toInt())
        val linkData = IntArray(linkCount.toInt() * MAX_LINK_FIELDS)
        var linkFill = 0
        for (buffer in state.linkBuffers) {
            var slot = 0
            buffer.forEach { value ->
                if (slot == 0) {
                    linkNodes[linkFill] = value
                } else {
                    linkData[linkFill * MAX_LINK_FIELDS + slot - 1] = value
                }
                slot++
                if (slot == MAX_LINK_FIELDS + 1) {
                    slot = 0
                    linkFill++
                }
            }
        }
        it.unimi.dsi.fastutil.Arrays.parallelQuickSort(
            0,
            linkFill,
            { first, second -> linkNodes[first].compareTo(linkNodes[second]) },
            { first, second ->
                val node = linkNodes[first]
                linkNodes[first] = linkNodes[second]
                linkNodes[second] = node
                for (slot in 0 until MAX_LINK_FIELDS) {
                    val value = linkData[first * MAX_LINK_FIELDS + slot]
                    linkData[first * MAX_LINK_FIELDS + slot] = linkData[second * MAX_LINK_FIELDS + slot]
                    linkData[second * MAX_LINK_FIELDS + slot] = value
                }
            },
        )

        return mapChunksParallel(dump.chunks) { chunkIndex, _ ->
            val expansion = state.expansionBuffers[chunkIndex]
            val synthetic = IntBlockBuffer(state.syntheticArena)
            val members = IntOpenHashSet(64)
            val queue = IntArrayList(64)
            val ordered = IntArrayList(64)
            val concealed = IntArrayList(16)
            // Stream one record at a time: [ownerDense][walkMains][elementCount][elements...]
            var recordOwner = -1
            var walkMains = -1
            var remainingElements = -1
            var elements = IntArray(256)
            var elementFill = 0
            expansion.forEach { value ->
                when {
                    recordOwner == -1 -> recordOwner = value
                    walkMains == -1 -> walkMains = value
                    remainingElements == -1 -> {
                        remainingElements = value
                        if (elements.size < remainingElements) {
                            elements = IntArray(maxOf(remainingElements, elements.size * 2))
                        }
                        elementFill = 0
                    }
                    else -> {
                        elements[elementFill++] = value
                        remainingElements--
                    }
                }
                if (recordOwner != -1 && walkMains != -1 && remainingElements == 0) {
                    expandOneTable(
                        state,
                        recordOwner,
                        walkMains,
                        elements,
                        elementFill,
                        entryGraphClass,
                        transparentGraphClass,
                        linkNodes,
                        linkData,
                        linkFill,
                        members,
                        queue,
                        ordered,
                        concealed,
                        synthetic,
                    )
                    recordOwner = -1
                    walkMains = -1
                    remainingElements = -1
                }
            }
            synthetic
        }
    }

    private fun expandOneTable(
        state: ExtractionState,
        ownerDense: Int,
        walkMains: Int,
        elements: IntArray,
        elementCount: Int,
        entryGraphClass: BooleanArray,
        transparentGraphClass: BooleanArray,
        linkNodes: IntArray,
        linkData: IntArray,
        linkFill: Int,
        members: IntOpenHashSet,
        queue: IntArrayList,
        ordered: IntArrayList,
        concealed: IntArrayList,
        synthetic: IntBlockBuffer,
    ) {
        members.clear()
        queue.clear()
        ordered.clear()
        concealed.clear()
        for (elementIndex in 0 until elementCount) {
            val element = elements[elementIndex]
            if (!members.add(element)) continue
            val elementClass = state.classIndexOf(element)
            if (entryGraphClass.getOrElse(elementClass) { false }) {
                queue.add(element)
                // Transparent bin nodes (TreeBin, ForwardingNode) are
                // treeification/resize state: walked through, kept alive as a
                // liveness-only extra, concealed from the member set.
                if (transparentGraphClass.getOrElse(elementClass) { false }) {
                    concealed.add(element)
                } else {
                    ordered.add(element)
                }
            } else {
                // Per-object references form a target set, so non-entry
                // elements deduplicate as well.
                ordered.add(element)
            }
        }
        while (!queue.isEmpty) {
            val member = queue.removeInt(queue.size - 1)
            val linkIndex = binarySearch(linkNodes, linkFill, member)
            if (linkIndex < 0) continue
            for (slot in 0 until MAX_LINK_FIELDS) {
                val linked = linkData[linkIndex * MAX_LINK_FIELDS + slot]
                if (linked < 0) continue
                val linkedClass = state.classIndexOf(linked)
                if (!entryGraphClass.getOrElse(linkedClass) { false } || !members.add(linked)) {
                    continue
                }
                queue.add(linked)
                if (transparentGraphClass.getOrElse(linkedClass) { false }) {
                    concealed.add(linked)
                } else {
                    ordered.add(linked)
                }
            }
        }

        val mainCount = ordered.size
        val extrasBase = state.countOf(ownerDense)
        synthetic.add(ownerDense)
        synthetic.add(extrasBase)
        synthetic.add(mainCount + concealed.size)
        for (orderedIndex in 0 until mainCount) synthetic.add(ordered.getInt(orderedIndex))
        for (concealedIndex in 0 until concealed.size) {
            synthetic.add(concealed.getInt(concealedIndex) or EXTRA_FLAG or PROJ_FLAG)
        }
        state.setCount(ownerDense, extrasBase + mainCount + concealed.size)
        if (walkMains + mainCount == 0) state.setFlag(ownerDense, ObjectFlags.LEAF)
    }

    private fun binarySearch(values: IntArray, size: Int, key: Int): Int {
        var low = 0
        var high = size - 1
        while (low <= high) {
            val middle = (low + high) ushr 1
            when {
                values[middle] < key -> low = middle + 1
                values[middle] > key -> high = middle - 1
                else -> return middle
            }
        }
        return -1
    }

    // ------------------------------------------------------------------ CSR build

    private fun prefixSums(state: ExtractionState): Int {
        val offsets = state.counts
        var total = 0L
        for (denseId in 0 until state.objectCount) {
            val count = state.countOf(denseId)
            offsets.set(U32, denseId.toLong() shl 2, total.toInt())
            total += count
        }
        require(total <= Int.MAX_VALUE) { "targets overflow: $total" }
        offsets.set(U32, state.objectCount.toLong() shl 2, total.toInt())
        return total.toInt()
    }

    private fun scatter(
        state: ExtractionState,
        syntheticBuffers: Array<IntBlockBuffer>,
        targets: MemorySegment,
    ) {
        val offsets = state.counts
        val spillArena = Arena.ofShared()
        try {
            mapChunksParallel(dump.chunks) { chunkIndex, _ ->
                val reader = IntSpillReader(
                    state.spillDirectory.file("chunk-$chunkIndex"),
                    spillArena,
                )
                var cursor = 0L
                while (cursor < reader.intCount) {
                    val denseId = reader[cursor]
                    val mainCount = reader[cursor + 1]
                    val extrasCount = reader[cursor + 2]
                    cursor += 3
                    var write = offsets.get(U32, denseId.toLong() shl 2).toLong()
                    for (mainIndex in 0 until mainCount) {
                        targets.set(U32, write shl 2, reader[cursor + mainIndex])
                        write++
                    }
                    for (extraIndex in 0 until extrasCount) {
                        targets.set(
                            U32,
                            write shl 2,
                            reader[cursor + mainCount + extraIndex] or EXTRA_FLAG,
                        )
                        write++
                    }
                    cursor += mainCount + extrasCount
                }

                // Synthetic hash-table rows land after the row's extras;
                // records stream as [dense][extrasBase][mainCount][targets...].
                val synthetic = syntheticBuffers[chunkIndex]
                var headerSlot = 0
                var extrasBase = 0
                var remainingTargets = -1
                var write = 0L
                synthetic.forEach { value ->
                    when {
                        headerSlot == 0 -> {
                            write = offsets.get(U32, value.toLong() shl 2).toLong()
                            headerSlot = 1
                        }
                        headerSlot == 1 -> {
                            extrasBase = value
                            headerSlot = 2
                        }
                        headerSlot == 2 -> {
                            remainingTargets = value
                            write += extrasBase
                            headerSlot = 3
                        }
                        else -> {
                            targets.set(U32, write shl 2, value)
                            write++
                            remainingTargets--
                        }
                    }
                    if (headerSlot == 3 && remainingTargets == 0) {
                        headerSlot = 0
                        remainingTargets = -1
                    }
                }
            }
        } finally {
            spillArena.close()
        }
    }

    private companion object {
        private val U32 = ValueLayout.JAVA_INT_UNALIGNED
    }
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
    private val classStore = state.classStore
    private val plans = state.plans
    private val threadLocalsByAddress = state.dump.threadLocalsByAddress
    private val spill = IntSpillWriter(state.spillDirectory.file("chunk-$chunkIndex"))
    private val links = state.linkBuffers[chunkIndex]
    private val expansion = state.expansionBuffers[chunkIndex]
    private val shallowPairs = state.shallowPairBuffers[chunkIndex]

    private var mains = IntArray(256)
    private var mainCount = 0
    private var extras = IntArray(64)
    private var extrasCount = 0
    private var expandSeeds = IntArray(8)
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
                        if (linkSlot == MAX_LINK_FIELDS) break
                        val value = file.id(dataStart + fieldOffset)
                        links.add(if (value == 0L) -1 else table.denseIdOf(value))
                        linkSlot++
                    }
                    while (linkSlot < MAX_LINK_FIELDS) {
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
        val threadLocals = threadLocalsByAddress.get(address)
        if (threadLocals != null) {
            for (localIndex in 0 until threadLocals.size) {
                counters.rawNonNullRefs++
                addMainValue(threadLocals.getLong(localIndex))
            }
        }

        // Backbone fields (tree root, list head/tail) defer to expansion: the
        // chain expansion rewires this instance to its full element set.
        val hasExpansion = plan.expandOffsets.isNotEmpty()
        if (hasExpansion) {
            var seedCount = 0
            expandSeeds = ensure(expandSeeds, plan.expandOffsets.size)
            for (fieldOffset in plan.expandOffsets) {
                val value = file.id(dataStart + fieldOffset)
                if (value == 0L) continue
                counters.rawNonNullRefs++
                val target = table.denseIdOf(value)
                if (target >= 0) {
                    expandSeeds[seedCount++] = target
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
            expansion.add(mainCount) // walk mains: expansion must not leaf-fold past them
            expansion.add(seedCount)
            for (seedIndex in 0 until seedCount) expansion.add(expandSeeds[seedIndex])
        }

        finishRecord(denseId, leafWhenEmpty = !hasExpansion)
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
                // Elements defer to expansion: entry-node chains expand there.
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
                expansion.add(0) // walk mains: node tables have none
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
        val projBit = if (scopeTraversable) PROJ_FLAG else 0
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
