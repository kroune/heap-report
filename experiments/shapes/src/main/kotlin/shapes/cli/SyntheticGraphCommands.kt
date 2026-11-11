package shapes.cli

import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.int
import com.github.ajalt.clikt.parameters.types.path
import shapes.analysis.AnalysisScope
import shapes.graph.ClassName
import shapes.input.graph.TextObjectGraphWriter
import shapes.relaxation.ShapeRelaxationRules
import shapes.synthetic.SyntheticObjectGraphs

internal class GenerateSyntheticGraphCommand : ShapesCommand(
    "generate",
    "Generate a synthetic text graph",
) {
    private val moduleCount by option("--modules", help = "Number of synthetic modules")
        .int()
        .required()
    private val outputPath by option("--out", help = "Output text graph").path().required()

    override fun run() {
        val (graph, _) = SyntheticObjectGraphs.modules(moduleCount)
        TextObjectGraphWriter(outputPath).write(graph)
        println("graph written: $outputPath (${graph.size} objects)")
    }
}

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
