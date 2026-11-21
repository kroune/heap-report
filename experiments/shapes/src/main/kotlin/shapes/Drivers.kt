package shapes

import shapes.analysis.aggregate
import shapes.analysis.collapse
import shapes.analysis.findComponents
import shapes.analysis.relax
import shapes.artifact.writeArtifact
import shapes.hprof.extractReferences
import shapes.hprof.indexHprof
import shapes.hprof.select
import shapes.relaxation.ShapeRelaxationRules
import shapes.relaxation.ShapeRelaxationStats
import shapes.report.printSummary
import shapes.report.writeEdgeHistogram
import shapes.report.writeExtractionStats
import shapes.report.writeJsonReport
import shapes.synthetic.SyntheticObjectGraphs
import shapes.verification.ShapeEdgeAccountingVerifier
import shapes.verification.ShapeInvariantVerifier

/**
 * The whole program, top to bottom. Extraction is a pipeline (the dump
 * changes representation at every step), analysis is a dataflow over one
 * graph (every step adds a fact about it), and the sinks at the end read
 * whichever facts they report on.
 */
fun runHprofAnalyze(config: HprofAnalyzeConfig, log: Log) {
    val references = indexHprof(config.hprof, log).use { dump ->
        dump.extractReferences(config.projection, log)
    }
    references.select(config.selection, log).use { graph ->
        config.extractionStats?.let { path ->
            writeExtractionStats(path, graph, references.counters, config.projection, config.selection)
            log("extraction stats written: $path")
        }
        config.edgeHistogram?.let { path ->
            writeEdgeHistogram(path, graph)
            log("edge histogram written: $path")
        }

        val components = graph.findComponents(log)
        val relaxations = graph.relax(components, config.shapeRules, log)
        val shapes = graph.collapse(components, relaxations, log)
        val result = graph.aggregate(components, shapes, log)

        config.artifact?.let { path ->
            result.writeArtifact(path)
            log("artifact written: $path")
        }
        logRelaxationStats(shapes.relaxationStats, log)
        config.relaxationStats?.let { path ->
            writeJsonReport(path, shapes.relaxationStats)
            log("relax stats written: $path")
        }
        if (config.verifyEdges) {
            val violations = ShapeEdgeAccountingVerifier.verify(
                graph, components, relaxations, shapes, result, log,
            )
            failOnViolations("verify-edges", "edge accounting exact", violations, log)
        } else {
            result.printSummary()
        }
        if (config.verifyInvariants) {
            val violations = ShapeInvariantVerifier.verify(graph, result)
            failOnViolations("verify", "all invariants hold", violations, log)
        }
    }
}

fun runDemo(config: DemoConfig, log: Log) {
    val (objects, roots) = SyntheticObjectGraphs.modules(config.moduleCount)
    log("synthetic heap: ${objects.size} objects, ${roots.size} module roots")
    val graph = objects.select(roots)

    val components = graph.findComponents(log)
    val relaxations = graph.relax(components, ShapeRelaxationRules(), log)
    val shapes = graph.collapse(components, relaxations, log)
    val result = graph.aggregate(components, shapes, log)

    result.printSummary()
    if (config.verifyInvariants) {
        val violations = ShapeInvariantVerifier.verify(graph, result)
        failOnViolations("verify", "all invariants hold", violations, log)
    }
}

private fun logRelaxationStats(stats: ShapeRelaxationStats, log: Log) {
    if (stats.relaxedObjects > 0) {
        log(
            "relax: ${stats.relaxedObjects} relaxed objects; concealed edges: " +
                "${stats.classOnlyHiddenEdges} (class-only) + " +
                "${stats.setChildrenHiddenEdges} (set-children)"
        )
    }
}

private fun failOnViolations(
    check: String,
    successMessage: String,
    violations: List<String>,
    log: Log,
) {
    if (violations.isEmpty()) {
        log("$check: $successMessage")
        return
    }
    log("$check: VIOLATIONS:")
    violations.forEach { log("  - $it") }
    throw VerificationFailed(check)
}
