package shapes.verification

import shapes.Log
import shapes.analysis.ShapeAnalysisResult
import shapes.analysis.internal.CanonicalShapes
import shapes.analysis.internal.ObjectRelaxations
import shapes.analysis.internal.StronglyConnectedComponents
import shapes.graph.AnalysisGraph
import shapes.relaxation.StructuralRelaxation

/** Full per-reference audit against the internal object-to-shape assignment. */
internal object ShapeEdgeAccountingVerifier {
    internal fun verify(
        graph: AnalysisGraph,
        components: StronglyConnectedComponents,
        relaxations: ObjectRelaxations,
        shapes: CanonicalShapes,
        result: ShapeAnalysisResult,
        log: Log = {},
    ): List<String> {
        val publicIdByDigest = result.entries.associate { it.digest to it.id }
        val publicIdByInternalId = IntArray(shapes.shapes.size) { internalId ->
            publicIdByDigest.getValue(shapes.shapes[internalId].digest)
        }
        val shapeOfObject = IntArray(graph.size) { -1 }
        for (componentId in 0 until components.members.count) {
            val internalShapeId = shapes.shapeOfComponent[componentId]
            val publicShapeId = publicIdByInternalId[internalShapeId]
            components.members.forEachMember(componentId) { objectId ->
                shapeOfObject[objectId] = publicShapeId
            }
        }

        val externalTruth = HashMap<ShapeEdgeKey, Long>()
        val internalTruth = HashMap<Int, Long>()
        val nonExemptShapes = HashSet<Int>()
        val quotientExempt = findQuotientExemptObjects(graph, components, relaxations)
        // A set-children component conceals duplicates, so its shape cannot be
        // audited exactly — even where other, non-relaxed components intern
        // into the same shape (identical structure with nothing to conceal).
        val exemptShapes = HashSet<Int>()
        var relaxedSourceCount = 0L

        for (objectId in 0 until graph.size) {
            val sourceShapeId = shapeOfObject[objectId]
            if (sourceShapeId < 0) continue
            if (quotientExempt[objectId]) {
                relaxedSourceCount++
                exemptShapes.add(sourceShapeId)
                continue
            }
            // Class-only conceals external edges only; internal SCC wiring stays in
            // the key and the payload, so it is still accounted exactly.
            val classOnly = relaxations.relaxationOf(objectId) == StructuralRelaxation.CLASS_ONLY
            if (classOnly) relaxedSourceCount++

            nonExemptShapes.add(sourceShapeId)
            val sourceComponentId = components.assignment.componentOf(objectId)
            for (referenceIndex in 0 until graph.referenceCount(objectId)) {
                val targetId = graph.referenceAt(objectId, referenceIndex)
                // "Internal" is the analyzer's definition: same strongly connected
                // component. Same shape is weaker — every class-only object of a
                // class folds into one LEAF shape across unrelated components.
                if (targetId >= 0 &&
                    components.assignment.componentOf(targetId) == sourceComponentId
                ) {
                    internalTruth.merge(sourceShapeId, 1L, Long::plus)
                    continue
                }
                if (classOnly) continue
                val targetShapeId = if (targetId < 0) {
                    publicIdByInternalId[shapes.leafShapeInternalIds[-targetId - 1]]
                } else {
                    shapeOfObject[targetId]
                }
                externalTruth.merge(ShapeEdgeKey(sourceShapeId, targetShapeId), 1L, Long::plus)
            }
        }
        if (relaxedSourceCount > 0) {
            log(
                "verify-edges: $relaxedSourceCount objects of relaxed classes have their " +
                    "concealed edges accounted by ShapeRelaxationStats instead"
            )
        }

        nonExemptShapes.removeAll(exemptShapes)
        return compareAccounting(result, nonExemptShapes, relaxations.isEmpty, internalTruth, externalTruth)
    }

    private fun findQuotientExemptObjects(
        graph: AnalysisGraph,
        components: StronglyConnectedComponents,
        relaxations: ObjectRelaxations,
    ): BooleanArray {
        val exempt = BooleanArray(graph.size)
        if (relaxations.isEmpty) return exempt

        for (componentId in 0 until components.members.count) {
            var hasSetChildrenMember = false
            components.members.forEachMember(componentId) { objectId ->
                val relaxation = relaxations.relaxationOf(objectId)
                if (relaxation == StructuralRelaxation.SET_CHILDREN) hasSetChildrenMember = true
            }
            if (hasSetChildrenMember) {
                components.members.forEachMember(componentId) { objectId ->
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
            if (edgeKey.sourceShapeId !in nonExemptShapes && !noRelaxationRules) continue
            if (edgeKey !in externalEmitted) {
                violations += "edge #${edgeKey.sourceShapeId} -> #${edgeKey.targetShapeId}: " +
                    "true $actual missing from artifact"
            }
        }
        return violations.take(20) + if (violations.size > 20) {
            listOf("... and ${violations.size - 20} more")
        } else {
            emptyList()
        }
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
