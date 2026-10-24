package shapes

import java.nio.file.Path
import kotlin.system.exitProcess

private const val USAGE = """
shapes — exact structural collapse (see backend/mat/SHAPES.md)

  analyze --graph <file> --class <className> [--out <shapes.bin>] [--verify]
  generate --modules <N> --out <graph.txt>
  demo --modules <N> [--verify]          generate + analyze in memory

common options:
  --relax <rules.tsv>   class relaxation rules (Relax.kt): weaken the structural
                        key for listed classes (class-only|set-children)

real heap dumps (MAT library, needs pre-built indexes next to the hprof):
  mat-info --hprof <file.hprof>
  mat-histogram --hprof <file.hprof> [--top 30]
  mat-extract --hprof <file.hprof> --class <className> --out <r.grph>
      [--scope retained|reachable] [--normalize-maps] [--stats <stats.json>] [--boundary-stubs]
      [--raw-references]  keep soft/weak/phantom-reference edges (default: filtered —
                          the GC clears them, so they do not retain their referents)
  mat-trace --hprof <file.hprof> --class <className> [--targets a,b,c] [--maxlevel 15] [--maxobjs 80M]
  analyze-grph --graph <r.grph> --class <className> [--out <shapes.bin>] [--verify]
      [--whole-graph]  include all preselected objects, preserving original roots

graph format: '<id> <class> <shallowBytes> [refId,refId,...]' per line, '#' comments
"""

fun main(args: Array<String>) {
    val opts = parseOpts(args.toList())
    when (opts.cmd) {
        "analyze" -> {
            val g = ObjectGraph.load(Path.of(opts.require("graph")))
            val cls = opts.require("class")
            val roots = g.instancesOf(cls)
            println("class $cls: ${roots.size} instances (roots)")
            val z = ShapesAnalyzer(g, relaxOf(opts))
            val t = z.analyze(roots)
            printRelax(z)
            printReport(t)
            opts["out"]?.let {
                ShapesEmit.write(t, Path.of(it))
                println("artifact written: $it")
            }
            if (opts.flag("verify")) runVerify(g, roots, t)
        }
        "generate" -> {
            val (g, _) = SynthGraphs.modules(opts.require("modules").toInt())
            g.save(Path.of(opts.require("out")))
            println("graph written: ${opts.require("out")} (${g.size} objects)")
        }
        "demo" -> {
            val (g, roots) = SynthGraphs.modules(opts.require("modules").toInt())
            println("synthetic heap: ${g.size} objects, ${roots.size} module roots")
            val t = ShapesAnalyzer(g).analyze(roots)
            printReport(t)
            if (opts.flag("verify")) runVerify(g, roots, t)
        }
        "mat-info" -> shapes.mat.MatExtract.info(opts.require("hprof"))
        "mat-histogram" -> shapes.mat.MatExtract.histogram(opts.require("hprof"), (opts["top"] ?: "30").toInt())
        "mat-extract" -> {
            val fieldRules = opts["relax"]?.let { path ->
                val rules = Relax.parse(java.nio.file.Files.readString(Path.of(path)))
                if (rules.classRules.isNotEmpty())
                    println("relax: ${rules.classRules.size} analyzer-side rules ignored by mat-extract " +
                        "(pass the same file to analyze-grph)")
                rules.fieldRules
            } ?: emptyMap()
            shapes.mat.MatExtract.extract(
                opts.require("hprof"), opts.require("class"), Path.of(opts.require("out")),
                opts["scope"] ?: "reachable", opts.flag("normalize-maps"),
                opts["stats"]?.let { Path.of(it) }, opts.flag("boundary-stubs"),
                filterReferences = !opts.flag("raw-references"),
                fieldRules = fieldRules,
            )
        }
        "mat-trace" -> shapes.mat.MatExtract.trace(
            opts.require("hprof"), opts.require("class"),
            (opts["targets"] ?: "DefaultGradleBuild,DefaultGradle,BuildState,DefaultProject").split(","),
            (opts["maxlevel"] ?: "15").toInt(),
            (opts["maxobjs"] ?: "80000000").toInt(),
        )
        "mat-fields" -> shapes.mat.MatExtract.fields(
            opts.require("hprof"), opts.require("class"),
            (opts["limit"] ?: "5").toInt(), !opts.flag("all"),
        )
        "mat-ref-targets" -> shapes.mat.MatExtract.refTargets(
            opts.require("hprof"), opts.require("class"),
            (opts["maxscan"] ?: "1000000000").toInt(),
        )
        "mat-isclass" -> shapes.mat.MatExtract.isClassCheck(
            opts.require("hprof"),
            (opts["limit"] ?: "5").toInt(),
        )
        "mat-reachback" -> shapes.mat.MatExtract.reachBack(
            opts.require("hprof"), opts.require("class"),
            (opts["limit"] ?: "3").toInt(),
            (opts["maxlevel"] ?: "30").toInt(),
            (opts["maxobjs"] ?: "150000000").toInt(),
        )
        "analyze-grph" -> {
            val g = ObjectGraph.loadGrph(Path.of(opts.require("graph")))
            val cls = opts.require("class")
            val roots = g.instancesOf(cls)
            println("loaded ${g.size} objects (source heap ${g.sourceObjectCount}), class $cls: ${roots.size} roots")
            val t0 = System.nanoTime()
            if (opts.flag("verify-edges")) {
                // inv3 audit: analyze once, then check every ref against the artifact
                val z = ShapesAnalyzer(g, relaxOf(opts)).apply { progress = ::println }
                val dbg = z.analyzeDebug(roots, opts.flag("whole-graph"))
                println("analyze: ${(System.nanoTime() - t0) / 1_000_000} ms")
                opts["out"]?.let {
                    ShapesEmit.write(dbg.table, Path.of(it))
                    println("artifact written: $it")
                }
                printRelax(z)
                val problems = verifyEdgeAccounting(g, dbg, z.relaxLevels())
                if (problems.isEmpty()) println("verify-edges: edge accounting exact")
                else {
                    println("verify-edges: MISMATCHES:")
                    problems.forEach { println("  - $it") }
                    exitProcess(1)
                }
                return
            }
            val z = ShapesAnalyzer(g, relaxOf(opts)).apply { progress = ::println }
            val t = z.analyze(roots, opts.flag("whole-graph"))
            println("analyze: ${(System.nanoTime() - t0) / 1_000_000} ms")
            printRelax(z)
            writeRelaxStats(z, opts)
            // artifact FIRST: printReport of a mega-SCC_UNIQUE shape can emit
            // a million edge lines — the report must never cost the artifact
            opts["out"]?.let {
                ShapesEmit.write(t, Path.of(it))
                println("artifact written: $it")
            }
            printReport(t)
            if (opts.flag("verify")) runVerify(g, roots, t, opts.flag("whole-graph"))
        }
        else -> {
            System.err.println(USAGE)
            exitProcess(if (opts.cmd == null) 0 else 2)
        }
    }
}

