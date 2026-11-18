package shapes.extraction

import shapes.graph.ClassName
import shapes.input.heap.HeapObjectSource
import shapes.input.heap.NamedReference
import shapes.input.heap.denseTargetId
import shapes.relaxation.FieldConcealmentRules

/**
 * Single source of truth for references admitted into extraction.
 * Indexed and named views apply exactly the same filtering rules.
 */
internal class HeapReferenceFilter private constructor(
    private val heap: HeapObjectSource,
    private val referenceClassIds: Set<Int>,
    private val concealedFieldsByClassId: Map<Int, Set<String>>,
    filterReferenceMechanics: Boolean,
) {
    private val filteredReferenceFields = if (filterReferenceMechanics) {
        REFERENCE_MECHANICS_FIELDS
    } else {
        DISCOVERED_ONLY
    }

    class FilteredReferences(
        val targetIds: IntArray,
        val concealedFieldCount: Int,
    )

    fun references(objectId: Int): IntArray = read(objectId).targetIds

    fun read(objectId: Int): FilteredReferences {
        val concealedFields = concealedFieldsByClassId[heap.classId(objectId)].orEmpty()
        val isReferenceObject = heap.classId(objectId) in referenceClassIds
        if (concealedFields.isEmpty() && !isReferenceObject) {
            return FilteredReferences(heap.indexedReferences(objectId), 0)
        }

        var concealedFieldCount = 0
        val filteredTargets = ArrayList<Int>()
        for (reference in heap.namedReferences(objectId)) {
            if (reference.fieldName in concealedFields) {
                concealedFieldCount++
                continue
            }
            if (isReferenceObject && reference.fieldName in filteredReferenceFields) continue
            filteredTargets.add(heap.denseTargetId(reference))
        }
        return FilteredReferences(filteredTargets.toIntArray(), concealedFieldCount)
    }

    /** Named view used by structural projection, with identical filtering. */
    fun namedReferences(objectId: Int): List<NamedReference> {
        val concealedFields = concealedFieldsByClassId[heap.classId(objectId)].orEmpty()
        val isReferenceObject = heap.classId(objectId) in referenceClassIds
        if (concealedFields.isEmpty() && !isReferenceObject) return heap.namedReferences(objectId)

        return heap.namedReferences(objectId).filter { reference ->
            reference.fieldName !in concealedFields &&
                (!isReferenceObject || reference.fieldName !in filteredReferenceFields)
        }
    }

    companion object {
        private val REFERENCE_MECHANICS_FIELDS = setOf("referent", "queue", "next", "discovered")
        private val DISCOVERED_ONLY = setOf("discovered")

        fun create(
            heap: HeapObjectSource,
            fieldConcealmentRules: FieldConcealmentRules,
            filterReferenceMechanics: Boolean,
            progress: (String) -> Unit,
        ): HeapReferenceFilter {
            val referenceClassIds = heap.classesNamed(ClassName("java.lang.ref.Reference"), true)
                .mapTo(HashSet()) { it.classObjectId }
            val filteredFields = if (filterReferenceMechanics) {
                REFERENCE_MECHANICS_FIELDS
            } else {
                DISCOVERED_ONLY
            }
            progress(
                "java.lang.ref.Reference subtypes: ${referenceClassIds.size} classes — " +
                    "filtered fields: $filteredFields"
            )

            val concealedFieldsByClassId = HashMap<Int, Set<String>>()
            for ((className, fields) in fieldConcealmentRules.fieldsByClass) {
                val matchingClasses = heap.classesNamed(className, includeSubclasses = true)
                for (matchingClass in matchingClasses) {
                    val classId = matchingClass.classObjectId
                    concealedFieldsByClassId[classId] =
                        concealedFieldsByClassId[classId].orEmpty() + fields
                }
                progress(
                    "conceal-field rule: $className + subclasses = " +
                        "${matchingClasses.size} classes, drop fields $fields"
                )
                if (matchingClasses.isEmpty()) {
                    progress("conceal-field: WARNING $className matches no class in the input")
                }
            }

            return HeapReferenceFilter(
                heap,
                referenceClassIds,
                concealedFieldsByClassId,
                filterReferenceMechanics,
            )
        }
    }
}
