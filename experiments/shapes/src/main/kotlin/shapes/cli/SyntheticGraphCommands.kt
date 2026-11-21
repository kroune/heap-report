package shapes.cli

import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.int
import shapes.DemoConfig
import shapes.runDemo

internal class AnalyzeSyntheticGraphCommand : ShapesCommand(
    "demo",
    "Generate and analyze a synthetic graph in memory",
) {
    private val moduleCount by option("--modules", help = "Number of synthetic modules")
        .int()
        .required()
    private val verifyInvariants by option("--verify", help = "Verify analyzer invariants").flag()

    override fun run() {
        drive { runDemo(DemoConfig(moduleCount, verifyInvariants), ::println) }
    }
}
