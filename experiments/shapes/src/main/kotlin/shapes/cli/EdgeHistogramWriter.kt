package shapes.cli

import java.nio.file.Files
import java.nio.file.Path

/**
 * Writes a per-class `name<TAB>instances<TAB>outEdges` TSV; [enumerate] calls
 * the supplied action once per object. Used by `--edge-histogram` to reconcile
 * extraction edge counts between pipelines and runs.
 */
internal inline fun writeEdgeHistogram(
    path: Path,
    enumerate: ((className: String, outEdges: Int) -> Unit) -> Unit,
) {
    val instances = HashMap<String, Long>()
    val edges = HashMap<String, Long>()
    enumerate { className, outEdges ->
        instances.merge(className, 1L, Long::plus)
        if (outEdges > 0) edges.merge(className, outEdges.toLong(), Long::plus)
    }
    Files.newBufferedWriter(path).use { writer ->
        for (className in instances.keys.sorted()) {
            writer.write("$className\t${instances[className]}\t${edges[className] ?: 0}\n")
        }
    }
}
