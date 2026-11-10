package shapes

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.groups.mutuallyExclusiveOptions
import com.github.ajalt.clikt.parameters.groups.required
import com.github.ajalt.clikt.parameters.options.convert
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.choice
import com.github.ajalt.clikt.parameters.types.int
import com.github.ajalt.clikt.parameters.types.path
import shapes.extraction.ExtractionMode
import shapes.extraction.GraphExtractor
import shapes.input.GraphInput
import shapes.input.TextGraphInput
import shapes.input.TextGraphOutput
import shapes.mat.MatDiagnostics
import shapes.mat.MatHeapInput
import shapes.models.ClassName
import java.nio.file.Files
import java.nio.file.Path

private abstract class ShapesCommand(
    name: String,
    private val helpText: String,
) : CliktCommand(name) {
    override fun help(context: Context): String = helpText
}

private data class AnalysisOptions(
    val out: Path?,
    val verify: Boolean,
    val verifyEdges: Boolean,
    val relax: Path?,
    val relaxStats: Path?,
)

private abstract class AnalysisCommand(name: String, help: String) : ShapesCommand(name, help) {
    private val out by option("--out", help = "Write the SHP1 artifact to this file").path()
    private val verify by option("--verify", help = "Verify analyzer invariants").flag()
    private val verifyEdges by option("--verify-edges", help = "Verify exact edge accounting").flag()
    private val relax by option("--relax", help = "Class relaxation rules (class-only|set-children)")
        .path(mustExist = true, canBeDir = false)
    private val relaxStats by option("--relax-stats", help = "Write relaxation statistics as JSON").path()

    protected val analysisOptions: AnalysisOptions
        get() = AnalysisOptions(out, verify, verifyEdges, relax, relaxStats)
}

private sealed interface AnalysisScope {
    data class ClassScope(val className: ClassName) : AnalysisScope
    data object WholeHeap : AnalysisScope
}

private fun classScope(value: String): AnalysisScope = AnalysisScope.ClassScope(ClassName(value))

private class ShapesCli : ShapesCommand(
    "shapes",
    "Exact structural collapse (see backend/mat/SHAPES.md)",
) {
    override val printHelpOnEmptyArgs: Boolean = true

    override fun helpEpilog(context: Context): String =
        "Text graph format: '<id> <class> <shallowBytes> [refId,refId,...]' per line; '#' starts a comment."

    init {
        subcommands(
            Analyze(), AnalyzeGrph(), Generate(), Demo(),
            MatInfo(), MatHistogram(), MatAnalyze(), MatTrace(), MatFields(),
            MatRefTargets(), MatIsClass(), MatReachBack(),
        )
    }

    override fun run() = Unit
}

private class Analyze : AnalysisCommand(
    "analyze",
    "Analyze a text graph rooted at all instances of a class",
) {
    private val graph by option("--graph", help = "Input text graph")
        .path(mustExist = true, canBeDir = false).required()
    private val className by option("--class", help = "Root class name").required()

    override fun run() {
        val input = TextGraphInput(graph).read()
        val rules = rulesOf(analysisOptions.relax)
        warnIgnoredFieldRules(rules)
        analyzeGraph(
            graph = input,
            options = analysisOptions,
            className = ClassName(className),
            wholeHeap = false,
            wholeGraph = false,
            classRules = rules?.classRules.orEmpty(),
        )
    }
}

private class AnalyzeGrph : AnalysisCommand(
    "analyze-grph",
    "Analyze a serialized GRPH graph",
) {
    private val graph by option("--graph", help = "Input GRPH graph")
        .path(mustExist = true, canBeDir = false).required()
    private val scope: AnalysisScope by mutuallyExclusiveOptions(
        option("--class", help = "Root class name").convert { classScope(it) },
        option("--whole-heap", help = "Analyze the whole graph without class roots")
            .flag().convert { AnalysisScope.WholeHeap },
    ).required()
    private val wholeGraph by option("--whole-graph", help = "Treat the complete graph as reachable").flag()

    override fun run() {
        val (className, wholeHeap) = when (val selected = scope) {
            is AnalysisScope.ClassScope -> selected.className to false
            AnalysisScope.WholeHeap -> null to true
        }
        if (wholeHeap && !wholeGraph) throw UsageError("--whole-heap requires --whole-graph")
        val input = GraphInput(graph).read()
        val rules = rulesOf(analysisOptions.relax)
        warnIgnoredFieldRules(rules)
        analyzeGraph(
            graph = input,
            options = analysisOptions,
            className = className,
            wholeHeap = wholeHeap,
            wholeGraph = wholeGraph,
            classRules = rules?.classRules.orEmpty(),
        )
    }
}

