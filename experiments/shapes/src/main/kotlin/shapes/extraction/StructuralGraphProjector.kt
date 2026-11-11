package shapes.extraction

import shapes.graph.ClassName
import shapes.graph.storage.IntStack
import shapes.input.heap.HeapObjectSource
import shapes.input.heap.NamedReference

/**
 * Converts implementation-level heap references into semantic structure:
 * map bucket chains, linked-order pointers, views and caches are hidden.
 */
internal class StructuralGraphProjector(
    private val heap: HeapObjectSource,
    private val referenceFilter: HeapReferenceFilter,
) {
    fun projectClassName(className: ClassName): ClassName = when (className.name) {
        IMMUTABLE_MAP_ENTRY_NONTERMINAL -> ClassName(IMMUTABLE_MAP_ENTRY)
        else -> className
    }

    /** Projects an already filtered reference list without reading it again. */
    fun projectReferences(
        objectId: Int,
        filteredReferences: IntArray = referenceFilter.references(objectId),
    ): IntArray {
        return when (heap.className(objectId).name) {
            LINKED_HASH_MAP -> withoutNamedEdges(
                filteredReferences,
                referenceFilter.namedReferences(objectId),
                setOf("head", "tail"),
            )
            HASH_MAP_NODE, CONCURRENT_HASH_MAP_NODE -> withoutNamedEdges(
                filteredReferences,
                referenceFilter.namedReferences(objectId),
                setOf("next"),
            )
            LINKED_HASH_MAP_ENTRY -> withoutNamedEdges(
                filteredReferences,
                referenceFilter.namedReferences(objectId),
                setOf("next", "before", "after"),
            )
            HASH_MAP_NODE_ARRAY, CONCURRENT_HASH_MAP_NODE_ARRAY ->
                projectHashTable(filteredReferences)
            IMMUTABLE_MAP_ENTRY_ARRAY, JAVA_MAP_ENTRY_ARRAY ->
                filteredReferences.distinct().sorted().toIntArray()
            CONCURRENT_HASH_MAP -> keepNamedTargets(
                filteredReferences,
                referenceFilter.namedReferences(objectId),
                setOf("table"),
            )
            REGULAR_IMMUTABLE_MAP_KEY_SET,
            REGULAR_IMMUTABLE_MAP_VALUES,
            IMMUTABLE_MAP_ENTRY_SET_REGULAR -> IntArray(0)
            REGULAR_IMMUTABLE_MAP -> keepNamedTargets(
                filteredReferences,
                referenceFilter.namedReferences(objectId),
                setOf("entries"),
            )
            IMMUTABLE_MAP_ENTRY_NONTERMINAL -> withoutNamedEdges(
                filteredReferences,
                referenceFilter.namedReferences(objectId),
                setOf("nextInKeyBucket", "nextInValueBucket"),
            )
            DEFAULT_IMMUTABLE_ATTRIBUTES_CONTAINER -> keepNamedTargets(
                filteredReferences,
                referenceFilter.namedReferences(objectId),
                setOf("hierarchy"),
            )
            DEFAULT_IMMUTABLE_ATTRIBUTES_ENTRY -> withoutNamedEdges(
                filteredReferences,
                referenceFilter.namedReferences(objectId),
                setOf("coercionCache"),
            )
            else -> filteredReferences
        }
    }

    private fun keepNamedTargets(
        references: IntArray,
        namedReferences: List<NamedReference>,
        retainedFieldNames: Set<String>,
    ): IntArray {
        val retainedTargets = namedReferences
            .filter { it.fieldName in retainedFieldNames && it.targetObjectId >= 0 }
            .mapTo(HashSet()) { it.targetObjectId }
        if (retainedTargets.isEmpty()) return references
        return references.filter { it in retainedTargets }.distinct().toIntArray()
    }

    /** Removes a named edge without removing aliases through semantic fields. */
    private fun withoutNamedEdges(
        references: IntArray,
        namedReferences: List<NamedReference>,
        removedFieldNames: Set<String>,
    ): IntArray {
        val removedTargets = HashSet<Int>()
        val retainedTargets = HashSet<Int>()
        for (reference in namedReferences) {
            if (reference.targetObjectId < 0) continue
            if (reference.fieldName in removedFieldNames) {
                removedTargets.add(reference.targetObjectId)
            } else {
                retainedTargets.add(reference.targetObjectId)
            }
        }
        removedTargets.removeAll(retainedTargets)
        if (removedTargets.isEmpty()) return references

        val emittedTargets = HashSet<Int>()
        return references
            .filter { it !in removedTargets && emittedTargets.add(it) }
            .toIntArray()
    }

    /** Replaces bucket placement with the set of semantic map entries. */
    private fun projectHashTable(references: IntArray): IntArray {
        val memberIds = ObjectIdSet()
        val pendingMembers = IntStack(initialCapacity = 16)
        val otherReferences = IntArray(references.size)
        var otherReferenceCount = 0

        for (targetId in references) {
            if (targetId >= 0 && isEntryNode(heap.className(targetId))) {
                if (memberIds.add(targetId)) pendingMembers.push(targetId)
            } else {
                otherReferences[otherReferenceCount++] = targetId
            }
        }

        while (!pendingMembers.isEmpty()) {
            val memberId = pendingMembers.pop()
            val memberClass = heap.className(memberId)
            for (reference in referenceFilter.namedReferences(memberId)) {
                val targetId = reference.targetObjectId
                if (targetId < 0 || !isTechnicalLink(memberClass, reference.fieldName)) continue
                if (isEntryNode(heap.className(targetId)) && memberIds.add(targetId)) {
                    pendingMembers.push(targetId)
                }
            }
        }

        val projected = IntArray(otherReferenceCount + memberIds.size)
        otherReferences.copyInto(projected, 0, 0, otherReferenceCount)
        memberIds.copyInto(projected, otherReferenceCount)
        return projected
    }

    private fun isEntryNode(className: ClassName): Boolean = when (className.name) {
        HASH_MAP_NODE, LINKED_HASH_MAP_ENTRY, CONCURRENT_HASH_MAP_NODE -> true
        else -> false
    }

    private fun isTechnicalLink(className: ClassName, fieldName: String): Boolean =
        when (className.name) {
            HASH_MAP_NODE, CONCURRENT_HASH_MAP_NODE -> fieldName == "next"
            LINKED_HASH_MAP_ENTRY -> fieldName == "next" || fieldName == "before" || fieldName == "after"
            else -> false
        }

    private class ObjectIdSet {
        private var values = IntArray(16)
        private var occupied = BooleanArray(16)
        var size = 0
            private set

        fun add(value: Int): Boolean {
            if (size * 2 >= values.size) rehash()
            var slot = hash(value) and (values.size - 1)
            while (occupied[slot]) {
                if (values[slot] == value) return false
                slot = (slot + 1) and (values.size - 1)
            }
            occupied[slot] = true
            values[slot] = value
            size++
            return true
        }

        fun copyInto(destination: IntArray, offset: Int) {
            var outputIndex = offset
            for (slot in values.indices) {
                if (occupied[slot]) destination[outputIndex++] = values[slot]
            }
        }

        private fun rehash() {
            val oldValues = values
            val oldOccupied = occupied
            values = IntArray(oldValues.size * 2)
            occupied = BooleanArray(values.size)
            size = 0
            for (slot in oldValues.indices) {
                if (oldOccupied[slot]) add(oldValues[slot])
            }
        }

        private fun hash(value: Int): Int = value * -0x61c88647
    }

    private companion object {
        const val HASH_MAP_NODE = "java.util.HashMap" + '$' + "Node"
        const val LINKED_HASH_MAP = "java.util.LinkedHashMap"
        const val LINKED_HASH_MAP_ENTRY = "java.util.LinkedHashMap" + '$' + "Entry"
        const val HASH_MAP_NODE_ARRAY = "java.util.HashMap" + '$' + "Node[]"
        const val CONCURRENT_HASH_MAP = "java.util.concurrent.ConcurrentHashMap"
        const val CONCURRENT_HASH_MAP_NODE = CONCURRENT_HASH_MAP + '$' + "Node"
        const val CONCURRENT_HASH_MAP_NODE_ARRAY = CONCURRENT_HASH_MAP_NODE + "[]"
        const val IMMUTABLE_MAP_ENTRY_ARRAY = "com.google.common.collect.ImmutableMapEntry[]"
        const val JAVA_MAP_ENTRY_ARRAY = "java.util.Map" + '$' + "Entry[]"
        const val REGULAR_IMMUTABLE_MAP = "com.google.common.collect.RegularImmutableMap"
        const val REGULAR_IMMUTABLE_MAP_KEY_SET = REGULAR_IMMUTABLE_MAP + '$' + "KeySet"
        const val REGULAR_IMMUTABLE_MAP_VALUES = REGULAR_IMMUTABLE_MAP + '$' + "Values"
        const val IMMUTABLE_MAP_ENTRY_SET_REGULAR =
            "com.google.common.collect.ImmutableMapEntrySet" + '$' + "RegularEntrySet"
        const val IMMUTABLE_MAP_ENTRY = "com.google.common.collect.ImmutableMapEntry"
        const val IMMUTABLE_MAP_ENTRY_NONTERMINAL =
            IMMUTABLE_MAP_ENTRY + '$' + "NonTerminalImmutableMapEntry"
        const val DEFAULT_IMMUTABLE_ATTRIBUTES_ENTRY =
            "org.gradle.api.internal.attributes.DefaultImmutableAttributesEntry"
        const val DEFAULT_IMMUTABLE_ATTRIBUTES_CONTAINER =
            "org.gradle.api.internal.attributes.DefaultImmutableAttributesContainer"
    }
}
