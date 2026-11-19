package shapes.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.path
import java.nio.file.Path

internal abstract class ShapesCommand(
    name: String,
    private val helpText: String,
) : CliktCommand(name) {
    override fun help(context: Context): String = helpText
}

internal data class AnalysisCliOptions(
    val outputArtifact: Path? = null,
    val verifyInvariants: Boolean = false,
    val verifyEdgeAccounting: Boolean = false,
    val relaxationRules: Path? = null,
    val relaxationStats: Path? = null,
)

internal abstract class AnalysisCommand(name: String, help: String) : ShapesCommand(name, help) {
    private val outputArtifact by option("--out", help = "Write the SHP1 artifact to this file").path()
    private val verifyInvariants by option("--verify", help = "Verify analyzer invariants").flag()
    private val verifyEdgeAccounting by option(
        "--verify-edges",
        help = "Verify exact edge accounting",
    ).flag()
    private val relaxationRules by option(
        "--relax",
        help = "Class relaxation rules (class-only|set-children)",
    ).path(mustExist = true, canBeDir = false)
    private val relaxationStats by option(
        "--relax-stats",
        help = "Write relaxation statistics as JSON",
    ).path()

    protected val analysisOptions: AnalysisCliOptions
        get() = AnalysisCliOptions(
            outputArtifact,
            verifyInvariants,
            verifyEdgeAccounting,
            relaxationRules,
            relaxationStats,
        )
}

internal class ShapesCli : ShapesCommand(
    "shapes",
    "Exact structural collapse (see backend/mat/SHAPES.md)",
) {
    override val printHelpOnEmptyArgs: Boolean = true

    init {
        subcommands(
            HprofAnalyzeCommand(),
            HprofScanCommand(),
            AnalyzeSyntheticGraphCommand(),
        )
    }

    override fun run() = Unit
}