private class Generate : ShapesCommand("generate", "Generate a synthetic text graph") {
    private val modules by option("--modules", help = "Number of synthetic modules").int().required()
    private val out by option("--out", help = "Output text graph").path().required()

    override fun run() {
        val (graph, _) = SynthGraphs.modules(modules)
        TextGraphOutput.write(graph, out)
        println("graph written: $out (${graph.size} objects)")
    }
}

private class Demo : ShapesCommand("demo", "Generate and analyze a synthetic graph in memory") {
    private val modules by option("--modules", help = "Number of synthetic modules").int().required()
    private val verify by option("--verify", help = "Verify analyzer invariants").flag()

    override fun run() {
        val (graph, roots) = SynthGraphs.modules(modules)
        println("synthetic heap: ${graph.size} objects, ${roots.size} module roots")
        val table = ShapesAnalyzer(graph).analyze(roots)
        printReport(table)
        if (verify) runVerify(graph, roots, table)
    }
}

private abstract class MatDiagnosticCommand(name: String, help: String) : ShapesCommand(name, help) {
    protected val hprof by option("--hprof", help = "Heap dump with pre-built MAT indexes")
        .path(mustExist = true, canBeDir = false).required()
}

private class MatInfo : MatDiagnosticCommand("mat-info", "Print heap snapshot metadata") {
    override fun run() = MatDiagnostics.info(hprof.toString())
}

private class MatHistogram : MatDiagnosticCommand("mat-histogram", "Print the heap class histogram") {
    private val top by option("--top", help = "Maximum number of classes to print").int().default(30)

    override fun run() = MatDiagnostics.histogram(hprof.toString(), top)
}

private class MatAnalyze : AnalysisCommand(
    "mat-analyze",
    "Extract and analyze a MAT heap snapshot in one JVM, without a GRPH round-trip",
) {
    private val hprof by option("--hprof", help = "Heap dump with pre-built MAT indexes")
        .path(mustExist = true, canBeDir = false).required()
    private val scope: AnalysisScope by mutuallyExclusiveOptions(
        option("--class", help = "Class whose instances define the extraction scope").convert { classScope(it) },
        option("--whole-heap", help = "Extract and analyze the whole heap")
            .flag().convert { AnalysisScope.WholeHeap },
    ).required()
    private val extractionScope by option(
        "--scope",
        help = "retained/reachable with --class; heap (or omitted) with --whole-heap",
    ).choice("retained", "reachable", "heap")
    private val stats by option("--stats", help = "Write extraction statistics as JSON").path()
    private val boundaryStubs by option("--boundary-stubs", help = "Retain stubs at extraction boundaries").flag()
    private val rawReferences by option(
        "--raw-references",
        help = "Keep soft/weak/phantom-reference edges",
    ).flag()

    override fun run() {
        val mode = extractionMode()
        val rules = rulesOf(analysisOptions.relax)
        val result = MatHeapInput.open(hprof.toString()).use { input ->
            GraphExtractor(
                input = input,
                filterReferences = !rawReferences,
                fieldRules = rules?.fieldRules.orEmpty(),
                boundaryStubs = boundaryStubs,
                progress = ::println,
            ).extract(mode)
        }
        stats?.let {
            writeJson(it, result.stats)
            println("extraction stats written: $it")
        }
        val className = (mode as? ExtractionMode.PartialExtraction)?.className
        analyzeGraph(
            graph = result.graph,
            options = analysisOptions,
            className = className,
            wholeHeap = mode is ExtractionMode.WholeHeap,
            wholeGraph = true,
            classRules = rules?.classRules.orEmpty(),
        )
    }

    private fun extractionMode(): ExtractionMode = when (val selected = scope) {
        AnalysisScope.WholeHeap -> {
            if (extractionScope != null && extractionScope != "heap") {
                throw UsageError("--whole-heap requires --scope heap")
            }
            ExtractionMode.WholeHeap
        }
        is AnalysisScope.ClassScope -> when (extractionScope) {
            "retained" -> ExtractionMode.PartialExtraction.Retained(selected.className)
            "reachable" -> ExtractionMode.PartialExtraction.Reachable(selected.className)
            else -> throw UsageError("--class requires --scope retained or --scope reachable")
        }
    }
}

