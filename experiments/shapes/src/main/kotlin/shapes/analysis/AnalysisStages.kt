package shapes.analysis

import shapes.Log
import shapes.analysis.internal.CanonicalShapes
import shapes.analysis.internal.ComponentFinder
import shapes.analysis.internal.ObjectRelaxations
import shapes.analysis.internal.RelaxationResolver
import shapes.analysis.internal.ShapeAggregator
import shapes.analysis.internal.ShapeCollapser
import shapes.analysis.internal.StronglyConnectedComponents
import shapes.graph.AnalysisGraph
import shapes.phase
import shapes.relaxation.ShapeRelaxationRules

/**
 * Shape analysis is a dataflow over one fixed graph: every stage takes the
 * graph and the facts it needs, and returns one new fact.
 *
 *     val components  = graph.findComponents(log)
 *     val relaxations = graph.relax(components, rules, log)
 *     val shapes      = graph.collapse(components, relaxations, log)
 *     val result      = graph.aggregate(components, shapes, log)
 */

/** Strongly connected components in reverse topological order. */
internal fun AnalysisGraph.findComponents(log: Log): StronglyConnectedComponents =
    log.phase("scc", { "${it.members.count} components" }) { ComponentFinder(this)() }

/** Per-object effective relaxation: class rules plus the observability cascade. */
internal fun AnalysisGraph.relax(
    components: StronglyConnectedComponents,
    rules: ShapeRelaxationRules,
    log: Log,
): ObjectRelaxations = log.phase(
    "relax",
    { "${it.unobservableObjects} objects below concealed edges relaxed to class rows" },
) { RelaxationResolver(this, components, rules, log)() }

/** Canonical shapes interned bottom-up over the condensation DAG. */
internal fun AnalysisGraph.collapse(
    components: StronglyConnectedComponents,
    relaxations: ObjectRelaxations,
    log: Log,
): CanonicalShapes = log.phase("cons", { "${it.shapes.size} shapes" }) {
    ShapeCollapser(this, components, relaxations, log)()
}

/** Per-shape aggregates, public ids by digest order, edge table and roots histogram. */
internal fun AnalysisGraph.aggregate(
    components: StronglyConnectedComponents,
    shapes: CanonicalShapes,
    log: Log,
): ShapeAnalysisResult = log.phase("aggregate") { ShapeAggregator(this, components, shapes)() }
