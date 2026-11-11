package shapes.cli

import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.int
import shapes.analysis.AnalysisScope
import shapes.graph.ClassName
import shapes.relaxation.ShapeRelaxationRules
import shapes.synthetic.SyntheticObjectGraphs

internal class AnalyzeSyntheticGraphCommand : ShapesCommand(
    "demo",
    "Generate and analyze a synthetic graph in memory",
) {
    private val moduleCount by option("--modules", help = "Number of synthetic modules")
        .int()
        .required()
    private val verifyInvariants by option("--verify", help = "Verify analyzer invariants").flag()

    override fun run() {
        val (graph, roots) = SyntheticObjectGraphs.modules(moduleCount)
        println("synthetic heap: ${graph.size} objects, ${roots.size} module roots")
        GraphAnalysisCliWorkflow().run(
            GraphAnalysisRequest(
                graph,
                AnalysisScope.ReachableFrom(roots),
                ClassName("synth.Module"),
                AnalysisCliOptions(verifyInvariants = verifyInvariants),
                ShapeRelaxationRules(),
            )
        )
    }
}
