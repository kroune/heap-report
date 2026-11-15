package shapes.analysis.internal

import shapes.analysis.ShapeKind
import shapes.graph.ObjectGraph
import shapes.relaxation.ShapeRelaxationPolicy
import shapes.relaxation.ShapeRelaxationStats
import shapes.relaxation.StructuralRelaxation

internal class ShapeCollapseResult(
    val shapes: List<CanonicalShape>,
    val shapeOfComponent: IntArray,
)

/** Bottom-up structural collapse over the condensation DAG. */
internal class ShapeCollapsePhase(
    private val graph: ObjectGraph,
    private val relaxationPolicy: ShapeRelaxationPolicy,
    private val relaxationStats: ShapeRelaxationStats,
    private val progress: (String) -> Unit,
) {
    fun collapse(components: StronglyConnectedComponents): ShapeCollapseResult {
        val shapes = ArrayList<CanonicalShape>()
        val shapeOfComponent = IntArray(components.members.count) { -1 }
        val acyclicInterner = ShapeInterner(progress)
        val cyclicInterner = HashMap<SccStructuralKey, CyclicShape>()
        val edgeComparator = Comparator<ChildShapeReference> { first, second ->
            val childComparison = compareChildShapeIds(
                shapes,
                first.childShapeId,
                second.childShapeId,
            )
            if (childComparison != 0) childComparison
            else first.multiplicity.compareTo(second.multiplicity)
        }
        val cyclicCollapser = CyclicShapeCollapser(
            graph,
            relaxationPolicy,
            relaxationStats,
            shapes,
            shapeOfComponent,
            components,
            cyclicInterner,
            edgeComparator,
        )

        var childShapeIds = IntArray(64)
        var packedChildEdges = LongArray(64)

        for (componentId in 0 until components.members.count) {
            val memberCount = components.members.size(componentId)
            var hasSelfLoop = false
            if (memberCount == 1) {
                val objectId = components.members.singletonMember(componentId)
                graph.forEachReference(objectId) { targetId ->
                    if (targetId == objectId) hasSelfLoop = true
                }
            }

            val shape = if (memberCount == 1 && !hasSelfLoop) {
                val objectId = components.members.singletonMember(componentId)
                val relaxation = relaxationPolicy.relaxationOf(graph.classIndex(objectId))
                if (relaxation != StructuralRelaxation.EXACT) relaxationStats.relaxedObjects++

                val referenceCount = graph.referenceCount(objectId)
                if (referenceCount == 0 || relaxation == StructuralRelaxation.CLASS_ONLY) {
                    if (relaxation == StructuralRelaxation.CLASS_ONLY) {
                        relaxationStats.classOnlyHiddenEdges += referenceCount
                    }
                    acyclicInterner.intern(
                        shapes,
                        graph.classIndex(objectId),
                        graph.className(objectId),
                        ShapeKind.LEAF,
                        packedChildEdges,
                        0,
                    )
                } else {
                    if (referenceCount > childShapeIds.size) {
                        childShapeIds = IntArray(maxOf(referenceCount, childShapeIds.size * 2))
                        packedChildEdges = LongArray(childShapeIds.size)
                    }
                    var childCount = 0
                    graph.forEachReference(objectId) { targetId ->
                        val targetComponent = components.assignment.componentOf(targetId)
                        val childShapeId = shapeOfComponent[targetComponent]
                        check(childShapeId >= 0) {
                            "child component $targetComponent collapsed after parent — " +
                                "topological order violated"
                        }
                        childShapeIds[childCount++] = childShapeId
                    }

                    sortChildShapeIds(shapes, childShapeIds, childCount)
                    val setChildren = relaxation == StructuralRelaxation.SET_CHILDREN
                    val packedCount = packChildEdges(
                        childShapeIds,
                        childCount,
                        packedChildEdges,
                        setChildren,
                    )
                    if (setChildren) {
                        relaxationStats.setChildrenHiddenEdges += childCount - packedCount
                    }
                    acyclicInterner.intern(
                        shapes,
                        graph.classIndex(objectId),
                        graph.className(objectId),
                        ShapeKind.NODE,
                        packedChildEdges,
                        packedCount,
                    )
                }
            } else {
                val memberIds = IntArray(memberCount)
                var memberIndex = 0
                components.members.forEachMember(componentId) { objectId ->
                    memberIds[memberIndex++] = objectId
                }
                cyclicCollapser.collapse(memberIds)
            }
            shapeOfComponent[componentId] = shape.internalId

            if (componentId and 0x7FFFFF == 0x7FFFFF) {
                val runtime = Runtime.getRuntime()
                progress(
                    "cons: ${componentId + 1}/${components.members.count} comps, " +
                        "${shapes.size} shapes, " +
                        "${(runtime.totalMemory() - runtime.freeMemory()) / 1_000_000} MB used, " +
                        "maxProbe=${acyclicInterner.maxProbe}"
                )
            }
        }
        return ShapeCollapseResult(shapes, shapeOfComponent)
    }

    private fun compareChildShapeIds(
        shapes: List<CanonicalShape>,
        firstId: Int,
        secondId: Int,
    ): Int {
        val digestComparison = shapes[firstId].digest.compareTo(shapes[secondId].digest)
        return if (digestComparison != 0) digestComparison else firstId.compareTo(secondId)
    }

    private fun sortChildShapeIds(
        shapes: List<CanonicalShape>,
        childIds: IntArray,
        childCount: Int,
    ) {
        if (childCount <= 64) {
            for (index in 1 until childCount) {
                val childId = childIds[index]
                var insertionIndex = index - 1
                while (insertionIndex >= 0 &&
                    compareChildShapeIds(shapes, childIds[insertionIndex], childId) > 0
                ) {
                    childIds[insertionIndex + 1] = childIds[insertionIndex]
                    insertionIndex--
                }
                childIds[insertionIndex + 1] = childId
            }
            return
        }

        val sorted = childIds.copyOf(childCount).sortedWith { first, second ->
            compareChildShapeIds(shapes, first, second)
        }
        sorted.forEachIndexed { index, childId -> childIds[index] = childId }
    }

    private fun packChildEdges(
        childIds: IntArray,
        childCount: Int,
        packedEdges: LongArray,
        setChildren: Boolean,
    ): Int {
        var packedCount = 0
        var previousChildId = -1
        var multiplicity = 0
        for (index in 0 until childCount) {
            val childId = childIds[index]
            if (childId == previousChildId) {
                multiplicity++
            } else {
                if (previousChildId >= 0) {
                    packedEdges[packedCount++] = ChildShapeEdge(
                        previousChildId,
                        if (setChildren) 1 else multiplicity,
                    ).packed
                }
                previousChildId = childId
                multiplicity = 1
            }
        }
        if (previousChildId >= 0) {
            packedEdges[packedCount++] = ChildShapeEdge(
                previousChildId,
                if (setChildren) 1 else multiplicity,
            ).packed
        }
        return packedCount
    }
}
