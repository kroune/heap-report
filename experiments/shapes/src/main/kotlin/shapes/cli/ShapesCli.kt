package shapes.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.core.subcommands
import shapes.VerificationFailed
import shapes.relaxation.RelaxationRules
import shapes.relaxation.RelaxationRulesTsvParser
import java.nio.file.Files
import java.nio.file.Path

internal abstract class ShapesCommand(
    name: String,
    private val helpText: String,
) : CliktCommand(name) {
    override fun help(context: Context): String = helpText

    /** Runs a driver; verification violations become exit code 1. */
    protected fun drive(driver: () -> Unit) {
        try {
            driver()
        } catch (failure: VerificationFailed) {
            throw ProgramResult(1)
        }
    }
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

/** Loads a relaxation TSV; no file means no rules. */
internal fun loadRelaxationRules(path: Path?, log: (String) -> Unit): RelaxationRules {
    if (path == null) return RelaxationRules()
    val rules = RelaxationRulesTsvParser.parse(Files.readString(path))
    log(
        "relax: ${rules.shapeRules.byClass.size} analyzer rules, " +
            "${rules.shapeRules.byPattern.size} regex rules, " +
            "${rules.fieldConcealmentRules.fieldsByClass.size} field rules from $path"
    )
    return rules
}
