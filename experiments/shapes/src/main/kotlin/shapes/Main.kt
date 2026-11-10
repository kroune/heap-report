package shapes

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
import kotlin.system.exitProcess

private const val USAGE = """
shapes — exact structural collapse (see backend/mat/SHAPES.md)

  analyze --graph <file.txt> --class <className> [--out <shapes.bin>] [--verify]
  analyze-grph --graph <file.grph> [--class <className> | --whole-heap]
      [--whole-graph] [--out <shapes.bin>] [--verify]
  generate --modules <N> --out <graph.txt>
  demo --modules <N> [--verify]          generate + analyze in memory

common options:
  --relax <rules.tsv>   class relaxation rules (class-only|set-children)

real heap dumps (MAT library, needs pre-built indexes next to the hprof):
  mat-info --hprof <file.hprof>
  mat-histogram --hprof <file.hprof> [--top 30]
  mat-analyze --hprof <file.hprof> [--class <className> | --whole-heap]
      --scope retained|reachable|heap --out <shapes.bin>
      [--stats <stats.json>] [--relax-stats <relax.json>] [--boundary-stubs]
      [--raw-references]  keep soft/weak/phantom-reference edges
      [--relax <rules.tsv>]  applies both extraction and analyzer rules
      [--verify] [--verify-edges]
      Extraction and analysis run in one JVM without a GRPH round-trip.
  mat-trace --hprof <file.hprof> --class <className> [--targets a,b,c] [--maxlevel 15] [--maxobjs 80M]

graph format: '<id> <class> <shallowBytes> [refId,refId,...]' per line, '#' comments
"""

fun main(args: Array<String>) {
    val opts = parseOpts(args.toList())
    when (opts.cmd) {
        "analyze" -> {
            val graph = TextGraphInput(Path.of(opts.require("graph"))).read()
            val rules = rulesOf(opts)
            warnIgnoredFieldRules(rules)
            analyzeGraph(graph, opts, ClassName(opts.require("class")), wholeHeap = false,
                wholeGraph = false, classRules = rules?.classRules.orEmpty())
        }

        "generate" -> {
            val (graph, _) = SynthGraphs.modules(opts.require("modules").toInt())
            TextGraphOutput.write(graph, Path.of(opts.require("out")))
            println("graph written: ${opts.require("out")} (${graph.size} objects)")
        }

        "demo" -> {
            val (graph, roots) = SynthGraphs.modules(opts.require("modules").toInt())
            println("synthetic heap: ${graph.size} objects, ${roots.size} module roots")
            val table = ShapesAnalyzer(graph).analyze(roots)
            printReport(table)
            if (opts.flag("verify")) runVerify(graph, roots, table)
        }

        "mat-info" -> MatDiagnostics.info(opts.require("hprof"))
        "mat-histogram" -> MatDiagnostics.histogram(opts.require("hprof"), (opts["top"] ?: "30").toInt())

        "mat-analyze" -> {
            val mode = extractionModeOf(opts)
            val rules = rulesOf(opts)
            val result = MatHeapInput.open(opts.require("hprof")).use { input ->
                GraphExtractor(
                    input = input,
                    filterReferences = !opts.flag("raw-references"),
                    fieldRules = rules?.fieldRules.orEmpty(),
                    boundaryStubs = opts.flag("boundary-stubs"),
                    progress = ::println,
                ).extract(mode)
            }
            opts["stats"]?.let {
                writeJson(Path.of(it), result.stats)
                println("extraction stats written: $it")
            }
            val className = (mode as? ExtractionMode.PartialExtraction)?.className
            analyzeGraph(
                graph = result.graph,
                opts = opts,
                className = className,
                wholeHeap = mode is ExtractionMode.WholeHeap,
                wholeGraph = true,
                classRules = rules?.classRules.orEmpty(),
            )
        }

        "analyze-grph" -> {
            val graph = GraphInput(Path.of(opts.require("graph"))).read()
            val wholeHeap = opts.flag("whole-heap")
            require(!wholeHeap || opts["class"] == null) { "--whole-heap and --class are mutually exclusive" }
            require(!wholeHeap || opts.flag("whole-graph")) { "--whole-heap requires --whole-graph" }
            val className = if (wholeHeap) null else ClassName(opts.require("class"))
            val rules = rulesOf(opts)
            warnIgnoredFieldRules(rules)
            analyzeGraph(graph, opts, className, wholeHeap, opts.flag("whole-graph"),
                rules?.classRules.orEmpty())
        }

        "mat-trace" -> MatDiagnostics.trace(
            opts.require("hprof"), opts.require("class"),
            (opts["targets"] ?: "DefaultGradleBuild,DefaultGradle,BuildState,DefaultProject").split(","),
            (opts["maxlevel"] ?: "15").toInt(), (opts["maxobjs"] ?: "80000000").toInt(),
        )
        "mat-fields" -> MatDiagnostics.fields(
            opts.require("hprof"), opts.require("class"),
            (opts["limit"] ?: "5").toInt(), !opts.flag("all"),
        )
        "mat-ref-targets" -> MatDiagnostics.refTargets(
            opts.require("hprof"), opts.require("class"), (opts["maxscan"] ?: "1000000000").toInt(),
        )
        "mat-isclass" -> MatDiagnostics.isClassCheck(
            opts.require("hprof"), (opts["limit"] ?: "5").toInt(),
        )
        "mat-reachback" -> MatDiagnostics.reachBack(
            opts.require("hprof"), opts.require("class"), (opts["limit"] ?: "3").toInt(),
            (opts["maxlevel"] ?: "30").toInt(), (opts["maxobjs"] ?: "150000000").toInt(),
        )

        else -> {
            System.err.println(USAGE)
            exitProcess(if (opts.cmd == null) 0 else 2)
        }
    }
}

