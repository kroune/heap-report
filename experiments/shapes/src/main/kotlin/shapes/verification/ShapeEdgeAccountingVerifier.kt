package shapes.verification

import shapes.analysis.ShapeAnalysisDebugSnapshot
import shapes.analysis.ShapeAnalysisResult
import shapes.graph.AnalysisGraph
import shapes.relaxation.ShapeRelaxationPolicy
import shapes.relaxation.StructuralRelaxation

/** Full per-reference audit against the internal object-to-shape assignment. */
internal object ShapeEdgeAccountingVerifier {
    internal fun verify(
        graph: AnalysisGraph,
        debug: ShapeAnalysisDebugSnapshot,
        relaxationPolicy: ShapeRelaxationPolicy,
        progress: (String) -> Unit = {},
    ): List<String> {
        val result = debug.result
        val publicIdByDigest = result.entries.associate { it.digest to it.id }
        val publicIdByInternalId = IntArray(debug.collapse.shapes.size) { internalId ->
            publicIdByDigest.getValue(debug.collapse.shapes[internalId].digest)
        }
        val shapeOfObject = IntArray(graph.size) { -1 }
        for (componentId in 0 until debug.components.members.count) {
            val internalShapeId = debug.collapse.shapeOfComponent[componentId]
            val publicShapeId = publicIdByInternalId[internalShapeId]
            debug.components.members.forEachMember(componentId) { objectId ->
                shapeOfObject[objectId] = publicShapeId
            }
        }

        val externalTruth = HashMap<ShapeEdgeKey, Long>()
        val internalTruth = HashMap<Int, Long>()
        val nonExemptShapes = HashSet<Int>()
        val quotientExempt = findQuotientExemptObjects(graph, debug, relaxationPolicy)
        var relaxedSourceCount = 0L

        for (objectId in 0 until graph.size) {
            val sourceShapeId = shapeOfObject[objectId]
            if (sourceShapeId < 0) continue
            if (quotientExempt[objectId] ||
                relaxationPolicy.relaxationOf(graph.classIndex(objectId)) != StructuralRelaxation.EXACT
            ) {
                relaxedSourceCount++
                continue
            }

            nonExemptShapes.add(sourceShapeId)
            for (referenceIndex in 0 until graph.referenceCount(objectId)) {
                val targetId = graph.referenceAt(objectId, referenceIndex)
                val targetShapeId = if (targetId < 0) {
                    publicIdByInternalId[debug.collapse.leafShapeInternalIds[-targetId - 1]]
                } else {
                    shapeOfObject[targetId]
                }
                if (targetShapeId == sourceShapeId) {
                    internalTruth.merge(sourceShapeId, 1L, Long::plus)
                } else {
                    externalTruth.merge(ShapeEdgeKey(sourceShapeId, targetShapeId), 1L, Long::plus)
                }
            }
        }
        if (relaxedSourceCount > 0) {
            progress(
                "verify-edges: $relaxedSourceCount objects of relaxed classes exempted " +
                    "(their concealed edges are accounted by ShapeRelaxationStats)"
            )
        }

        return compareAccounting(result, nonExemptShapes, relaxationPolicy.isEmpty, internalTruth, externalTruth)
    }

    private fun findQuotientExemptObjects(
        graph: AnalysisGraph,
        debug: ShapeAnalysisDebugSnapshot,
        relaxationPolicy: ShapeRelaxationPolicy,
    ): BooleanArray {
        val exempt = BooleanArray(graph.size)
        if (relaxationPolicy.isEmpty) return exempt

        for (componentId in 0 until debug.components.members.count) {
            var hasSetChildrenMember = false
            debug.components.members.forEachMember(componentId) { objectId ->
                val relaxation = relaxationPolicy.relaxationOf(graph.classIndex(objectId))
                if (relaxation == StructuralRelaxation.SET_CHILDREN) hasSetChildrenMember = true
            }
            if (hasSetChildrenMember) {
                debug.components.members.forEachMember(componentId) { objectId ->
                    exempt[objectId] = true
                }
            }
        }
        return exempt
    }

    private fun compareAccounting(
        result: ShapeAnalysisResult,
        nonExemptShapes: Set<Int>,
        noRelaxationRules: Boolean,
        internalTruth: Map<Int, Long>,
        externalTruth: Map<ShapeEdgeKey, Long>,
    ): List<String> {
        val violations = mutableListOf<String>()
        val entriesById = result.entries.associateBy { it.id }
        for (entry in result.entries) {
            if (entry.id !in nonExemptShapes && !noRelaxationRules) continue
            val collapsedInstanceCount = entry.instanceCount / entry.sccInstanceSize
            val emitted = entry.internalEdgesPerInstance * collapsedInstanceCount
            val actual = internalTruth[entry.id] ?: 0L
            if (emitted != actual) {
                violations += "shape #${entry.id} ${entry.kind} ${entry.label.take(50)}: " +
                    "internal emitted $emitted != true $actual"
            }
        }

        val externalEmitted = HashMap<ShapeEdgeKey, Long>()
        for (edge in result.edges) {
            val source = entriesById.getValue(edge.shapeId)
            if (source.id !in nonExemptShapes && !noRelaxationRules) continue
            val expandedMultiplicity = if (source.edgesPerMember) {
                edge.multiplicity * source.instanceCount
            } else {
                edge.multiplicity * (source.instanceCount / source.sccInstanceSize)
            }
            externalEmitted.merge(
                ShapeEdgeKey(edge.shapeId, edge.childShapeId),
                expandedMultiplicity,
                Long::plus,
            )
        }

        for ((edgeKey, emitted) in externalEmitted) {
            val actual = externalTruth[edgeKey] ?: 0L
            if (actual != emitted) {
                violations += "edge #${edgeKey.sourceShapeId} -> #${edgeKey.targetShapeId}: " +
                    "emitted $emitted != true $actual"
            }
        }
        for ((edgeKey, actual) in externalTruth) {
            if (edgeKey !in externalEmitted) {
                violations += "edge #${edgeKey.sourceShapeId} -> #${edgeKey.targetShapeId}: " +
                    "true $actual missing from artifact"
            }
        }
        return violations.take(20)
    }
}

@JvmInline
private value class ShapeEdgeKey(val packed: Long) {
    constructor(sourceShapeId: Int, targetShapeId: Int) : this(
        (sourceShapeId.toLong() shl Int.SIZE_BITS) or
            (targetShapeId.toLong() and 0xFFFFFFFFL)
    )

    val sourceShapeId: Int
        get() = (packed shr Int.SIZE_BITS).toInt()

    val targetShapeId: Int
        get() = packed.toInt()
}
