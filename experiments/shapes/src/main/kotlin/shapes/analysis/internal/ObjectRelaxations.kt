package shapes.analysis.internal

import shapes.Log
import shapes.graph.AnalysisGraph
import shapes.relaxation.ShapeRelaxationPolicy
import shapes.relaxation.ShapeRelaxationRules
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
internal class ObjectRelaxations internal constructor(
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
}

/**
 * Resolves class rules against the graph's class table and runs the
 * observability sweep over the condensation DAG. Component ids are in reverse
 * topological order (children collapse before parents), so one descending
 * pass visits every component after all of its referrers: seed the scope
 * roots, then push observability through members whose rule does not conceal
 * children. Within an SCC the internal wiring is never concealed, so
 * observability is a per-component fact.
 *
 * With no rules or no known roots the cascade is skipped entirely.
 */
internal class RelaxationResolver(
    private val graph: AnalysisGraph,
    private val components: StronglyConnectedComponents,
    private val rules: ShapeRelaxationRules,
    private val log: Log,
) {
    operator fun invoke(): ObjectRelaxations {
        val policy = ShapeRelaxationPolicy(rules, graph.classNames) { className ->
            log("relax: WARNING rule for $className matches no class in this graph")
        }
        val rootIds = graph.roots
        if (policy.isEmpty || rootIds.isEmpty()) {
            return ObjectRelaxations(graph, policy, null, null, 0L)
        }

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
