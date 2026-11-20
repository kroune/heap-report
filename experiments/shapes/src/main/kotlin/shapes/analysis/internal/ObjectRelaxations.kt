package shapes.analysis.internal

import shapes.graph.AnalysisGraph
import shapes.relaxation.ShapeRelaxationPolicy
import shapes.relaxation.StructuralRelaxation
import java.util.BitSet

/**
 * Per-object effective relaxation: the per-class policy plus the
 * observability cascade.
 *
 * A class-only rule conceals an object's children from its key, but the
 * children keep their own rows. When EVERY path from the scope roots to an
 * object crosses a concealing edge, no surviving key distinguishes by that
 * object's identity — the fine splits of such "ghost" rows are unobservable.
 * The cascade relaxes those objects to class rows as well. Population and
 * byte totals remain exact; the extra concealed edges are counted by
 * ShapeRelaxationStats exactly like rule-driven class-only edges.
 */
internal class ObjectRelaxations private constructor(
    private val graph: AnalysisGraph,
    private val policy: ShapeRelaxationPolicy,
    private val components: StronglyConnectedComponents?,
    private val observableComponents: BitSet?,
    /** Objects relaxed by the cascade (identity unobservable from the roots). */
    val unobservableObjects: Long,
) {
    val isEmpty: Boolean
        get() = policy.isEmpty

    fun relaxationOf(objectId: Int): StructuralRelaxation {
        val observable = observableComponents
        if (observable != null &&
            !observable.get(components!!.assignment.componentOf(objectId))
        ) {
            return StructuralRelaxation.CLASS_ONLY
        }
        return policy.relaxationOf(graph.classIndex(objectId))
    }

    companion object {
        fun withoutCascade(
            graph: AnalysisGraph,
            policy: ShapeRelaxationPolicy,
        ): ObjectRelaxations = ObjectRelaxations(graph, policy, null, null, 0L)

        /**
         * Observability sweep over the condensation DAG. Component ids are in
         * reverse topological order (children collapse before parents), so one
         * descending pass visits every component after all of its referrers:
         * seed the scope roots, then push observability through members whose
         * rule does not conceal children. Within an SCC the internal wiring is
         * never concealed, so observability is a per-component fact.
         *
         * With no rules or no known roots the cascade is skipped entirely.
         */
        fun compute(
            graph: AnalysisGraph,
            policy: ShapeRelaxationPolicy,
            components: StronglyConnectedComponents,
            rootIds: IntArray,
        ): ObjectRelaxations {
            if (policy.isEmpty || rootIds.isEmpty()) return withoutCascade(graph, policy)

            val componentCount = components.members.count
            val observable = BitSet(componentCount)
            for (rootId in rootIds) {
                observable.set(components.assignment.componentOf(rootId))
            }
            var unobservable = 0L
            for (componentId in componentCount - 1 downTo 0) {
                if (!observable.get(componentId)) {
                    unobservable += components.members.size(componentId)
                    continue
                }
                components.members.forEachMember(componentId) { objectId ->
                    if (policy.relaxationOf(graph.classIndex(objectId)) !=
                        StructuralRelaxation.CLASS_ONLY
                    ) {
                        for (referenceIndex in 0 until graph.referenceCount(objectId)) {
                            val targetId = graph.referenceAt(objectId, referenceIndex)
                            if (targetId >= 0) {
                                observable.set(components.assignment.componentOf(targetId))
                            }
                        }
                    }
                }
            }
            return ObjectRelaxations(graph, policy, components, observable, unobservable)
        }
    }
}
