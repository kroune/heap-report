package shapes.extraction

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import shapes.ObjectGraph

/** The in-memory graph and its extraction metadata; no serialization round-trip. */
data class ExtractionResult(
    val graph: ObjectGraph,
    val stats: ExtractionStats,
)

/** Versioned-by-pipeline metadata appended to SHP1 by the Python gateway. */
@Serializable
data class ExtractionStats(
    val extractionMode: ExtractionMode,
    val objects: Int,
    val roots: Int,
    val shallowTotal: Long,
    val sourceEdges: Long,
    val scopeBoundaryEdges: Long,
    val projectedEdges: Long,
    val projectedBoundaryEdges: Long,
    val matRetainedObjects: Int,
    val referenceFiltered: Boolean,
    val scopeFilteredOutObjects: Long,
    val scopeFilteredOutShallow: Long,
    val concealedFieldEdges: Long,
    val fieldRules: List<ExtractionFieldRule>,
)

@Serializable
data class ExtractionFieldRule(
    @SerialName("class") val className: String,
    val fields: List<String>,
)