private fun extractionModeOf(opts: Opts): ExtractionMode {
    if (opts.flag("whole-heap")) {
        require(opts["class"] == null) { "--whole-heap and --class are mutually exclusive" }
        require(opts["scope"] == null || opts["scope"] == "heap") { "--whole-heap requires --scope heap" }
        return ExtractionMode.WholeHeap
    }
    val className = ClassName(opts.require("class"))
    return when (opts["scope"]) {
        "retained" -> ExtractionMode.PartialExtraction.Retained(className)
        "reachable" -> ExtractionMode.PartialExtraction.Reachable(className)
        else -> error("select --whole-heap --scope heap or --class with --scope retained|reachable")
    }
}

private fun analyzeGraph(
    graph: ObjectGraph,
    opts: Opts,
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
    if (opts.flag("verify-edges")) {
        val debug = analyzer.analyzeDebug(roots, wholeGraph)
        println("analyze: ${(System.nanoTime() - start) / 1_000_000} ms")
        opts["out"]?.let {
            ShapesEmit.write(debug.table, Path.of(it))
            println("artifact written: $it")
        }
        printRelax(analyzer)
        writeRelaxStats(analyzer, opts)
        val problems = verifyEdgeAccounting(graph, debug, analyzer.relaxLevels())
        if (problems.isEmpty()) println("verify-edges: edge accounting exact")
        else {
            println("verify-edges: MISMATCHES:")
            problems.forEach { println("  - $it") }
            exitProcess(1)
        }
        return
    }

    val table = analyzer.analyze(roots, wholeGraph)
    println("analyze: ${(System.nanoTime() - start) / 1_000_000} ms")
    printRelax(analyzer)
    writeRelaxStats(analyzer, opts)
    // Emit before the potentially huge human-readable report.
    opts["out"]?.let {
        ShapesEmit.write(table, Path.of(it))
        println("artifact written: $it")
    }
    printReport(table)
    if (opts.flag("verify")) runVerify(graph, roots, table, wholeGraph)
}

private fun rulesOf(opts: Opts): Relax.Rules? = opts["relax"]?.let { path ->
    Relax.parse(Files.readString(Path.of(path))).also {
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

private fun writeRelaxStats(analyzer: ShapesAnalyzer, opts: Opts) {
    opts["relax-stats"]?.let { path ->
        writeJson(Path.of(path), analyzer.relaxStats)
        println("relax stats written: $path")
    }
}

private fun runVerify(graph: ObjectGraph, roots: IntArray, table: ShapeTable, wholeGraph: Boolean = false) {
    val problems = verifyInvariants(graph, roots, table, wholeGraph)
    if (problems.isEmpty()) println("verify: all invariants hold")
    else {
        println("verify: VIOLATIONS:")
        problems.forEach { println("  - $it") }
        exitProcess(1)
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

private class Opts(val cmd: String?, private val kv: Map<String, String>, private val flags: Set<String>) {
    operator fun get(k: String): String? = kv[k]
    fun require(k: String): String = kv[k] ?: error("missing --$k")
    fun flag(k: String): Boolean = k in flags
}

private fun parseOpts(args: List<String>): Opts {
    if (args.isEmpty()) return Opts(null, emptyMap(), emptySet())
    val values = LinkedHashMap<String, String>()
    val flags = LinkedHashSet<String>()
    var i = 1
    while (i < args.size) {
        val arg = args[i]
        require(arg.startsWith("--")) { "unexpected arg: $arg" }
        val key = arg.removePrefix("--")
        if (i + 1 < args.size && !args[i + 1].startsWith("--")) {
            values[key] = args[++i]
        } else {
            flags.add(key)
        }
        i++
    }
    return Opts(args[0], values, flags)
}
