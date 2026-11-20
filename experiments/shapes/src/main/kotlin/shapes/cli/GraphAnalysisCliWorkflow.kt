package shapes.cli

import com.github.ajalt.clikt.core.ProgramResult
import shapes.analysis.AnalysisScope
import shapes.analysis.ShapeAnalysisResult
import shapes.analysis.ShapesAnalyzer
import shapes.artifact.Shp1ArtifactWriter
import shapes.graph.AnalysisGraph
import shapes.graph.ClassName
import shapes.relaxation.RelaxationRules
import shapes.relaxation.RelaxationRulesTsvParser
import shapes.relaxation.ShapeRelaxationRules
import shapes.verification.ShapeEdgeAccountingVerifier
import shapes.verification.ShapeInvariantVerifier
import java.nio.file.Files
import java.nio.file.Path

internal class GraphAnalysisRequest(
    val graph: AnalysisGraph,
    val scope: AnalysisScope,
    val rootClassName: ClassName?,
    val options: AnalysisCliOptions,
    val relaxationRules: ShapeRelaxationRules,
)

internal class GraphAnalysisCliWorkflow {
    fun run(request: GraphAnalysisRequest) {
        val rootCount = when (val scope = request.scope) {
            is AnalysisScope.ReachableFrom -> scope.roots.size
            is AnalysisScope.EntireGraph -> scope.rootsForHistogram.size
        }
        println(
            "input: ${request.graph.totalObjectCount} objects " +
                "(${request.graph.size} nodes / ${request.graph.leafShapes.size} leaf classes) / " +
                "${request.graph.totalEdgeCount} refs " +
                "(source heap ${request.graph.sourceObjectCount}), " +
                (request.rootClassName?.let { "class $it: $rootCount roots" }
                    ?: "whole heap: no class roots")
        )

        val startedAt = System.nanoTime()
        val analyzer = ShapesAnalyzer(request.graph, request.relaxationRules).apply {
            progress = ::println
        }
        val debug = if (request.options.verifyEdgeAccounting) {
            analyzer.analyzeDebug(request.scope)
        } else {
            null
        }
        val result = debug?.result ?: analyzer.analyze(request.scope)
        println("analyze: ${(System.nanoTime() - startedAt) / 1_000_000} ms")

        request.options.outputArtifact?.let { path ->
            Shp1ArtifactWriter(path).write(result)
            println("artifact written: $path")
        }
        printRelaxationStats(analyzer)
        writeRelaxationStats(analyzer, request.options.relaxationStats)

        if (debug != null) {
            val violations = ShapeEdgeAccountingVerifier.verify(
                request.graph,
                debug,
                ::println,
            )
            reportVerification("verify-edges", "edge accounting exact", violations)
            return
        }

        ShapeAnalysisConsoleReporter.print(result)
        if (request.options.verifyInvariants) {
            val violations = ShapeInvariantVerifier.verify(request.graph, request.scope, result)
            reportVerification("verify", "all invariants hold", violations)
        }
    }

    private fun reportVerification(
        commandName: String,
        successMessage: String,
        violations: List<String>,
    ) {
        if (violations.isEmpty()) {
            println("$commandName: $successMessage")
            return
        }
        println("$commandName: VIOLATIONS:")
        violations.forEach { println("  - $it") }
        throw ProgramResult(1)
    }

    private fun printRelaxationStats(analyzer: ShapesAnalyzer) {
        val stats = analyzer.relaxationStats
        if (stats.relaxedObjects > 0) {
            println(
                "relax: ${stats.relaxedObjects} relaxed objects; concealed edges: " +
                    "${stats.classOnlyHiddenEdges} (class-only) + " +
                    "${stats.setChildrenHiddenEdges} (set-children)"
            )
        }
    }

    private fun writeRelaxationStats(analyzer: ShapesAnalyzer, outputPath: Path?) {
        outputPath?.let { path ->
            writeJsonReport(path, analyzer.relaxationStats)
            println("relax stats written: $path")
        }
    }
}

internal object ShapeAnalysisConsoleReporter {
    fun print(result: ShapeAnalysisResult) {
        println("reachable objects : ${result.reachableObjectCount}")
        println("unique shapes     : ${result.entries.size}")
        if (result.entries.isNotEmpty()) {
            val collapseRatio = result.reachableObjectCount.toDouble() / result.entries.size
            println("collapse ratio    : %.1fx".format(collapseRatio))
        }

        println("roots histogram   :")
        val sortedRoots = result.rootsHistogram.entries.sortedByDescending { it.value }
        for ((shapeId, count) in sortedRoots) {
            println("  %-56s x %s".format(result.entries[shapeId].label, count))
        }

        println("shapes (by shallowTotal):")
        val largestShapes = result.entries.sortedByDescending { it.shallowTotal }.take(20)
        for (entry in largestShapes) {
            val rowFormat = "  #%-4s %-52s %-9s count=%-7s shallowTotal=%-11s " +
                "min=%-7s max=%-7s roots=%s"
            println(
                rowFormat.format(
                    entry.id,
                    entry.label.take(52),
                    entry.kind,
                    entry.instanceCount,
                    entry.shallowTotal,
                    entry.shallowMin,
                    entry.shallowMax,
                    entry.rootCount,
                )
            )
            printEdges(result, entry.id)
        }
    }

    private fun printEdges(result: ShapeAnalysisResult, shapeId: Int) {
        val entry = result.entries[shapeId]
        val edges = result.edgesOf(shapeId)
        for (edge in edges.take(10)) {
            val child = result.entries[edge.childShapeId]
            println(
                "        -> #%-4s %-46s mult=%s%s".format(
                    edge.childShapeId,
                    child.label.take(46),
                    edge.multiplicity,
                    if (entry.edgesPerMember) " (per member)" else " (total)",
                )
            )
        }
        val remainingCount = edges.size - 10
        if (remainingCount > 0) println("        -> ... and $remainingCount more edges")
    }
}

internal fun loadRelaxationRules(path: Path?): RelaxationRules? = path?.let {
    RelaxationRulesTsvParser.parse(Files.readString(it)).also { rules ->
        println(
            "relax: ${rules.shapeRules.byClass.size} analyzer rules, " +
                "${rules.shapeRules.byPattern.size} regex rules, " +
                "${rules.fieldConcealmentRules.fieldsByClass.size} field rules from $path"
        )
    }
}
