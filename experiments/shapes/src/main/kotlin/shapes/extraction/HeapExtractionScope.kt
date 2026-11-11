package shapes.extraction

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import shapes.graph.ClassName

@Serializable
sealed interface HeapExtractionScope {
    @Serializable
    sealed interface ClassScope : HeapExtractionScope {
        val className: ClassName
    }

    @Serializable
    @SerialName("reachable")
    data class ReachableFromClass(
        override val className: ClassName,
    ) : ClassScope

    @Serializable
    @SerialName("retained")
    data class RetainedByClass(
        override val className: ClassName,
    ) : ClassScope

    @Serializable
    @SerialName("heap")
    data object WholeHeap : HeapExtractionScope
}

data class HeapExtractionOptions(
    val filterReferenceMechanics: Boolean = true,
    val boundary: ExtractionBoundary = ExtractionBoundary.DropReferences,
)

sealed interface ExtractionBoundary {
    data object DropReferences : ExtractionBoundary

    /** One synthetic leaf per target class outside a retained scope. */
    data object CreateClassStubs : ExtractionBoundary
}