private class MatTrace : MatDiagnosticCommand("mat-trace", "Trace selected classes in a heap snapshot") {
    private val className by option("--class", help = "Class to trace").required()
    private val targets by option("--targets", help = "Comma-separated target class names")
        .default("DefaultGradleBuild,DefaultGradle,BuildState,DefaultProject")
    private val maxLevel by option("--maxlevel", help = "Maximum trace depth").int().default(15)
    private val maxObjs by option("--maxobjs", help = "Maximum objects to visit").int().default(80_000_000)

    override fun run() = MatDiagnostics.trace(
        hprof.toString(), className, targets.split(","), maxLevel, maxObjs,
    )
}

private class MatFields : MatDiagnosticCommand("mat-fields", "Print outbound fields for class instances") {
    private val className by option("--class", help = "Class to inspect").required()
    private val limit by option("--limit", help = "Maximum instances to print").int().default(5)
    private val all by option("--all", help = "Include instances without reference targets").flag()

    override fun run() = MatDiagnostics.fields(hprof.toString(), className, limit, !all)
}

private class MatRefTargets : MatDiagnosticCommand(
    "mat-ref-targets",
    "Print a histogram of reference-typed field targets",
) {
    private val className by option("--class", help = "Class to inspect").required()
    private val maxScan by option("--maxscan", help = "Maximum instances to scan").int().default(1_000_000_000)

    override fun run() = MatDiagnostics.refTargets(hprof.toString(), className, maxScan)
}

private class MatIsClass : MatDiagnosticCommand(
    "mat-isclass",
    "Check java.lang.Class instances against the MAT class table",
) {
    private val limit by option("--limit", help = "Maximum instances to scan").int().default(5)

    override fun run() = MatDiagnostics.isClassCheck(hprof.toString(), limit)
}

private class MatReachBack : MatDiagnosticCommand(
    "mat-reachback",
    "Run reverse BFS over the MAT inbound-reference index",
) {
    private val className by option("--class", help = "Class to start from").required()
    private val limit by option("--limit", help = "Maximum seed instances").int().default(3)
    private val maxLevel by option("--maxlevel", help = "Maximum BFS depth").int().default(30)
    private val maxObjs by option("--maxobjs", help = "Maximum objects to visit").int().default(150_000_000)

    override fun run() = MatDiagnostics.reachBack(hprof.toString(), className, limit, maxLevel, maxObjs)
}

fun main(args: Array<String>) = ShapesCli().main(args)

