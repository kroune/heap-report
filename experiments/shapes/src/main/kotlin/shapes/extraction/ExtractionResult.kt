package shapes.extraction

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import shapes.graph.ObjectGraph

data class HeapExtractionResult(
    val graph: ObjectGraph,
    val stats: ExtractionStats,
)

@Serializable
data class ExtractionStats(
    @SerialName("extractionMode") val scope: HeapExtractionScope,
    @SerialName("objects") val selectedObjectCount: Int,
    @SerialName("roots") val rootCount: Int,
    @SerialName("shallowTotal") val totalShallowSize: Long,
    @SerialName("sourceEdges") val sourceReferenceCount: Long,
    @SerialName("scopeBoundaryEdges") val scopeBoundaryReferenceCount: Long,
    @SerialName("projectedEdges") val projectedReferenceCount: Long,
    @SerialName("projectedBoundaryEdges") val projectedBoundaryReferenceCount: Long,
    @SerialName("matRetainedObjects") val sourceRetainedObjectCount: Int,
    @SerialName("referenceFiltered") val referenceMechanicsFiltered: Boolean,
    @SerialName("scopeFilteredOutObjects") val scopeFilteredOutObjectCount: Long,
    @SerialName("scopeFilteredOutShallow") val scopeFilteredOutShallowSize: Long,
    @SerialName("concealedFieldEdges") val concealedFieldReferenceCount: Long,
    @SerialName("fieldRules") val concealedFieldRules: List<ConcealedFieldRuleStats>,
)

@Serializable
data class ConcealedFieldRuleStats(
    @SerialName("class") val className: String,
    val fields: List<String>,
)