private fun relaxOf(opts: Opts): Map<String, Int> =
    opts["relax"]?.let { path ->
        val rules = Relax.parse(java.nio.file.Files.readString(Path.of(path)))
        println("relax: ${rules.classRules.size} analyzer rules from $path" +
            (if (rules.fieldRules.isEmpty()) "" else " (${rules.fieldRules.size} extraction-side field rules — ignored here)"))
        rules.classRules
    } ?: emptyMap()

private fun printRelax(z: ShapesAnalyzer) {
    val s = z.relaxStats
    if (s.relaxedObjects > 0) {
        println("relax: ${s.relaxedObjects} relaxed objects; concealed edges: " +
            "${s.classOnlyHiddenEdges} (class-only) + ${s.setChildrenHiddenEdges} (set-children)")
    }
}

private fun writeRelaxStats(z: ShapesAnalyzer, opts: Opts) {
    opts["relax-stats"]?.let { path ->
        writeJson(Path.of(path), z.relaxStats)
        println("relax stats written: $path")
    }
}

private fun runVerify(g: ObjectGraph, roots: IntArray, t: ShapeTable, wholeGraph: Boolean = false) {
    val problems = verifyInvariants(g, roots, t, wholeGraph)
    if (problems.isEmpty()) println("verify: all invariants hold")
    else {
        println("verify: VIOLATIONS:")
        problems.forEach { println("  - $it") }
        exitProcess(1)
    }
}

private fun printReport(t: ShapeTable) {
    println("reachable objects : ${t.reachableCount}")
    println("unique shapes     : ${t.entries.size}")
    if (t.entries.isNotEmpty()) {
        println("collapse ratio    : %.1fx".format(t.reachableCount.toDouble() / t.entries.size))
    }
    println("roots histogram   :")
    for ((sid, cnt) in t.rootsHistogram.entries.sortedByDescending { it.value }) {
        println("  %-56s x %d".format(t.entries[sid].label, cnt))
    }
    println("shapes (by shallowTotal):")
    for (e in t.entries.sortedByDescending { it.shallowTotal }.take(20)) {
        println(
            "  #%-4d %-52s %-9s count=%-7d shallowTotal=%-11d min=%-7d max=%-7d roots=%d".format(
                e.id, e.label.take(52), e.kind, e.instanceCount, e.shallowTotal,
                e.shallowMin, e.shallowMax, e.rootCount
            )
        )
        for (edge in t.edgesOf(e.id).take(10)) {
            val child = t.entries[edge.childShapeId]
            println(
                "        -> #%-4d %-46s mult=%d%s".format(
                    edge.childShapeId, child.label.take(46), edge.mult,
                    if (e.edgesPerMember) " (per member)" else " (total)"
                )
            )
        }
        val more = t.edgesOf(e.id).size - 10
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
    val kv = LinkedHashMap<String, String>()
    val flags = LinkedHashSet<String>()
    var i = 1
    while (i < args.size) {
        val a = args[i]
        require(a.startsWith("--")) { "unexpected arg: $a" }
        val k = a.removePrefix("--")
        if (i + 1 < args.size && !args[i + 1].startsWith("--")) kv[k] = args[++i]
        else flags.add(k)
        i++
    }
    return Opts(args[0], kv, flags)
}
