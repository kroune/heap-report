package shapes.analysis.internal

import shapes.Log
import shapes.analysis.ShapeKind
import shapes.graph.AnalysisGraph
import shapes.relaxation.ShapeRelaxationStats
import shapes.relaxation.StructuralRelaxation

/**
 * The interned canonical shapes of one analysis: the shape table, the shape
 * of every component, the internal ids of folded leaf classes, and the
 * exact accounting of what relaxation concealed while interning.
 */
internal class CanonicalShapes(
    val shapes: List<CanonicalShape>,
    val shapeOfComponent: IntArray,
    /** Internal shape ids of folded leaf classes, by leaf ordinal. */
    val leafShapeInternalIds: IntArray,
    val relaxationStats: ShapeRelaxationStats,
)

/**
 * Bottom-up structural collapse over the condensation DAG: one sweep in
 * component order (reverse topological), where every component is interned
 * once all of its children are. Singletons go through the acyclic interner,
 * small cycles through WL refinement and full-key interning, large cycles
 * through the lazy signature path.
 */
internal class ShapeCollapser(
    private val graph: AnalysisGraph,
    private val components: StronglyConnectedComponents,
    private val relaxations: ObjectRelaxations,
    private val log: Log,
) {
    operator fun invoke(): CanonicalShapes {
        val relaxationStats = ShapeRelaxationStats()
        val progress = log
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
            relaxations,
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
            val shape = if (memberCount == 1) {
                val objectId = components.members.singletonMember(componentId)
                val relaxation = relaxations.relaxationOf(objectId)
                val referenceCount = graph.referenceCount(objectId)
                var hasSelfLoop = false
                var childCount = 0
                for (referenceIndex in 0 until referenceCount) {
                    val targetId = graph.referenceAt(objectId, referenceIndex)
                    if (targetId == objectId) {
                        hasSelfLoop = true
                        break
                    }
                    if (relaxation != StructuralRelaxation.CLASS_ONLY) {
                        if (childCount == childShapeIds.size) {
                            childShapeIds = childShapeIds.copyOf(maxOf(referenceCount, childShapeIds.size * 2))
                            packedChildEdges = LongArray(childShapeIds.size)
                        }
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
                }

                if (hasSelfLoop) {
                    cyclicCollapser.collapse(intArrayOf(objectId))
                } else {
                    if (relaxation != StructuralRelaxation.EXACT) relaxationStats.relaxedObjects++
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
        return CanonicalShapes(shapes, shapeOfComponent, leafShapeInternalIds, relaxationStats)
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

        it.unimi.dsi.fastutil.ints.IntArrays.quickSort(childIds, 0, childCount) { first, second ->
            compareChildShapeIds(shapes, first, second)
        }
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
