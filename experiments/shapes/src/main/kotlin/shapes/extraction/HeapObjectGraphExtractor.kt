package shapes.extraction

import shapes.input.heap.HeapObjectSource
import shapes.relaxation.FieldConcealmentRules

/** Coordinates scope selection and one-pass graph materialization. */
class HeapObjectGraphExtractor(
    private val heap: HeapObjectSource,
    private val options: HeapExtractionOptions = HeapExtractionOptions(),
    private val fieldConcealmentRules: FieldConcealmentRules = FieldConcealmentRules(),
    private val progress: (String) -> Unit = {},
) {
    fun extract(scope: HeapExtractionScope): HeapExtractionResult {
        require(
            options.boundary !is ExtractionBoundary.CreateClassStubs ||
                scope is HeapExtractionScope.RetainedByClass
        ) {
            "boundary stubs only make sense for retained scope"
        }

        val startedAt = System.nanoTime()
        val rootIds = ClassInstanceRootsResolver.resolve(scope, heap, progress)
        val referenceFilter = HeapReferenceFilter.create(
            heap,
            fieldConcealmentRules,
            options.filterReferenceMechanics,
            progress,
        )
        val classObjectExclusion = ClassObjectExclusion.create(heap, progress)
        val selection = HeapScopeSelector(
            heap,
            referenceFilter,
            classObjectExclusion,
            progress,
        ).select(scope, rootIds)

        progress(
            "scope $scope: ${selection.objectCount} objects in " +
                "${(System.nanoTime() - startedAt) / 1_000_000} ms"
        )
        require(selection.objectCount > 0) { "empty $scope set" }

        val projector = StructuralGraphProjector(heap, referenceFilter)
        return ObjectGraphMaterializer(
            heap,
            referenceFilter,
            projector,
            classObjectExclusion,
            options,
            fieldConcealmentRules,
            progress,
        ).materialize(scope, selection, startedAt)
    }
}
