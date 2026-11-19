package shapes.cli

import com.github.ajalt.clikt.parameters.groups.mutuallyExclusiveOptions
import com.github.ajalt.clikt.parameters.groups.required
import com.github.ajalt.clikt.parameters.options.convert
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.path
import kotlinx.serialization.Serializable
import shapes.analysis.AnalysisScope
import shapes.graph.ClassName
import shapes.hprof.HprofDirectory
import shapes.hprof.HprofFile
import shapes.hprof.HprofGraphExtractor
import shapes.hprof.HprofHeapIndex
import shapes.relaxation.FieldConcealmentRules
import shapes.relaxation.ShapeRelaxationRules

private sealed interface HprofRootChoice {
    data class RootClass(val className: String) : HprofRootChoice
    data object WholeHeap : HprofRootChoice
}

@Serializable
internal data class HprofExtractionStats(
    val scope: String,
    val objects: Long,
    val shallowTotal: Long,
    val nodes: Int,
    val leafClasses: Int,
    val edges: Long,
    val roots: Int,
    val rawNonNullRefs: Long,
    val classTargetRefs: Long,
    val danglingRefs: Long,
    val mechanicsDropped: Long,
    val concealedDropped: Long,
    val projectionDropped: Long,
    val dedupDropped: Long,
    val referenceMechanicsFiltered: Boolean,
    val foldedLeaves: Boolean,
)

/** Direct HPROF extraction + shape analysis in one JVM. */
internal class HprofAnalyzeCommand : AnalysisCommand(
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

    private val stats by option("--stats", help = "Write extraction statistics as JSON").path()

    private val edgeHistogram by option(
        "--edge-histogram",
        help = "Write per-class instance/out-edge counts as TSV",
    ).path()

    override fun run() {
        val rules = loadRelaxationRules(analysisOptions.relaxationRules)
        val scopeClass = (rootChoice as? HprofRootChoice.RootClass)?.className
        val foldLeaves = scopeClass == null && !noFold

        val output = HprofFile.open(hprof).use { file ->
            val directory = HprofDirectory.scan(file)
            val heapIndex = HprofHeapIndex.build(file, directory)
            println(
                "hprof: ${heapIndex.counts.objects} objects, " +
                        "${heapIndex.classStore.size} classes, ${heapIndex.chunks.size} chunks"
            )
            HprofGraphExtractor(
                file = file,
                index = heapIndex,
                concealmentRules = rules?.fieldConcealmentRules ?: FieldConcealmentRules(),
                filterReferenceMechanics = !rawReferences,
                foldLeaves = foldLeaves,
                scopeClassName = scopeClass,
                progress = ::println,
            ).extract()
        }

        edgeHistogram?.let { path ->
            writeEdgeHistogram(path) { action ->
                val graph = output.graph
                for (nodeId in 0 until graph.nodeCount) {
                    action(graph.classNameOf(nodeId).name, graph.targetCount(nodeId))
                }
                for (leaf in graph.leafShapes) {
                    repeat(leaf.instanceCount.toInt()) {
                        action(graph.classNames[leaf.classIndex].name, 0)
                    }
                }
            }
            println("edge histogram written: $path")
        }

        stats?.let { path ->
            writeJsonReport(
                path,
                HprofExtractionStats(
                    scope = scopeClass ?: "whole-heap",
                    objects = output.graph.totalObjectCount,
                    shallowTotal = output.graph.totalShallowBytes(),
                    nodes = output.graph.nodeCount,
                    leafClasses = output.graph.leafShapes.size,
                    edges = output.graph.totalEdgeCount,
                    roots = output.graph.roots.size,
                    rawNonNullRefs = output.counters.rawNonNullRefs,
                    classTargetRefs = output.counters.classTargetRefs,
                    danglingRefs = output.counters.danglingRefs,
                    mechanicsDropped = output.counters.mechanicsDropped,
                    concealedDropped = output.counters.concealedDropped,
                    projectionDropped = output.counters.projectionDropped,
                    dedupDropped = output.counters.dedupDropped,
                    referenceMechanicsFiltered = !rawReferences,
                    foldedLeaves = foldLeaves,
                ),
            )
            println("extraction stats written: $path")
        }

        output.graph.use { graph ->
            GraphAnalysisCliWorkflow().run(
                GraphAnalysisRequest(
                    graph,
                    AnalysisScope.EntireGraph(graph.roots),
                    scopeClass?.let(::ClassName),
                    analysisOptions,
                    rules?.shapeRules ?: ShapeRelaxationRules(),
                )
            )
        }
    }
}
