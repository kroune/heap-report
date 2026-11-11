package shapes.extraction

import shapes.graph.ObjectGraph
import shapes.input.heap.HeapObjectSource
import shapes.relaxation.FieldConcealmentRules

/** Materializes a selected heap scope as a dense in-memory object graph. */
internal class ObjectGraphMaterializer(
    private val heap: HeapObjectSource,
    private val referenceFilter: HeapReferenceFilter,
    private val projector: StructuralGraphProjector,
    private val classObjectExclusion: ClassObjectExclusion,
    private val options: HeapExtractionOptions,
    private val fieldConcealmentRules: FieldConcealmentRules,
    private val progress: (String) -> Unit,
) {
    fun materialize(
        scope: HeapExtractionScope,
        selection: HeapObjectSelection,
        startedAt: Long,
    ): HeapExtractionResult {
        val selectedCount = selection.objectCount
        val idRemapper = DenseObjectIdRemapper(selection.objectIds)
        val stubs = if (options.boundary is ExtractionBoundary.CreateClassStubs) {
            BoundaryStubRegistry(
                heap,
                firstStubObjectId = selectedCount,
                queriedClassName = (scope as? HeapExtractionScope.ClassScope)?.className,
            )
        } else {
            null
        }
        val builder = ObjectGraph.Builder(
            sourceObjectCount = heap.objectCount.toLong(),
            expectedObjectCount = if (stubs == null) selectedCount else 0,
        )
        val counters = MaterializationCounters()

        var processedObjectCount = 0
        var sourceObjectId = selection.objectIds.nextSetBit(0)
        while (sourceObjectId >= 0) {
            val projectedClassName = projector.projectClassName(heap.className(sourceObjectId))
            val classIndex = builder.classIndex(projectedClassName)
            val shallowSize = heap.shallowSize(sourceObjectId)
            counters.totalShallowSize += shallowSize.toLong()

            val filtered = referenceFilter.read(sourceObjectId)
            counters.concealedFieldReferenceCount += filtered.concealedFieldCount
            recordSourceReferences(sourceObjectId, filtered.targetIds, scope, selection, stubs, counters)

            val projected = projector.projectReferences(sourceObjectId, filtered.targetIds)
            val references = mapProjectedReferences(projected, selection, idRemapper, stubs, counters)
            builder.addObject(classIndex, shallowSize, references)
            counters.projectedReferenceCount += references.size

            processedObjectCount++
            if (processedObjectCount % 5_000_000 == 0) {
                progress("  materialize: $processedObjectCount/$selectedCount")
            }
            sourceObjectId = selection.objectIds.nextSetBit(sourceObjectId + 1)
        }

        stubs?.appendTo(builder)
        if (stubs != null && stubs.count > 0) {
            progress(
                "boundary stubs: ${stubs.count} target classes, " +
                    "${counters.scopeBoundaryReferenceCount} boundary refs"
            )
        }

        val graph = builder.build()
        progress(
            "materialized ${graph.size} objects / ${graph.referenceCount} refs, " +
                "${graph.classNames.size} classes in " +
                "${(System.nanoTime() - startedAt) / 1_000_000} ms total"
        )
        progress(
            "edges: source=${counters.sourceReferenceCount} " +
                "projected=${counters.projectedReferenceCount} " +
                "outside-scope=${counters.scopeBoundaryReferenceCount}"
        )
        return HeapExtractionResult(
            graph,
            counters.build(scope, selection, options, fieldConcealmentRules),
        )
    }

    private fun recordSourceReferences(
        sourceObjectId: Int,
        references: IntArray,
        scope: HeapExtractionScope,
        selection: HeapObjectSelection,
        stubs: BoundaryStubRegistry?,
        counters: MaterializationCounters,
    ) {
        for (targetId in references) {
            if (targetId < 0 || classObjectExclusion.isExcluded(targetId)) continue
            if (selection.objectIds.get(targetId)) {
                counters.sourceReferenceCount++
                continue
            }

            check(scope is HeapExtractionScope.RetainedByClass) {
                "ref $targetId of $sourceObjectId escaped the reachable set"
            }
            counters.scopeBoundaryReferenceCount++
            stubs?.denseIdFor(targetId)
        }
    }

    private fun mapProjectedReferences(
        projected: IntArray,
        selection: HeapObjectSelection,
        idRemapper: DenseObjectIdRemapper,
        stubs: BoundaryStubRegistry?,
        counters: MaterializationCounters,
    ): IntArray {
        var outputCount = 0
        for (targetId in projected) {
            if (targetId < 0 || classObjectExclusion.isExcluded(targetId)) continue
            if (selection.objectIds.get(targetId) || stubs != null) {
                outputCount++
            } else {
                counters.projectedBoundaryReferenceCount++
            }
        }

        val mapped = IntArray(outputCount)
        var outputIndex = 0
        for (targetId in projected) {
            if (targetId < 0 || classObjectExclusion.isExcluded(targetId)) continue
            val denseTargetId = when {
                selection.objectIds.get(targetId) -> idRemapper.denseId(targetId)
                stubs != null -> stubs.denseIdFor(targetId)
                else -> continue
            }
            mapped[outputIndex++] = denseTargetId
        }
        return mapped
    }
}

private class MaterializationCounters {
    var totalShallowSize = 0L
    var sourceReferenceCount = 0L
    var scopeBoundaryReferenceCount = 0L
    var projectedReferenceCount = 0L
    var projectedBoundaryReferenceCount = 0L
    var concealedFieldReferenceCount = 0L

    fun build(
        scope: HeapExtractionScope,
        selection: HeapObjectSelection,
        options: HeapExtractionOptions,
        fieldConcealmentRules: FieldConcealmentRules,
    ): ExtractionStats = ExtractionStats(
        scope = scope,
        selectedObjectCount = selection.objectCount,
        rootCount = selection.rootIds.size,
        totalShallowSize = totalShallowSize,
        sourceReferenceCount = sourceReferenceCount,
        scopeBoundaryReferenceCount = scopeBoundaryReferenceCount,
        projectedReferenceCount = projectedReferenceCount,
        projectedBoundaryReferenceCount = projectedBoundaryReferenceCount,
        sourceRetainedObjectCount = selection.sourceRetainedObjectCount,
        referenceMechanicsFiltered = options.filterReferenceMechanics,
        scopeFilteredOutObjectCount = selection.filteredOutObjectCount,
        scopeFilteredOutShallowSize = selection.filteredOutShallowSize,
        concealedFieldReferenceCount = concealedFieldReferenceCount,
        concealedFieldRules = fieldConcealmentRules.fieldsByClass.entries
            .sortedBy { it.key.name }
            .map { (className, fields) ->
                ConcealedFieldRuleStats(className.name, fields.sorted())
            },
    )
}
