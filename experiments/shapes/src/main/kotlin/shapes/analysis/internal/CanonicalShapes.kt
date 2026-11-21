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
    /**
     * Members per component instance as accounted: the real member count for
     * exact shapes, the quotient group count for set-children shapes (their
     * duplicates are concealed, not counted).
     */
    val memberCount: Int,
    val quotient: Boolean,
) : CanonicalShape() {
    override val kind: ShapeKind = ShapeKind.SCC
}

/**
 * Large SCC interned by invariant signature (lazy WL): [members] holds either
 * the full member payload in presentational order or, above the payload cap,
 * only the most-frequent classes; aggregate edge totals replace per-member
 * edges either way.
 */
internal class LazySccShape(
    internalId: Int,
    digest: ShapeDigest,
    totalExternalEdges: List<CanonicalShapeEdge>,
    members: List<CanonicalSccMember>,
    memberCount: Int,
    val totalInternalEdges: Int,
    val distinctClassCount: Int,
) : CyclicShape(
    internalId = internalId,
    digest = digest,
    packedChildEdges = LongArray(0),
    totalExternalEdges = totalExternalEdges,
    members = members,
    memberCount = memberCount,
    quotient = false,
)

internal data class CanonicalShapeEdge(
    val child: CanonicalShape,
    val multiplicity: Int,
)

/**
 * One WL color class of an SCC: [memberCount] structurally indistinguishable
 * members sharing one class, one internal connection profile and one external
 * edge list. The refinement fixpoint is equitable, so every member of the
 * group has exactly [internalConnections] (per member, not per group).
 */
internal data class CanonicalSccMember(
    val className: ClassName,
    val memberCount: Int,
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

/** One color class with its member count; counts are 1 under set semantics. */
internal data class SccMemberGroup(
    val key: SccMemberStructuralKey,
    val memberCount: Int,
)

internal data class SccStructuralKey(
    val groups: List<SccMemberGroup>,
)