private fun analyzeGraph(
    graph: ObjectGraph,
    options: AnalysisOptions,
    className: ClassName?,
    wholeHeap: Boolean,
    wholeGraph: Boolean,
    classRules: Map<ClassName, Int>,
) {
    require(wholeHeap == (className == null)) { "exactly one of class scope or whole heap is required" }
    val roots = className?.let { graph.instancesOf(it) } ?: IntArray(0)
    println(
        "input: ${graph.size} objects / ${graph.referenceCount} refs (source heap ${graph.sourceObjectCount}), " +
            (className?.let { "class $it: ${roots.size} roots" } ?: "whole heap: no class roots")
    )
    val start = System.nanoTime()
    val analyzer = ShapesAnalyzer(graph, classRules).apply { progress = ::println }
    if (options.verifyEdges) {
        val debug = analyzer.analyzeDebug(roots, wholeGraph)
        println("analyze: ${(System.nanoTime() - start) / 1_000_000} ms")
        options.out?.let {
            ShapesEmit.write(debug.table, it)
            println("artifact written: $it")
        }
        printRelax(analyzer)
        writeRelaxStats(analyzer, options.relaxStats)
        val problems = verifyEdgeAccounting(graph, debug, analyzer.relaxLevels())
        if (problems.isEmpty()) println("verify-edges: edge accounting exact")
        else {
            println("verify-edges: MISMATCHES:")
            problems.forEach { println("  - $it") }
            throw ProgramResult(1)
        }
        return
    }

    val table = analyzer.analyze(roots, wholeGraph)
    println("analyze: ${(System.nanoTime() - start) / 1_000_000} ms")
    printRelax(analyzer)
    writeRelaxStats(analyzer, options.relaxStats)
    // Emit before the potentially huge human-readable report.
    options.out?.let {
        ShapesEmit.write(table, it)
        println("artifact written: $it")
    }
    printReport(table)
    if (options.verify) runVerify(graph, roots, table, wholeGraph)
}

private fun rulesOf(path: Path?): Relax.Rules? = path?.let {
    Relax.parse(Files.readString(it)).also {
        println("relax: ${it.classRules.size} analyzer rules, ${it.fieldRules.size} field rules from $path")
    }
}

private fun warnIgnoredFieldRules(rules: Relax.Rules?) {
    if (!rules?.fieldRules.isNullOrEmpty()) {
        println("relax: field rules require a named-field input and are ignored for serialized/text graphs")
    }
}

private fun printRelax(analyzer: ShapesAnalyzer) {
    val stats = analyzer.relaxStats
    if (stats.relaxedObjects > 0) {
        println(
            "relax: ${stats.relaxedObjects} relaxed objects; concealed edges: " +
                "${stats.classOnlyHiddenEdges} (class-only) + ${stats.setChildrenHiddenEdges} (set-children)"
        )
    }
}

private fun writeRelaxStats(analyzer: ShapesAnalyzer, relaxStats: Path?) {
    relaxStats?.let { path ->
        writeJson(path, analyzer.relaxStats)
        println("relax stats written: $path")
    }
}

private fun runVerify(graph: ObjectGraph, roots: IntArray, table: ShapeTable, wholeGraph: Boolean = false) {
    val problems = verifyInvariants(graph, roots, table, wholeGraph)
    if (problems.isEmpty()) println("verify: all invariants hold")
    else {
        println("verify: VIOLATIONS:")
        problems.forEach { println("  - $it") }
        throw ProgramResult(1)
    }
}

private fun printReport(table: ShapeTable) {
    println("reachable objects : ${table.reachableCount}")
    println("unique shapes     : ${table.entries.size}")
    if (table.entries.isNotEmpty()) {
        println("collapse ratio    : %.1fx".format(table.reachableCount.toDouble() / table.entries.size))
    }
    println("roots histogram   :")
    for ((shapeId, count) in table.rootsHistogram.entries.sortedByDescending { it.value }) {
        println("  %-56s x %d".format(table.entries[shapeId].label, count))
    }
    println("shapes (by shallowTotal):")
    for (entry in table.entries.sortedByDescending { it.shallowTotal }.take(20)) {
        println(
            "  #%-4d %-52s %-9s count=%-7d shallowTotal=%-11s min=%-7s max=%-7s roots=%d".format(
                entry.id, entry.label.take(52), entry.kind, entry.instanceCount, entry.shallowTotal,
                entry.shallowMin, entry.shallowMax, entry.rootCount,
            )
        )
        for (edge in table.edgesOf(entry.id).take(10)) {
            val child = table.entries[edge.childShapeId]
            println(
                "        -> #%-4d %-46s mult=%d%s".format(
                    edge.childShapeId, child.label.take(46), edge.mult,
                    if (entry.edgesPerMember) " (per member)" else " (total)",
                )
            )
        }
        val more = table.edgesOf(entry.id).size - 10
        if (more > 0) println("        -> ... and $more more edges")
    }
}
