package shapes.mat

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import shapes.mat.MatExtract.ExtractionMode

/** Versioned-by-pipeline metadata appended to SHP1 by the Python gateway. */
@Serializable
internal data class ExtractionStats(
    val extractionMode: ExtractionMode,
    val mapProjection: String,
    val objects: Int,
    val roots: Int,
    val shallowTotal: Long,
    val sourceEdges: Long,
    val scopeBoundaryEdges: Long,
    val projectedEdges: Long,
    val projectedBoundaryEdges: Long,
    val matRetainedObjects: Int,
    val referenceFiltered: Boolean,
    // Legacy field names retained for artifact compatibility.
    val softReachableOnlyDropped: Long,
    val softReachableOnlyDroppedShallow: Long,
    val scopeFilteredOutObjects: Long,
    val scopeFilteredOutShallow: Long,
    val concealedFieldEdges: Long,
    val fieldRules: List<ExtractionFieldRule>,
)

@Serializable
internal data class ExtractionFieldRule(
    @SerialName("class") val className: String,
    val fields: List<String>,
)
