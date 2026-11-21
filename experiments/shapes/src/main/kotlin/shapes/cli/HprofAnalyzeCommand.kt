package shapes.cli

import com.github.ajalt.clikt.parameters.groups.mutuallyExclusiveOptions
import com.github.ajalt.clikt.parameters.groups.required
import com.github.ajalt.clikt.parameters.options.convert
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.path
import shapes.HprofAnalyzeConfig
import shapes.hprof.ProjectionRules
import shapes.hprof.Selection
import shapes.runHprofAnalyze

private sealed interface HprofRootChoice {
    data class RootClass(val className: String) : HprofRootChoice
    data object WholeHeap : HprofRootChoice
}

/** Direct HPROF extraction + shape analysis in one JVM. */
internal class HprofAnalyzeCommand : ShapesCommand(
    "hprof-analyze",
    "Extract and analyze a heap dump straight from HPROF in one JVM",
) {
    private val hprof by option("--hprof", help = "Heap dump file")
        .path(mustExist = true, canBeDir = false)
        .required()

    private val rootChoice: HprofRootChoice by mutuallyExclusiveOptions(
        option("--class", help = "Class whose live instances seed a reachable scope")
            .convert { HprofRootChoice.RootClass(it) },
        option("--whole-heap", help = "Analyze every live non-class object")
            .flag()
            .convert { HprofRootChoice.WholeHeap },
    ).required()

    private val noFold by option(
        "--no-fold",
        help = "Keep leaf objects as graph nodes (verification mode)",
    ).flag()

    private val rawReferences by option(
        "--raw-references",
        help = "Keep soft/weak/phantom-reference edges",
    ).flag()

    private val relaxationRules by option(
        "--relax",
        help = "Class relaxation rules (class-only|set-children)",
    ).path(mustExist = true, canBeDir = false)

    private val outputArtifact by option("--out", help = "Write the SHP1 artifact to this file").path()

    private val stats by option("--stats", help = "Write extraction statistics as JSON").path()

    private val relaxationStats by option(
        "--relax-stats",
        help = "Write relaxation statistics as JSON",
    ).path()

    private val edgeHistogram by option(
        "--edge-histogram",
        help = "Write per-class instance/out-edge counts as TSV",
    ).path()

    private val verifyInvariants by option("--verify", help = "Verify analyzer invariants").flag()

    private val verifyEdgeAccounting by option(
        "--verify-edges",
        help = "Verify exact edge accounting",
    ).flag()

    // Investigation modes, parked until per-stage persistence lets them run as
    // drivers over a cached component graph (see SccClassMatrixReport.kt):
    //
    // private val sccReport by option("--scc-report", help = "Write a class-matrix report for the largest SCCs and exit").path()
    // private val printFields by option("--print-fields", help = "Print reference-field layouts for these comma-separated classes and exit")
    // private val sccProbes by option("--scc-probe", help = "Recompute SCCs with class->class edges dropped (srcRegex->dstRegex, comma list); repeatable").multiple()
    // private val sccProbeSplit by option("--scc-probe-split", help = "Extra drop rules that shatter the giant probe component; reports the crossing edges")
    // private val sccProbeAnchor by option("--scc-probe-anchor", help = "Class regex of anchor instances; attributes the giant probe component per anchor")

    override fun run() {
        val rules = loadRelaxationRules(relaxationRules, ::println)
        val config = HprofAnalyzeConfig(
            hprof = hprof,
            projection = ProjectionRules(
                concealment = rules.fieldConcealmentRules,
                filterReferenceMechanics = !rawReferences,
            ),
            selection = when (val choice = rootChoice) {
                is HprofRootChoice.RootClass -> Selection.ClassClosure(choice.className)
                HprofRootChoice.WholeHeap -> Selection.WholeHeap(foldLeaves = !noFold)
            },
            shapeRules = rules.shapeRules,
            artifact = outputArtifact,
            extractionStats = stats,
            relaxationStats = relaxationStats,
            edgeHistogram = edgeHistogram,
            verifyInvariants = verifyInvariants,
            verifyEdges = verifyEdgeAccounting,
        )
        drive { runHprofAnalyze(config, ::println) }
    }
}
