package shapes.hprof

import it.unimi.dsi.fastutil.ints.IntArrayList
import it.unimi.dsi.fastutil.ints.IntOpenHashSet
import shapes.graph.ClassName
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout

/**
 * Pass C of hprof extraction: hash-table chain expansion, liveness marking,
 * scope selection, leaf folding and in-place CSR compaction.
 */
internal class HeapGraphAssembler(
    private val state: ExtractionState,
    private val foldLeaves: Boolean,
    private val scopeClassName: String?,
    private val progress: (String) -> Unit,
) {
    private val objectCount = state.objectCount
    private val classBase = state.classTargetBase
    private val offsets = state.counts // prefix-summed in place
    private lateinit var targets: MemorySegment

    fun assemble(): HeapGraph {
        var phaseStart = System.nanoTime()
        expandNodeTables()
        state.expansionArena.close()
        progress("expand: hash-table chains in ${elapsed(phaseStart)} ms")

        phaseStart = System.nanoTime()
        val rootTargets = resolveRoots()
        val staticTargets = resolveClassRetained()
        state.table.close()
        val totalTargets = prefixSums()
        targets = mapScratchSegment(state.graphArena, totalTargets.toLong() shl 2)
        scatter()
        state.syntheticArena.close()
        progress("scatter: $totalTargets targets in ${elapsed(phaseStart)} ms")

        phaseStart = System.nanoTime()
        val liveCount = markLive(rootTargets, staticTargets)
        progress("live: $liveCount objects in ${elapsed(phaseStart)} ms")

        phaseStart = System.nanoTime()
        val scopeRoots = selectScope()
        val leafState = foldLeafStats()
        val graph = compact(leafState, scopeRoots)
        progress(
            "graph: ${graph.nodeCount} nodes, ${graph.totalEdgeCount} edges, " +
                "${graph.leafShapes.size} leaf shapes, ${graph.totalObjectCount} objects " +
                "in ${elapsed(phaseStart)} ms"
        )

        state.spillDirectory.close()
        state.metaArena.close()
        return graph
    }

    // ------------------------------------------------------------------ expansion

    private fun expandNodeTables() {
        val entryGraphClass = BooleanArray(state.graphClassCount + 1)
        for (plan in state.plans) {
            if (plan.isEntryNode && plan.graphClassIndex >= 0) {
                entryGraphClass[plan.graphClassIndex] = true
            }
        }

        // Gather and sort the entry-node link table by node id.
        var linkCount = 0L
        for (buffer in state.linkBuffers) linkCount += buffer.size / 4
        require(linkCount <= Int.MAX_VALUE) { "too many entry nodes: $linkCount" }
        val linkNodes = IntArray(linkCount.toInt())
        val linkData = IntArray(linkCount.toInt() * 3)
        var linkFill = 0
        for (buffer in state.linkBuffers) {
            var slot = 0
            buffer.forEach { value ->
                if (slot == 0) {
                    linkNodes[linkFill] = value
                } else {
                    linkData[linkFill * 3 + slot - 1] = value
                }
                slot++
                if (slot == 4) {
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
                for (slot in 0..2) {
                    val value = linkData[first * 3 + slot]
                    linkData[first * 3 + slot] = linkData[second * 3 + slot]
                    linkData[second * 3 + slot] = value
                }
            },
        )

        val syntheticBuffers = mapChunksParallel(state.index.chunks) { chunkIndex, _ ->
            val expansion = state.expansionBuffers[chunkIndex]
            val synthetic = IntBlockBuffer(state.syntheticArena)
            val members = IntOpenHashSet(64)
            val queue = IntArrayList(64)
            val ordered = IntArrayList(64)
            // Stream one record at a time: [arrayDense][elementCount][elements...]
            var recordArray = -1
            var remainingElements = -1
            var elements = IntArray(256)
            var elementFill = 0
            expansion.forEach { value ->
                when {
                    recordArray == -1 -> recordArray = value
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
                if (recordArray != -1 && remainingElements == 0) {
                    expandOneTable(
                        recordArray,
                        elements,
                        elementFill,
                        entryGraphClass,
                        linkNodes,
                        linkData,
                        linkFill,
                        members,
                        queue,
                        ordered,
                        synthetic,
                    )
                    recordArray = -1
                    remainingElements = -1
                }
            }
            synthetic
        }
        this.syntheticBuffers = syntheticBuffers
    }

    private lateinit var syntheticBuffers: Array<IntBlockBuffer>

    private fun expandOneTable(
        arrayDense: Int,
        elements: IntArray,
        elementCount: Int,
        entryGraphClass: BooleanArray,
        linkNodes: IntArray,
        linkData: IntArray,
        linkFill: Int,
        members: IntOpenHashSet,
        queue: IntArrayList,
        ordered: IntArrayList,
        synthetic: IntBlockBuffer,
    ) {
        members.clear()
        queue.clear()
        ordered.clear()
        for (elementIndex in 0 until elementCount) {
            val element = elements[elementIndex]
            val elementClass = state.classIndexOf(element)
            if (entryGraphClass.getOrElse(elementClass) { false }) {
                if (members.add(element)) {
                    ordered.add(element)
                    queue.add(element)
                }
            } else if (members.add(element)) {
                // The MAT outbound index is a target set, so non-entry
                // elements deduplicate as well.
                ordered.add(element)
            }
        }
        while (!queue.isEmpty) {
            val member = queue.removeInt(queue.size - 1)
            val linkIndex = binarySearch(linkNodes, linkFill, member)
            if (linkIndex < 0) continue
            for (slot in 0..2) {
                val linked = linkData[linkIndex * 3 + slot]
                if (linked >= 0 &&
                    entryGraphClass.getOrElse(state.classIndexOf(linked)) { false } &&
                    members.add(linked)
                ) {
                    ordered.add(linked)
                    queue.add(linked)
                }
            }
        }

        val mainCount = ordered.size
        val extrasBase = state.countOf(arrayDense)
        synthetic.add(arrayDense)
        synthetic.add(extrasBase)
        synthetic.add(mainCount)
        for (orderedIndex in 0 until mainCount) synthetic.add(ordered.getInt(orderedIndex))
        state.setCount(arrayDense, extrasBase + mainCount)
        if (mainCount == 0) state.setFlag(arrayDense, ObjectFlags.LEAF)
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

    // ------------------------------------------------------------------ roots and statics

    /** Encoded targets: dense id, or classBase + store class index. */
    private fun resolveRoots(): IntArray {
        val resolved = IntArrayList(state.index.rootAddresses.size)
        var dangling = 0
        for (address in state.index.rootAddresses) {
            if (address == 0L) continue
            val dense = state.table.denseIdOf(address)
            if (dense >= 0) {
                resolved.add(dense)
                continue
            }
            val classIndex = state.index.classStore.indexOf(address)
            if (classIndex >= 0) resolved.add(classBase + classIndex) else dangling++
        }
        if (dangling > 0) progress("roots: $dangling dangling root addresses ignored")
        return resolved.toIntArray()
    }

    private fun resolveClassRetained(): Array<IntArray> {
        val classStore = state.index.classStore
        return Array(classStore.size) { classIndex ->
            val retained = classStore[classIndex].retainedAddresses
            if (retained.isEmpty()) return@Array EMPTY_INTS
            val resolved = IntArrayList(retained.size)
            for (address in retained) {
                val dense = state.table.denseIdOf(address)
                if (dense >= 0) {
                    resolved.add(dense)
                } else {
                    val targetClass = classStore.indexOf(address)
                    if (targetClass >= 0) resolved.add(classBase + targetClass)
                }
            }
            resolved.toIntArray()
        }
    }

    // ------------------------------------------------------------------ CSR build

    private fun prefixSums(): Int {
        var total = 0L
        for (denseId in 0 until objectCount) {
            val count = state.countOf(denseId)
            offsets.set(U32, denseId.toLong() shl 2, total.toInt())
            total += count
        }
        require(total <= Int.MAX_VALUE) { "targets overflow: $total" }
        offsets.set(U32, objectCount.toLong() shl 2, total.toInt())
        return total.toInt()
    }

    private fun scatter() {
        val spillArena = Arena.ofShared()
        try {
            mapChunksParallel(state.index.chunks) { chunkIndex, _ ->
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

    // ------------------------------------------------------------------ liveness

    private fun markLive(rootTargets: IntArray, staticTargets: Array<IntArray>): Long {
        val classStore = state.index.classStore
        val classLive = BooleanArray(classStore.size)
        val graphClassFired = BooleanArray(state.graphClassCount + 1)
        val storeClassesByGraph = HashMap<Int, IntArrayList>()
        for (classIndex in state.plans.indices) {
            val graphIndex = state.plans[classIndex].graphClassIndex
            if (graphIndex >= 0) {
                storeClassesByGraph.getOrPut(graphIndex) { IntArrayList(2) }.add(classIndex)
            }
        }

        val queue = IntRingQueue()
        var liveCount = 0L

        fun fireClass(storeIndex: Int) {
            var cursor = storeIndex
            while (cursor >= 0 && !classLive[cursor]) {
                classLive[cursor] = true
                for (target in staticTargets[cursor]) {
                    if (target >= classBase) {
                        fireClass(target - classBase)
                    } else if (!state.hasFlag(target, ObjectFlags.LIVE)) {
                        state.setFlag(target, ObjectFlags.LIVE)
                        liveCount++
                        queue.push(target)
                    }
                }
                cursor = classStore[cursor].superIndex
            }
        }

        fun fireGraphClass(graphIndex: Int) {
            if (graphClassFired[graphIndex]) return
            graphClassFired[graphIndex] = true
            storeClassesByGraph[graphIndex]?.let { stores ->
                for (storeIndex in 0 until stores.size) fireClass(stores.getInt(storeIndex))
            }
        }

        for (target in rootTargets) {
            if (target >= classBase) {
                fireClass(target - classBase)
            } else if (!state.hasFlag(target, ObjectFlags.LIVE)) {
                state.setFlag(target, ObjectFlags.LIVE)
                liveCount++
                queue.push(target)
            }
        }

        while (!queue.isEmpty) {
            val node = queue.pop()
            fireGraphClass(state.classIndexOf(node))
            val rowStart = offsets.get(U32, node.toLong() shl 2).toLong()
            val rowEnd = offsets.get(U32, (node.toLong() + 1) shl 2).toLong()
            for (flat in rowStart until rowEnd) {
                val raw = targets.get(U32, flat shl 2) and TARGET_MASK
                if (raw >= classBase) {
                    fireClass(raw - classBase)
                } else if (!state.hasFlag(raw, ObjectFlags.LIVE)) {
                    state.setFlag(raw, ObjectFlags.LIVE)
                    liveCount++
                    queue.push(raw)
                }
            }
        }
        return liveCount
    }

    // ------------------------------------------------------------------ scope

    /** Marks the SCOPE flag; returns scope roots (dense ids) for class scopes. */
    private fun selectScope(): IntArray {
        if (scopeClassName == null) {
            for (denseId in 0 until objectCount) {
                val flags = state.flagsOf(denseId)
                if (flags and ObjectFlags.LIVE != 0 && flags and ObjectFlags.EXCLUDED == 0) {
                    state.setFlag(denseId, ObjectFlags.SCOPE)
                }
            }
            return IntArray(0)
        }

        var scopeGraphClass = -1
        for (graphIndex in 0 until state.graphClassCount) {
            if (state.graphClassNamesSlots[graphIndex] == scopeClassName) {
                scopeGraphClass = graphIndex
                break
            }
        }
        require(scopeGraphClass >= 0) { "class not found in heap: $scopeClassName" }

        val queue = IntRingQueue()
        val roots = IntArrayList()
        for (denseId in 0 until objectCount) {
            if (state.classIndexOf(denseId) != scopeGraphClass) continue
            val flags = state.flagsOf(denseId)
            if (flags and ObjectFlags.LIVE == 0 || flags and ObjectFlags.EXCLUDED != 0) continue
            roots.add(denseId)
            if (flags and ObjectFlags.SCOPE == 0) {
                state.setFlag(denseId, ObjectFlags.SCOPE)
                queue.push(denseId)
            }
        }
        require(roots.size > 0) { "no live instances of $scopeClassName" }

        while (!queue.isEmpty) {
            val node = queue.pop()
            val rowStart = offsets.get(U32, node.toLong() shl 2).toLong()
            val rowEnd = offsets.get(U32, (node.toLong() + 1) shl 2).toLong()
            for (flat in rowStart until rowEnd) {
                val encoded = targets.get(U32, flat shl 2)
                // Scope follows structural edges plus projection-dropped ones,
                // like the MAT-path BFS over filtered unprojected references.
                if (encoded and EXTRA_FLAG != 0 && encoded and PROJ_FLAG == 0) continue
                val raw = encoded and TARGET_MASK
                if (raw >= classBase) continue
                val flags = state.flagsOf(raw)
                if (flags and ObjectFlags.EXCLUDED != 0 || flags and ObjectFlags.SCOPE != 0) continue
                state.setFlag(raw, ObjectFlags.SCOPE)
                queue.push(raw)
            }
        }
        return roots.toIntArray()
    }

    // ------------------------------------------------------------------ leaf folding

    private class LeafFoldState(
        val sortedShallowPairs: LongArray,
        val leafOrdinalByClass: IntArray,
        val leafShapes: ArrayList<LeafShapeStats>,
        var foldedObjects: Long,
    )

    private fun foldLeafStats(): LeafFoldState {
        var pairCount = 0L
        for (buffer in state.shallowPairBuffers) pairCount += buffer.size / 2
        require(pairCount <= Int.MAX_VALUE) { "too many shallow overrides" }
        val pairs = LongArray(pairCount.toInt())
        var pairFill = 0
        for (buffer in state.shallowPairBuffers) {
            var pendingKey = 0L
            var haveKey = false
            buffer.forEach { value ->
                if (!haveKey) {
                    pendingKey = value.toLong() shl 32
                    haveKey = true
                } else {
                    pairs[pairFill++] = pendingKey or (value.toLong() and 0xFFFFFFFFL)
                    haveKey = false
                }
            }
        }
        state.shallowArena.close() // heap copy complete
        java.util.Arrays.parallelSort(pairs)

        val leafOrdinalByClass = IntArray(state.graphClassCount + 1) { -1 }
        val leafShapes = ArrayList<LeafShapeStats>()
        var foldedObjects = 0L
        if (foldLeaves) {
            var pairCursor = 0
            for (denseId in 0 until objectCount) {
                val flags = state.flagsOf(denseId)
                while (pairCursor < pairs.size && (pairs[pairCursor] ushr 32).toInt() < denseId) {
                    pairCursor++
                }
                if (flags and ObjectFlags.SCOPE == 0 || flags and ObjectFlags.LEAF == 0) continue
                val graphClass = state.classIndexOf(denseId)
                var ordinal = leafOrdinalByClass[graphClass]
                if (ordinal < 0) {
                    ordinal = leafShapes.size
                    leafOrdinalByClass[graphClass] = ordinal
                    leafShapes.add(LeafShapeStats(graphClass))
                }
                val shallow =
                    if (pairCursor < pairs.size && (pairs[pairCursor] ushr 32).toInt() == denseId) {
                        pairs[pairCursor] and 0xFFFFFFFFL
                    } else {
                        state.graphClassShallow[graphClass]
                    }
                leafShapes[ordinal].add(shallow)
                foldedObjects++
            }
        }
        return LeafFoldState(pairs, leafOrdinalByClass, leafShapes, foldedObjects)
    }

    // ------------------------------------------------------------------ compaction

    private fun compact(leafState: LeafFoldState, scopeRoots: IntArray): HeapGraph {
        // Rank structure over kept objects for target renumbering.
        val wordCount = (objectCount + 63) ushr 6
        val keptBits = LongArray(wordCount)
        val rankPrefix = IntArray(wordCount + 1)
        var nodeCount = 0
        for (denseId in 0 until objectCount) {
            if (isKept(denseId)) {
                keptBits[denseId ushr 6] = keptBits[denseId ushr 6] or (1L shl (denseId and 63))
                nodeCount++
            }
        }
        var running = 0
        for (word in 0 until wordCount) {
            rankPrefix[word] = running
            running += java.lang.Long.bitCount(keptBits[word])
        }
        rankPrefix[wordCount] = running
        check(running == nodeCount)

        val newClassIdx = state.graphArena.allocate(maxOf(nodeCount.toLong(), 1) shl 1)
        val overrides = it.unimi.dsi.fastutil.longs.LongArrayList()
        val pairs = leafState.sortedShallowPairs
        var pairCursor = 0
        var writeCursor = 0L
        var newId = 0
        var strippedClassTargets = 0L
        var strippedExcluded = 0L
        var foldedLeafEdges = 0L

        for (denseId in 0 until objectCount) {
            while (pairCursor < pairs.size && (pairs[pairCursor] ushr 32).toInt() < denseId) {
                pairCursor++
            }
            if (!isKept(denseId)) continue
            val rowStart = offsets.get(U32, denseId.toLong() shl 2).toLong()
            val rowEnd = offsets.get(U32, (denseId.toLong() + 1) shl 2).toLong()
            offsets.set(U32, newId.toLong() shl 2, writeCursor.toInt())

            val graphClass = state.classIndexOf(denseId)
            newClassIdx.set(ValueLayout.JAVA_SHORT_UNALIGNED, newId.toLong() shl 1, graphClass.toShort())
            if (pairCursor < pairs.size && (pairs[pairCursor] ushr 32).toInt() == denseId) {
                overrides.add((newId.toLong() shl 32) or (pairs[pairCursor] and 0xFFFFFFFFL))
            }

            for (flat in rowStart until rowEnd) {
                val encoded = targets.get(U32, flat shl 2)
                if (encoded and EXTRA_FLAG != 0) continue
                val raw = encoded and TARGET_MASK
                check(raw < classBase) { "main edge to class object escaped extras" }
                val targetFlags = state.flagsOf(raw)
                if (targetFlags and ObjectFlags.EXCLUDED != 0) {
                    strippedExcluded++
                    continue
                }
                check(targetFlags and ObjectFlags.SCOPE != 0) {
                    "edge from kept $denseId to out-of-scope $raw"
                }
                val value = if (foldLeaves && targetFlags and ObjectFlags.LEAF != 0) {
                    foldedLeafEdges++
                    HeapGraph.leafTarget(leafState.leafOrdinalByClass[state.classIndexOf(raw)])
                } else {
                    rankOf(keptBits, rankPrefix, raw)
                }
                targets.set(U32, writeCursor shl 2, value)
                writeCursor++
            }
            newId++
        }
        offsets.set(U32, newId.toLong() shl 2, writeCursor.toInt())
        check(newId == nodeCount)
        progress(
            "compact: $nodeCount nodes, $writeCursor edges " +
                "($foldedLeafEdges to leaves, $strippedClassTargets+$strippedExcluded stripped)"
        )

        val classNames = (0 until state.graphClassCount).map {
            ClassName(state.graphClassNamesSlots[it] ?: "unused-$it")
        }
        val rootIds = IntArray(scopeRoots.size)
        for (rootIndex in scopeRoots.indices) {
            rootIds[rootIndex] = rankOf(keptBits, rankPrefix, scopeRoots[rootIndex])
        }

        return HeapGraph(
            arena = state.graphArena,
            nodeCount = nodeCount,
            classNames = classNames,
            offsets = offsets,
            targets = targets,
            classIndices = newClassIdx,
            classShallowSizes = state.graphClassShallow.copyOf(maxOf(state.graphClassCount, 1)),
            shallowOverrides = overrides.toLongArray(),
            leafShapes = leafState.leafShapes.map { stats ->
                shapes.graph.LeafShapeAggregate(
                    classIndex = stats.classIndex,
                    instanceCount = stats.instanceCount,
                    shallowTotal = stats.shallowTotal.toULong(),
                    shallowMin = stats.shallowMin.toULong(),
                    shallowMax = stats.shallowMax.toULong(),
                    rootCount = stats.rootCount,
                )
            },
            roots = rootIds,
            totalObjectCount = nodeCount.toLong() + leafState.foldedObjects,
            totalEdgeCount = writeCursor,
            sourceObjectCount = objectCount.toLong(),
        )
    }

    private fun isKept(denseId: Int): Boolean {
        val flags = state.flagsOf(denseId)
        if (flags and ObjectFlags.SCOPE == 0) return false
        return !(foldLeaves && flags and ObjectFlags.LEAF != 0)
    }

    private fun rankOf(keptBits: LongArray, rankPrefix: IntArray, denseId: Int): Int {
        val word = denseId ushr 6
        val mask = (1L shl (denseId and 63)) - 1
        return rankPrefix[word] + java.lang.Long.bitCount(keptBits[word] and mask)
    }

    private fun elapsed(startedAt: Long): Long = (System.nanoTime() - startedAt) / 1_000_000

    companion object {
        /** Bit 31: liveness-only edge, stripped from the structural graph. */
        const val EXTRA_FLAG = 1 shl 31

        /** Bit 30: projection-dropped edge, traversed by class-scope selection. */
        const val PROJ_FLAG = 1 shl 30

        const val TARGET_MASK = PROJ_FLAG - 1

        private val EMPTY_INTS = IntArray(0)
        private val U32 = ValueLayout.JAVA_INT_UNALIGNED
    }
}

/** Unbounded int FIFO over recycled 1 Mi-entry blocks. */
internal class IntRingQueue {
    private val blocks = ArrayDeque<IntArray>()
    private val freeBlocks = ArrayDeque<IntArray>()
    private var headIndex = 0
    private var tailIndex = BLOCK_SIZE
    private var size = 0L

    val isEmpty: Boolean
        get() = size == 0L

    fun push(value: Int) {
        if (blocks.isEmpty() || tailIndex == BLOCK_SIZE) {
            blocks.addLast(freeBlocks.removeFirstOrNull() ?: IntArray(BLOCK_SIZE))
            tailIndex = 0
        }
        blocks.last()[tailIndex++] = value
        size++
    }

    fun pop(): Int {
        val block = blocks.first()
        val value = block[headIndex++]
        size--
        if (headIndex == BLOCK_SIZE) {
            blocks.removeFirst()
            freeBlocks.addLast(block)
            headIndex = 0
            if (blocks.isEmpty()) tailIndex = BLOCK_SIZE
        } else if (size == 0L && blocks.size == 1) {
            // keep the single block; reset cursors so capacity is reused
            headIndex = 0
            tailIndex = 0
        }
        return value
    }

    private companion object {
        const val BLOCK_SIZE = 1 shl 20
    }
}
