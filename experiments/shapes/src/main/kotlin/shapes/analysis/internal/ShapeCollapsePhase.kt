package shapes.analysis.internal

import shapes.analysis.ShapeKind
import shapes.graph.AnalysisGraph
import shapes.relaxation.ShapeRelaxationPolicy
import shapes.relaxation.ShapeRelaxationStats
import shapes.relaxation.StructuralRelaxation

internal class ShapeCollapseResult(
    val shapes: List<CanonicalShape>,
    val shapeOfComponent: IntArray,
    /** Internal shape ids of folded leaf classes, by leaf ordinal. */
    val leafShapeInternalIds: IntArray,
)

/** Bottom-up structural collapse over the condensation DAG. */
internal class ShapeCollapsePhase(
    private val graph: AnalysisGraph,
    private val relaxationPolicy: ShapeRelaxationPolicy,
    private val relaxationStats: ShapeRelaxationStats,
    private val progress: (String) -> Unit,
) {
    fun collapse(components: StronglyConnectedComponents): ShapeCollapseResult {
        val shapes = ArrayList<CanonicalShape>()
        val shapeOfComponent = IntArray(components.members.count) { -1 }
        val acyclicInterner = ShapeInterner(progress)
        val cyclicInterner = HashMap<SccStructuralKey, CyclicShape>()

        // Folded leaf classes become LEAF shapes up front, through the same
        // interner, so class-only relaxed objects merge with them exactly.
        val leafShapeInternalIds = IntArray(graph.leafShapes.size) { leafOrdinal ->
            val classIndex = graph.leafShapes[leafOrdinal].classIndex
            acyclicInterner.intern(
                shapes,
                classIndex,
                graph.classNames[classIndex],
                ShapeKind.LEAF,
                EMPTY_EDGES,
                0,
            ).internalId
        }
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
            leafShapeInternalIds,
        )

        var childShapeIds = IntArray(64)
        var packedChildEdges = LongArray(64)

        for (componentId in 0 until components.members.count) {
            val memberCount = components.members.size(componentId)
            var hasSelfLoop = false
            if (memberCount == 1) {
                val objectId = components.members.singletonMember(componentId)
                for (referenceIndex in 0 until graph.referenceCount(objectId)) {
                    if (graph.referenceAt(objectId, referenceIndex) == objectId) {
                        hasSelfLoop = true
                        break
                    }
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
                    for (referenceIndex in 0 until referenceCount) {
                        val targetId = graph.referenceAt(objectId, referenceIndex)
                        val childShapeId = if (targetId < 0) {
                            leafShapeInternalIds[-targetId - 1]
                        } else {
                            val targetComponent = components.assignment.componentOf(targetId)
                            shapeOfComponent[targetComponent]
                        }
                        check(childShapeId >= 0) {
                            "child of $objectId collapsed after parent — " +
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
            } else if (memberCount > LAZY_WL_THRESHOLD) {
                cyclicCollapser.collapseLazy(componentId, memberCount, progress)
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
        return ShapeCollapseResult(shapes, shapeOfComponent, leafShapeInternalIds)
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

    private companion object {
        val EMPTY_EDGES = LongArray(0)

        /**
         * SCCs above this member count take the lazy-WL path: an invariant
         * signature decides interning, and WL refinement runs only when two
         * components collide on it.
         */
        const val LAZY_WL_THRESHOLD = 1024
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
