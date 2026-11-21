package shapes.report

import kotlinx.serialization.Serializable
import shapes.hprof.ExtractionCounters
import shapes.hprof.HeapGraph
import shapes.hprof.ProjectionRules
import shapes.hprof.Selection
import java.nio.file.Path

/** Extraction statistics consumed by the backend (`_publication_metadata`); keys are stable. */
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

internal fun writeExtractionStats(
    path: Path,
    graph: HeapGraph,
    counters: ExtractionCounters,
    projection: ProjectionRules,
    selection: Selection,
) {
    writeJsonReport(
        path,
        HprofExtractionStats(
            scope = (selection as? Selection.ClassClosure)?.className ?: "whole-heap",
            objects = graph.totalObjectCount,
            shallowTotal = graph.totalShallowBytes(),
            nodes = graph.nodeCount,
            leafClasses = graph.leafShapes.size,
            edges = graph.totalEdgeCount,
            roots = graph.roots.size,
            rawNonNullRefs = counters.rawNonNullRefs,
            classTargetRefs = counters.classTargetRefs,
            danglingRefs = counters.danglingRefs,
            mechanicsDropped = counters.mechanicsDropped,
            concealedDropped = counters.concealedDropped,
            projectionDropped = counters.projectionDropped,
            dedupDropped = counters.dedupDropped,
            referenceMechanicsFiltered = projection.filterReferenceMechanics,
            foldedLeaves = (selection as? Selection.WholeHeap)?.foldLeaves ?: false,
        ),
    )
}
