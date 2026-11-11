package shapes.cli

import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.parameters.groups.mutuallyExclusiveOptions
import com.github.ajalt.clikt.parameters.groups.required
import com.github.ajalt.clikt.parameters.options.convert
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.choice
import com.github.ajalt.clikt.parameters.types.path
import shapes.analysis.AnalysisScope
import shapes.extraction.ExtractionBoundary
import shapes.extraction.HeapExtractionOptions
import shapes.extraction.HeapExtractionScope
import shapes.extraction.HeapObjectGraphExtractor
import shapes.graph.ClassName
import shapes.mat.MatHeapObjectSource
import shapes.relaxation.FieldConcealmentRules
import shapes.relaxation.ShapeRelaxationRules

private sealed interface AnalysisRootChoice {
    data class RootClass(val className: ClassName) : AnalysisRootChoice
    data object WholeHeap : AnalysisRootChoice
}

private fun rootClass(value: String): AnalysisRootChoice =
    AnalysisRootChoice.RootClass(ClassName(value))

internal class AnalyzeMatHeapCommand : AnalysisCommand(
    "mat-analyze",
    "Extract and analyze a MAT heap snapshot in one JVM",
) {
    private val hprof by option("--hprof", help = "Heap dump with pre-built MAT indexes")
        .path(mustExist = true, canBeDir = false)
        .required()
    private val rootChoice: AnalysisRootChoice by mutuallyExclusiveOptions(
        option("--class", help = "Class whose instances define the extraction scope")
            .convert { rootClass(it) },
        option("--whole-heap", help = "Extract and analyze the whole heap")
            .flag()
            .convert { AnalysisRootChoice.WholeHeap },
    ).required()
    private val extractionScope by option(
        "--scope",
        help = "retained/reachable with --class; heap (or omitted) with --whole-heap",
    ).choice("retained", "reachable", "heap")
    private val stats by option("--stats", help = "Write extraction statistics as JSON").path()
    private val boundaryStubs by option(
        "--boundary-stubs",
        help = "Retain stubs at extraction boundaries",
    ).flag()
    private val rawReferences by option(
        "--raw-references",
        help = "Keep soft/weak/phantom-reference edges",
    ).flag()

    override fun run() {
        val extractionScope = extractionScope()
        val rules = loadRelaxationRules(analysisOptions.relaxationRules)
        val options = HeapExtractionOptions(
            filterReferenceMechanics = !rawReferences,
            boundary = if (boundaryStubs) {
                ExtractionBoundary.CreateClassStubs
            } else {
                ExtractionBoundary.DropReferences
            },
        )
        val result = MatHeapObjectSource.open(hprof).use { heap ->
            HeapObjectGraphExtractor(
                heap,
                options,
                rules?.fieldConcealmentRules ?: FieldConcealmentRules(),
                ::println,
            ).extract(extractionScope)
        }
        stats?.let { path ->
            writeJsonReport(path, result.stats)
            println("extraction stats written: $path")
        }

        val rootClassName = (extractionScope as? HeapExtractionScope.ClassScope)?.className
        val roots = rootClassName?.let(result.graph::instancesOf) ?: IntArray(0)
        GraphAnalysisCliWorkflow().run(
            GraphAnalysisRequest(
                result.graph,
                AnalysisScope.EntireGraph(roots),
                rootClassName,
                analysisOptions,
                rules?.shapeRules ?: ShapeRelaxationRules(),
            )
        )
    }

    private fun extractionScope(): HeapExtractionScope = when (val choice = rootChoice) {
        AnalysisRootChoice.WholeHeap -> {
            if (extractionScope != null && extractionScope != "heap") {
                throw UsageError("--whole-heap requires --scope heap")
            }
            HeapExtractionScope.WholeHeap
        }
        is AnalysisRootChoice.RootClass -> when (extractionScope) {
            "retained" -> HeapExtractionScope.RetainedByClass(choice.className)
            "reachable" -> HeapExtractionScope.ReachableFromClass(choice.className)
            else -> throw UsageError("--class requires --scope retained or --scope reachable")
        }
    }
}
