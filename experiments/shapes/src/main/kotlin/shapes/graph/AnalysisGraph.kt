package shapes.graph

/** Aggregates of one folded leaf class, pre-collapsed during extraction. */
class LeafShapeAggregate(
    val classIndex: Int,
    val instanceCount: Long,
    val shallowTotal: ULong,
    val shallowMin: ULong,
    val shallowMax: ULong,
    val rootCount: Long,
)

/**
 * Graph contract consumed by shape analysis. Node ids are dense `[0, size)`.
 * A negative reference target `-(ordinal + 1)` denotes a folded leaf class
 * described by `leafShapes[ordinal]`; graphs without folding never produce
 * negative targets and expose an empty [leafShapes] list.
 */
interface AnalysisGraph {
    val size: Int

    /** Object count of the originating heap (diagnostics only). */
    val sourceObjectCount: Long

    /** All selected objects: nodes plus folded leaf instances. */
    val totalObjectCount: Long

    val totalEdgeCount: Long

    val classNames: List<ClassName>

    val leafShapes: List<LeafShapeAggregate>

    fun classIndex(objectId: Int): Int

    fun className(objectId: Int): ClassName

    fun shallowSize(objectId: Int): ULong

    fun referenceCount(objectId: Int): Int

    fun referenceAt(objectId: Int, referenceIndex: Int): Int
}
