package shapes.analysis

import shapes.Log
import shapes.analysis.internal.CanonicalShapes
import shapes.analysis.internal.ObjectRelaxations
import shapes.analysis.internal.StronglyConnectedComponents
import shapes.graph.AnalysisGraph
import shapes.relaxation.ShapeRelaxationRules

/** Every fact the analysis dataflow produced for one graph. */
internal class Analysis(
    val graph: AnalysisGraph,
    val components: StronglyConnectedComponents,
    val relaxations: ObjectRelaxations,
    val shapes: CanonicalShapes,
    val result: ShapeAnalysisResult,
)

/** The analysis dataflow as tests run it; the graph must already be selected. */
internal fun AnalysisGraph.analyzeShapes(
    rules: ShapeRelaxationRules = ShapeRelaxationRules(),
    log: Log = {},
): Analysis {
    val components = findComponents(log)
    val relaxations = relax(components, rules, log)
    val shapes = collapse(components, relaxations, log)
    val result = aggregate(components, shapes, log)
    return Analysis(this, components, relaxations, shapes, result)
}
