package shapes.extraction

/**
 * The single source of truth for references entering the shapes scope.
 *
 * MAT's indexed refs are cheap, but reference-mechanics and field-concealment
 * rules require named fields.  Both rule types are applied together here, so
 * scope traversal, projection, emission, and statistics cannot drift apart.
 * All methods are side-effect free: callers may inspect the same object more
 * than once without double-counting anything.
 */
class ReferencePolicy(
    private val indexedRefs: (Int) -> IntArray,
    private val rawNamedRefs: (Int) -> List<Pair<String, Int>>,
    private val isReference: (Int) -> Boolean,
    private val concealedFields: (Int) -> Set<String>?,
    filterReferences: Boolean,
) {
    private val referenceFields = if (filterReferences) REFERENCE_MECHANICS_FIELDS else DISCOVERED_ONLY

    class Read(val refs: IntArray, val concealedFieldEdges: Int)

    fun refs(id: Int): IntArray = read(id).refs

    fun read(id: Int): Read {
        val concealedFields = concealedFields(id).orEmpty()
        val isReference = isReference(id)
        if (concealedFields.isEmpty() && !isReference) {
            return Read(indexedRefs(id), 0)
        }

        var concealedCount = 0
        val references = ArrayList<Int>()
        for ((field, target) in rawNamedRefs(id)) {
            if (field in concealedFields) {
                concealedCount++
                continue
            }
            if (isReference && field in referenceFields) {
                continue
            }
            references.add(target)
        }
        return Read(references.toIntArray(), concealedCount)
    }

    /** Named view used by map projection; obeys exactly the same filters. */
    fun namedRefs(id: Int): List<Pair<String, Int>> {
        val concealed = concealedFields(id).orEmpty()
        val reference = isReference(id)
        if (concealed.isEmpty() && !reference) return rawNamedRefs(id)
        return rawNamedRefs(id).filter { (field, _) ->
            field !in concealed && (!reference || field !in referenceFields)
        }
    }

    private companion object {
        val REFERENCE_MECHANICS_FIELDS = setOf("referent", "queue", "next", "discovered")
        val DISCOVERED_ONLY = setOf("discovered")
    }
}
