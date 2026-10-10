package shapes

import java.nio.file.Path
import kotlin.system.exitProcess

private const val USAGE = """
shapes — exact structural collapse experiment (see backend/mat/SHAPES.md)

  analyze --graph <file> --class <className> [--out <shapes.bin>] [--verify]
  generate --modules <N> --out <graph.txt>
  demo --modules <N> [--verify]          generate + analyze in memory

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
            val t = ShapesAnalyzer(g).analyze(roots)
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
        else -> {
            System.err.println(USAGE)
            exitProcess(if (opts.cmd == null) 0 else 2)
        }
    }
}

private fun runVerify(g: ObjectGraph, roots: IntArray, t: ShapeTable) {
    val problems = verifyInvariants(g, roots, t)
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
        for (edge in t.edgesOf(e.id)) {
            val child = t.entries[edge.childShapeId]
            println(
                "        -> #%-4d %-46s mult=%d%s".format(
                    edge.childShapeId, child.label.take(46), edge.mult,
                    if (e.edgesPerMember) " (per member)" else " (total)"
                )
            )
        }
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
