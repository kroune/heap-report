package shapes.cli

import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.path
import shapes.analysis.AnalysisScope
import shapes.analysis.internal.AnalysisScopeResolver
import shapes.analysis.internal.StronglyConnectedComponentsFinder
import shapes.extraction.HeapExtractionScope
import shapes.extraction.HeapObjectGraphExtractor
import shapes.mat.MatHeapObjectSource
import shapes.relaxation.FieldConcealmentRules

internal inline fun writeEdgeHistogram(
    path: java.nio.file.Path,
    enumerate: ((className: String, outEdges: Int) -> Unit) -> Unit,
) {
    val instances = HashMap<String, Long>()
    val edges = HashMap<String, Long>()
    enumerate { className, outEdges ->
        instances.merge(className, 1L, Long::plus)
        if (outEdges > 0) edges.merge(className, outEdges.toLong(), Long::plus)
    }
    java.nio.file.Files.newBufferedWriter(path).use { writer ->
        for (className in instances.keys.sorted()) {
            writer.write("$className\t${instances[className]}\t${edges[className] ?: 0}\n")
        }
    }
}

/**
 * Golden-data diagnostic: extracts the whole heap through the MAT path, runs
 * Tarjan only, and prints the SCC size histogram plus the class histogram of
 * the largest component. Used to cross-check the hprof-based pipeline.
 */
internal class MatSccHistogramCommand : ShapesCommand(
    "mat-scc-histogram",
    "Whole-heap SCC size histogram through the MAT extraction path",
) {
    private val hprof by option("--hprof", help = "Heap dump with pre-built MAT indexes")
        .path(mustExist = true, canBeDir = false)
        .required()
    private val relax by option("--relax", help = "Relaxation rules TSV")
        .path(mustExist = true, canBeDir = false)
    private val edgeHistogram by option(
        "--edge-histogram",
        help = "Write per-class instance/out-edge counts as TSV",
    ).path()

    override fun run() {
        val rules = loadRelaxationRules(relax)
        val result = MatHeapObjectSource.open(hprof).use { heap ->
            HeapObjectGraphExtractor(
                heap,
                fieldConcealmentRules = rules?.fieldConcealmentRules ?: FieldConcealmentRules(),
                progress = ::println,
            ).extract(HeapExtractionScope.WholeHeap)
        }
        val graph = result.graph

        edgeHistogram?.let { path ->
            writeEdgeHistogram(path) { action ->
                for (objectId in 0 until graph.size) {
                    action(graph.className(objectId).name, graph.referenceCount(objectId))
                }
            }
            println("edge histogram written: $path")
        }

        val startedAt = System.nanoTime()
        val objects = AnalysisScopeResolver(graph).resolve(AnalysisScope.EntireGraph(IntArray(0)))
        val components = StronglyConnectedComponentsFinder(graph).find(objects)
        println(
            "scc: ${components.members.count} components in " +
                "${(System.nanoTime() - startedAt) / 1_000_000} ms"
        )

        val sizeHistogram = sortedMapOf<Int, Long>()
        var largestComponentId = -1
        var largestSize = 0
        for (componentId in 0 until components.members.count) {
            val size = components.members.size(componentId)
            sizeHistogram.merge(size, 1L, Long::plus)
            if (size > largestSize) {
                largestSize = size
                largestComponentId = componentId
            }
        }
        println("scc size histogram (size x count):")
        for ((size, count) in sizeHistogram) {
            if (size == 1 || size >= 1000 || count >= 1000) println("  $size x $count")
        }

        println("largest scc: $largestSize members (component $largestComponentId)")
        val classHistogram = HashMap<String, Long>()
        var internalEdges = 0L
        components.members.forEachMember(largestComponentId) { objectId ->
            classHistogram.merge(graph.className(objectId).name, 1L, Long::plus)
            graph.forEachReference(objectId) { targetId ->
                if (components.assignment.componentOf(targetId) == largestComponentId) {
                    internalEdges++
                }
            }
        }
        println("largest scc internal edges: $internalEdges")
        println("largest scc classes: ${classHistogram.size} distinct; top 40:")
        classHistogram.entries.sortedByDescending { it.value }.take(40).forEach { (name, count) ->
            println("  %12d  %s".format(count, name))
        }
    }
}
