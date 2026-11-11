package shapes.analysis

import shapes.graph.ClassName

enum class ShapeKind {
    LEAF,
    NODE,
    SCC,
}

data class SccMemberConnection(
    val targetMemberIndex: Int,
    val multiplicity: Int,
)

data class SccMemberEntry(
    val className: ClassName,
    val internalConnections: List<SccMemberConnection>,
)

class ShapeEntry(
    val id: Int,
    val digest: ShapeDigest,
    val kind: ShapeKind,
    val label: String,
    val instanceCount: Long,
    val shallowTotal: ULong,
    val shallowMin: ULong,
    val shallowMax: ULong,
    val rootCount: Long,
    val sccInstanceSize: Int,
    val internalEdgesPerInstance: Int,
    val edgesPerMember: Boolean,
    val members: List<SccMemberEntry>?,
    val membersComplete: Boolean,
)

data class ShapeEdge(
    val shapeId: Int,
    val childShapeId: Int,
    val multiplicity: Long,
)

class ShapeAnalysisResult(
    val reachableObjectCount: Int,
    val entries: List<ShapeEntry>,
    val edges: List<ShapeEdge>,
    val rootsHistogram: Map<Int, Long>,
) {
    fun edgesOf(shapeId: Int): List<ShapeEdge> = edges.filter { it.shapeId == shapeId }
}
