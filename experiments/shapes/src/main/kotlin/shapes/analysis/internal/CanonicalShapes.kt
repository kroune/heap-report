package shapes.analysis.internal

import shapes.analysis.ShapeDigest
import shapes.analysis.ShapeKind
import shapes.analysis.SccMemberConnection
import shapes.graph.ClassName

internal sealed class CanonicalShape {
    abstract val internalId: Int
    abstract val kind: ShapeKind
    abstract val digest: ShapeDigest
}

internal sealed class AcyclicShape : CanonicalShape() {
    abstract val className: ClassName
    abstract val classIndex: Int
    abstract val packedChildEdges: LongArray
}

internal class LeafShape(
    override val internalId: Int,
    override val className: ClassName,
    override val classIndex: Int,
    override val digest: ShapeDigest,
) : AcyclicShape() {
    override val kind: ShapeKind = ShapeKind.LEAF
    override val packedChildEdges: LongArray = LongArray(0)
}

internal class NodeShape(
    override val internalId: Int,
    override val className: ClassName,
    override val classIndex: Int,
    override val packedChildEdges: LongArray,
    override val digest: ShapeDigest,
) : AcyclicShape() {
    override val kind: ShapeKind = ShapeKind.NODE
}

internal open class CyclicShape(
    override val internalId: Int,
    override val digest: ShapeDigest,
    val packedChildEdges: LongArray,
    val totalExternalEdges: List<CanonicalShapeEdge>,
    val members: List<CanonicalSccMember>,
    val quotient: Boolean,
) : CanonicalShape() {
    override val kind: ShapeKind = ShapeKind.SCC
}

internal data class CanonicalShapeEdge(
    val child: CanonicalShape,
    val multiplicity: Int,
)

internal data class CanonicalSccMember(
    val className: ClassName,
    val internalReferenceCount: Int,
    val internalConnections: List<SccMemberConnection>,
    val externalEdges: List<CanonicalShapeEdge>,
)

/** Zero-cost view of the packed child-shape id and multiplicity. */
@JvmInline
internal value class ChildShapeEdge(val packed: Long) {
    constructor(childShapeId: Int, multiplicity: Int) : this(
        (childShapeId.toLong() shl Int.SIZE_BITS) or
            (multiplicity.toLong() and MULTIPLICITY_MASK)
    )

    val childShapeId: Int
        get() = (packed ushr Int.SIZE_BITS).toInt()

    val multiplicity: Int
        get() = packed.toInt()

    private companion object {
        const val MULTIPLICITY_MASK = 0xFFFFFFFFL
    }
}

internal data class ChildShapeReference(
    val childShapeId: Int,
    val multiplicity: Int,
)

internal data class SccMemberStructuralKey(
    val className: ClassName,
    val internalColorRanks: List<Int>,
    val externalEdges: List<ChildShapeReference>,
)

internal data class SccStructuralKey(
    val members: List<SccMemberStructuralKey>,
)
