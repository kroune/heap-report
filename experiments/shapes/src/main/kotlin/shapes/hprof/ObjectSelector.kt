package shapes.hprof

import it.unimi.dsi.fastutil.ints.IntArrayList
import shapes.Log
import shapes.graph.LeafShapeAggregate
import shapes.hprof.ObjectReferences.Companion.EXTRA_FLAG
import shapes.hprof.ObjectReferences.Companion.PROJ_FLAG
import shapes.hprof.ObjectReferences.Companion.TARGET_MASK
import shapes.phase
import java.lang.foreign.ValueLayout

/** Which objects of the dump become the analysis graph. */
sealed interface Selection {
    /**
     * Every live non-class object. With [foldLeaves], objects without
     * structural references collapse into per-class leaf aggregates instead
     * of becoming nodes.
     */
    class WholeHeap(val foldLeaves: Boolean = true) : Selection

    /**
     * The reachable closure of a class's live instances, which become the
     * analysis roots. Roots may be leaves, so nothing folds.
     */
    class ClassClosure(val className: String) : Selection
}

/**
 * Stage 3 of extraction: GC-root liveness with the class-statics fixpoint,
 * scope marking, leaf folding and in-place compaction to the dense analysis
 * graph. Takes ownership of the receiver.
 */
fun ObjectReferences.select(selection: Selection, log: Log): HeapGraph =
    log.phase(
        "select",
        { "${it.nodeCount} nodes, ${it.totalEdgeCount} edges, ${it.leafShapes.size} leaf shapes, ${it.totalObjectCount} objects" },
    ) { ObjectSelector(this, selection, log)() }

