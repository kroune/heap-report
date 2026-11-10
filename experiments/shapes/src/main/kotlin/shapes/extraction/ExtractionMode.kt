package shapes.extraction

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import shapes.models.ClassName

@Serializable
sealed interface ExtractionMode {
    @Serializable
    sealed interface PartialExtraction : ExtractionMode {
        val className: ClassName

        @Serializable
        @SerialName("reachable")
        data class Reachable(override val className: ClassName) : PartialExtraction

        @Serializable
        @SerialName("retained")
        data class Retained(override val className: ClassName) : PartialExtraction
    }

    @Serializable
    @SerialName("heap")
    data object WholeHeap : ExtractionMode
}
