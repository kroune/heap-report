package shapes.extraction

import it.unimi.dsi.fastutil.ints.IntOpenHashSet
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap
import it.unimi.dsi.fastutil.longs.LongArrayList
import it.unimi.dsi.fastutil.longs.LongOpenHashSet
import shapes.graph.ClassName
import shapes.input.heap.HeapObjectSource
import shapes.input.heap.NamedReference
import shapes.input.heap.denseTargetId

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
        val retainedAddresses = LongOpenHashSet()
        for (reference in namedReferences) {
            if (reference.fieldName in retainedFieldNames) {
                retainedAddresses.add(reference.targetObjectAddress)
            }
        }
        if (retainedAddresses.isEmpty()) return references

        val retainedTargetIds = resolveTargetIds(namedReferences, retainedAddresses)
        return references.filter { it in retainedTargetIds }.distinct().toIntArray()
    }

    /** Removes a named edge by HPROF address without removing aliases through semantic fields. */
    private fun withoutNamedEdges(
        references: IntArray,
        namedReferences: List<NamedReference>,
        removedFieldNames: Set<String>,
    ): IntArray {
        val removedAddresses = LongOpenHashSet()
        val retainedAddresses = LongOpenHashSet()
        for (reference in namedReferences) {
            if (reference.fieldName in removedFieldNames) {
                removedAddresses.add(reference.targetObjectAddress)
            } else {
                retainedAddresses.add(reference.targetObjectAddress)
            }
        }
        removedAddresses.removeAll(retainedAddresses)
        if (removedAddresses.isEmpty()) return references

        val removedTargetIds = resolveTargetIds(namedReferences, removedAddresses)
        val emittedTargets = IntOpenHashSet()
        return references
            .filter { it !in removedTargetIds && emittedTargets.add(it) }
            .toIntArray()
    }

    /** Resolves only addresses selected by projection, reusing any ids supplied by the source. */
    private fun resolveTargetIds(
        namedReferences: List<NamedReference>,
        addresses: LongOpenHashSet,
    ): IntOpenHashSet {
        val targetIds = IntOpenHashSet()
        val resolvedAddresses = LongOpenHashSet()
        for (reference in namedReferences) {
            if (reference.targetObjectId >= 0 && reference.targetObjectAddress in addresses) {
                targetIds.add(reference.targetObjectId)
                resolvedAddresses.add(reference.targetObjectAddress)
            }
        }
        val pending = addresses.iterator()
        while (pending.hasNext()) {
            val address = pending.nextLong()
            if (resolvedAddresses.add(address)) {
                targetIds.add(heap.resolveObjectAddress(address))
            }
        }
        return targetIds
    }

    /** Replaces bucket placement with semantic entries, traversing technical links by HPROF address. */
    private fun projectHashTable(references: IntArray): IntArray {
        val memberIdByAddress = Long2IntOpenHashMap()
        val pendingMembers = LongArrayList()
        val otherReferences = IntArray(references.size)
        var otherReferenceCount = 0

        for (targetId in references) {
            if (targetId >= 0 && isEntryNode(heap.className(targetId))) {
                val address = heap.objectAddress(targetId)
                if (!memberIdByAddress.containsKey(address)) {
                    memberIdByAddress.put(address, targetId)
                    pendingMembers.add(address)
                }
            } else {
                otherReferences[otherReferenceCount++] = targetId
            }
        }

        while (!pendingMembers.isEmpty()) {
            val memberAddress = pendingMembers.removeLong(pendingMembers.size - 1)
            val memberId = memberIdByAddress.get(memberAddress)
            val memberClass = heap.className(memberId)
            for (reference in referenceFilter.namedReferences(memberId)) {
                if (!isTechnicalLink(memberClass, reference.fieldName)) continue
                val targetAddress = reference.targetObjectAddress
                if (memberIdByAddress.containsKey(targetAddress)) continue

                val targetId = heap.denseTargetId(reference)
                if (!isEntryNode(heap.className(targetId))) continue
                memberIdByAddress.put(targetAddress, targetId)
                pendingMembers.add(targetAddress)
            }
        }

        val projected = IntArray(otherReferenceCount + memberIdByAddress.size)
        otherReferences.copyInto(projected, 0, 0, otherReferenceCount)
        var outputIndex = otherReferenceCount
        val members = memberIdByAddress.keys.iterator()
        while (members.hasNext()) {
            projected[outputIndex++] = memberIdByAddress.get(members.nextLong())
        }
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