internal class ObjectSelector(
    private val references: ObjectReferences,
    selection: Selection,
    private val log: Log,
) {
    private val objectCount = references.objectCount
    private val classBase = references.classTargetBase
    private val offsets = references.offsets
    private val targets = references.targets
    private val graphClasses = references.graphClasses
    private val foldLeaves = (selection as? Selection.WholeHeap)?.foldLeaves ?: false
    private val scopeClassName = (selection as? Selection.ClassClosure)?.className

    operator fun invoke(): HeapGraph {
        log.phase("live", { "$it objects" }) { markLive() }
        val scopeRoots = selectScope()
        val leafState = foldLeafStats()
        val graph = compact(leafState, scopeRoots)
        references.metaArena.close()
        return graph
    }

    // ------------------------------------------------------------------ liveness

    private fun markLive(): Long {
        val classStore = references.classes
        val classLive = BooleanArray(classStore.size)
        val graphClassFired = BooleanArray(graphClasses.size + 1)
        val storeClassesByGraph = graphClasses.storeClassesByGraphClass
        val staticTargets = references.classStatics

        val queue = IntRingQueue()
        var liveCount = 0L

        fun fireClass(storeIndex: Int) {
            var cursor = storeIndex
            while (cursor >= 0 && !classLive[cursor]) {
                classLive[cursor] = true
                for (target in staticTargets[cursor]) {
                    if (target >= classBase) {
                        fireClass(target - classBase)
                    } else if (!references.hasFlag(target, ObjectFlags.LIVE)) {
                        references.setFlag(target, ObjectFlags.LIVE)
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
            for (storeIndex in storeClassesByGraph[graphIndex]) fireClass(storeIndex)
        }

        for (target in references.roots) {
            if (target >= classBase) {
                fireClass(target - classBase)
            } else if (!references.hasFlag(target, ObjectFlags.LIVE)) {
                references.setFlag(target, ObjectFlags.LIVE)
                liveCount++
                queue.push(target)
            }
        }

        while (!queue.isEmpty) {
            val node = queue.pop()
            fireGraphClass(references.classIndexOf(node))
            val rowStart = references.rowStart(node)
            val rowEnd = references.rowEnd(node)
            for (flat in rowStart until rowEnd) {
                val raw = references.targetAt(flat) and TARGET_MASK
                if (raw >= classBase) {
                    fireClass(raw - classBase)
                } else if (!references.hasFlag(raw, ObjectFlags.LIVE)) {
                    references.setFlag(raw, ObjectFlags.LIVE)
                    liveCount++
                    queue.push(raw)
                }
            }
        }
        return liveCount
    }

    // ------------------------------------------------------------------ scope

    /** Marks the SCOPE flag; returns scope roots (dense ids). */
    private fun selectScope(): IntArray {
        if (scopeClassName == null) {
            for (denseId in 0 until objectCount) {
                val flags = references.flagsOf(denseId)
                if (flags and ObjectFlags.LIVE != 0 && flags and ObjectFlags.EXCLUDED == 0) {
                    references.setFlag(denseId, ObjectFlags.SCOPE)
                }
            }
            // Whole-heap scope roots: GC-root targets plus class statics —
            // exactly the objects the liveness walk was seeded with. They
            // seed the analyzer's observability cascade and the per-shape
            // root counts.
            val seen = LongArray((objectCount + 63) ushr 6)
            val seeds = IntArrayList()
            fun addSeed(denseId: Int) {
                val flags = references.flagsOf(denseId)
                if (flags and ObjectFlags.LIVE == 0 || flags and ObjectFlags.EXCLUDED != 0) return
                val word = denseId ushr 6
                val bit = 1L shl (denseId and 63)
                if (seen[word] and bit != 0L) return
                seen[word] = seen[word] or bit
                seeds.add(denseId)
            }
            for (target in references.roots) if (target < classBase) addSeed(target)
            for (retained in references.classStatics) {
                for (target in retained) if (target < classBase) addSeed(target)
            }
            return seeds.toIntArray()
        }

        val scopeGraphClass = graphClasses.indexOf(scopeClassName)
        require(scopeGraphClass >= 0) { "class not found in heap: $scopeClassName" }

        val queue = IntRingQueue()
        val roots = IntArrayList()
        for (denseId in 0 until objectCount) {
            if (references.classIndexOf(denseId) != scopeGraphClass) continue
            val flags = references.flagsOf(denseId)
            if (flags and ObjectFlags.LIVE == 0 || flags and ObjectFlags.EXCLUDED != 0) continue
            roots.add(denseId)
            if (flags and ObjectFlags.SCOPE == 0) {
                references.setFlag(denseId, ObjectFlags.SCOPE)
                queue.push(denseId)
            }
        }
        require(roots.isNotEmpty()) { "no live instances of $scopeClassName" }

        while (!queue.isEmpty) {
            val node = queue.pop()
            val rowStart = references.rowStart(node)
            val rowEnd = references.rowEnd(node)
            for (flat in rowStart until rowEnd) {
                val encoded = references.targetAt(flat)
                // Scope follows structural edges plus projection-dropped ones:
                // membership is decided on filtered but unprojected references.
                if (encoded and EXTRA_FLAG != 0 && encoded and PROJ_FLAG == 0) continue
                val raw = encoded and TARGET_MASK
                if (raw >= classBase) continue
                val flags = references.flagsOf(raw)
                if (flags and ObjectFlags.EXCLUDED != 0 || flags and ObjectFlags.SCOPE != 0) continue
                references.setFlag(raw, ObjectFlags.SCOPE)
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
        val pairs = references.shallowOverrides.toSortedPairs()

        val leafOrdinalByClass = IntArray(graphClasses.size + 1) { -1 }
        val leafShapes = ArrayList<LeafShapeStats>()
        var foldedObjects = 0L
        if (foldLeaves) {
            var pairCursor = 0
            for (denseId in 0 until objectCount) {
                val flags = references.flagsOf(denseId)
                while (pairCursor < pairs.size && (pairs[pairCursor] ushr 32).toInt() < denseId) {
                    pairCursor++
                }
                if (flags and ObjectFlags.SCOPE == 0 || flags and ObjectFlags.LEAF == 0) continue
                val graphClass = references.classIndexOf(denseId)
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
                        graphClasses.shallowSizes[graphClass]
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

        val newClassIdx = references.graphArena.allocate(maxOf(nodeCount.toLong(), 1) shl 1)
        val overrides = it.unimi.dsi.fastutil.longs.LongArrayList()
        val pairs = leafState.sortedShallowPairs
        var pairCursor = 0
        var writeCursor = 0L
        var newId = 0
        var strippedExcluded = 0L
        var foldedLeafEdges = 0L

        for (denseId in 0 until objectCount) {
            while (pairCursor < pairs.size && (pairs[pairCursor] ushr 32).toInt() < denseId) {
                pairCursor++
            }
            if (!isKept(denseId)) continue
            val rowStart = references.rowStart(denseId)
            val rowEnd = references.rowEnd(denseId)
            offsets.set(U32, newId.toLong() shl 2, writeCursor.toInt())

            val graphClass = references.classIndexOf(denseId)
            newClassIdx.set(ValueLayout.JAVA_SHORT_UNALIGNED, newId.toLong() shl 1, graphClass.toShort())
            if (pairCursor < pairs.size && (pairs[pairCursor] ushr 32).toInt() == denseId) {
                overrides.add((newId.toLong() shl 32) or (pairs[pairCursor] and 0xFFFFFFFFL))
            }

            for (flat in rowStart until rowEnd) {
                val encoded = references.targetAt(flat)
                if (encoded and EXTRA_FLAG != 0) continue
                val raw = encoded and TARGET_MASK
                check(raw < classBase) { "main edge to class object escaped extras" }
                val targetFlags = references.flagsOf(raw)
                if (targetFlags and ObjectFlags.EXCLUDED != 0) {
                    strippedExcluded++
                    continue
                }
                check(targetFlags and ObjectFlags.SCOPE != 0) {
                    "edge from kept $denseId to out-of-scope $raw"
                }
                val value = if (foldLeaves && targetFlags and ObjectFlags.LEAF != 0) {
                    foldedLeafEdges++
                    HeapGraph.leafTarget(leafState.leafOrdinalByClass[references.classIndexOf(raw)])
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
        log(
            "compact: $nodeCount nodes, $writeCursor edges " +
                "($foldedLeafEdges to leaves, $strippedExcluded stripped)"
        )

        // Leaf-folded root objects are not nodes; they drop out of the root
        // id list (their fold aggregates keep rootCount separately).
        val rootIdList = IntArrayList(scopeRoots.size)
        for (root in scopeRoots) {
            if (keptBits[root ushr 6] and (1L shl (root and 63)) != 0L) {
                rootIdList.add(rankOf(keptBits, rankPrefix, root))
            }
        }

        return HeapGraph(
            arena = references.graphArena,
            nodeCount = nodeCount,
            classNames = graphClasses.names,
            offsets = offsets,
            targets = targets,
            classIndices = newClassIdx,
            classShallowSizes = graphClasses.shallowSizes,
            shallowOverrides = overrides.toLongArray(),
            leafShapes = leafState.leafShapes.map { stats ->
                LeafShapeAggregate(
                    classIndex = stats.classIndex,
                    instanceCount = stats.instanceCount,
                    shallowTotal = stats.shallowTotal.toULong(),
                    shallowMin = stats.shallowMin.toULong(),
                    shallowMax = stats.shallowMax.toULong(),
                    rootCount = stats.rootCount,
                )
            },
            roots = rootIdList.toIntArray(),
            totalObjectCount = nodeCount.toLong() + leafState.foldedObjects,
            totalEdgeCount = writeCursor,
            sourceObjectCount = objectCount.toLong(),
        )
    }

    private fun isKept(denseId: Int): Boolean {
        val flags = references.flagsOf(denseId)
        if (flags and ObjectFlags.SCOPE == 0) return false
        return !(foldLeaves && flags and ObjectFlags.LEAF != 0)
    }

    private fun rankOf(keptBits: LongArray, rankPrefix: IntArray, denseId: Int): Int {
        val word = denseId ushr 6
        val mask = (1L shl (denseId and 63)) - 1
        return rankPrefix[word] + java.lang.Long.bitCount(keptBits[word] and mask)
    }

    private companion object {
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
