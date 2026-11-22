package shapes.hprof

import shapes.graph.AnalysisGraph
import shapes.graph.ClassName
import shapes.graph.LeafShapeAggregate
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout

/** Aggregated statistics of one folded leaf class (a pre-collapsed shape). */
class LeafShapeStats(
    val classIndex: Int,
    var instanceCount: Long = 0,
    var shallowTotal: Long = 0,
    var shallowMin: Long = Long.MAX_VALUE,
    var shallowMax: Long = 0,
    var rootCount: Long = 0,
) {
    fun add(shallow: Long) {
        instanceCount++
        shallowTotal += shallow
        if (shallow < shallowMin) shallowMin = shallow
        if (shallow > shallowMax) shallowMax = shallow
    }
}

/**
 * Off-heap CSR object graph with folded leaves.
 *
 * Node ids are dense `[0, nodeCount)`. A target value `>= 0` is a node id; a
 * negative target `-(ordinal + 1)` references a folded leaf class whose
 * aggregate statistics live in [leafShapes]. Node shallow sizes come from the
 * per-class model except arrays, which carry explicit overrides.
 */
class HeapGraph internal constructor(
    private val arena: Arena,
    val nodeCount: Int,
    override val classNames: List<ClassName>,
    /** u32 row offsets, `nodeCount + 1` entries. */
    private val offsets: MemorySegment,
    /** u32 targets (sign-encoded as described above). */
    private val targets: MemorySegment,
    /** u16 graph class index per node. */
    private val classIndices: MemorySegment,
    /** Modeled shallow size per graph class (instances). */
    private val classShallowSizes: LongArray,
    private val instanceShallowOverrides: BooleanArray,
    /** Array-node shallow overrides: packed (nodeId << 32 | shallow), sorted. */
    private val shallowOverrides: LongArray,
    private val shallowOverrideCount: Int,
    override val leafShapes: List<LeafShapeAggregate>,
    /** Node ids of analysis roots: class-closure roots, or the liveness seeds of a whole heap. */
    override val roots: IntArray,
    /** All selected objects including folded leaves. */
    override val totalObjectCount: Long,
    override val totalEdgeCount: Long,
    override val sourceObjectCount: Long,
) : AnalysisGraph, AutoCloseable {
    override val size: Int
        get() = nodeCount

    override fun classIndex(objectId: Int): Int = classIndexOf(objectId)

    override fun className(objectId: Int): ClassName = classNameOf(objectId)

    override fun shallowSize(objectId: Int): ULong = shallowSizeOf(objectId).toULong()

    override fun referenceCount(objectId: Int): Int = targetCount(objectId)

    override fun referenceAt(objectId: Int, referenceIndex: Int): Int =
        targetAt(objectId, referenceIndex)

    fun classIndexOf(nodeId: Int): Int =
        classIndices.get(U16, nodeId.toLong() shl 1).toInt() and 0xFFFF

    fun classNameOf(nodeId: Int): ClassName = classNames[classIndexOf(nodeId)]

    fun targetCount(nodeId: Int): Int =
        (offsets.get(U32, (nodeId.toLong() + 1) shl 2) - offsets.get(U32, nodeId.toLong() shl 2))

    fun targetAt(nodeId: Int, targetIndex: Int): Int =
        targets.get(U32, (offsets.get(U32, nodeId.toLong() shl 2).toLong() + targetIndex) shl 2)

    inline fun forEachTarget(nodeId: Int, action: (target: Int) -> Unit) {
        val row = rowBounds(nodeId)
        for (flatIndex in (row ushr 32).toInt() until row.toInt()) {
            action(targetAtFlat(flatIndex))
        }
    }

    @PublishedApi
    internal fun rowBounds(nodeId: Int): Long {
        val start = offsets.get(U32, nodeId.toLong() shl 2).toLong()
        val end = offsets.get(U32, (nodeId.toLong() + 1) shl 2).toLong()
        return (start shl 32) or end
    }

    @PublishedApi
    internal fun targetAtFlat(flatIndex: Int): Int =
        targets.get(U32, flatIndex.toLong() shl 2)

    fun shallowSizeOf(nodeId: Int): Long {
        val classIndex = classIndexOf(nodeId)
        val modeled = classShallowSizes[classIndex]
        if (modeled >= 0 && !instanceShallowOverrides[classIndex]) return modeled
        val override = overrideOf(nodeId)
        return if (override >= 0) override else modeled
    }

    /** Total shallow bytes over all selected objects, including folded leaves. */
    fun totalShallowBytes(): Long {
        var total = 0L
        for (nodeId in 0 until nodeCount) total += shallowSizeOf(nodeId)
        for (leaf in leafShapes) total += leaf.shallowTotal.toLong()
        return total
    }

    private fun overrideOf(nodeId: Int): Long {
        var low = 0
        var high = shallowOverrideCount - 1
        while (low <= high) {
            val middle = (low + high) ushr 1
            val middleNode = (shallowOverrides[middle] ushr 32).toInt()
            when {
                middleNode < nodeId -> low = middle + 1
                middleNode > nodeId -> high = middle - 1
                else -> return shallowOverrides[middle] and 0xFFFFFFFFL
            }
        }
        return -1
    }

    override fun close() = arena.close()

    companion object {
        private val U16 = ValueLayout.JAVA_SHORT_UNALIGNED
        private val U32 = ValueLayout.JAVA_INT_UNALIGNED

        /** Encodes a leaf ordinal as a negative target. */
        fun leafTarget(leafOrdinal: Int): Int = -(leafOrdinal + 1)

        /** Decodes a negative target into a leaf ordinal. */
        fun leafOrdinalOf(target: Int): Int = -target - 1
    }
}
