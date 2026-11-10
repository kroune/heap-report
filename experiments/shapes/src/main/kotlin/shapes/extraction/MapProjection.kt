package shapes.extraction

/**
 * Projects the implementation details of JDK map tables into their semantic
 * member edges.  The callbacks deliberately use dense MAT ids, so this class
 * can be used while streaming an extracted graph without retaining objects or
 * field metadata between calls.
 */
class MapProjection(
    private val className: (Int) -> String,
    private val indexedRefs: (Int) -> IntArray,
    private val namedRefs: (Int) -> List<Pair<String, Int>>,
) {
    /** Bucket collisions select a different concrete Guava entry subclass.
     * Once the bucket link is projected out, that class distinction is also
     * representation-only. Object/class totals are retained by the extractor.
     */
    fun structuralClassName(name: String): String = when (name) {
        IMMUTABLE_MAP_ENTRY_NONTERMINAL -> IMMUTABLE_MAP_ENTRY
        else -> name
    }

    /** Project an already-filtered reference list, avoiding a second source read. */
    fun refs(id: Int, raw: IntArray = indexedRefs(id)): IntArray {
        val name = className(id)
        return when (name) {
            LINKED_HASH_MAP -> withoutNamedEdges(raw, namedRefs(id), setOf("head", "tail"))
            HASH_MAP_NODE, CONCURRENT_HASH_MAP_NODE -> withoutNamedEdges(
                raw, namedRefs(id), setOf("next")
            )
            LINKED_HASH_MAP_ENTRY -> withoutNamedEdges(
                raw, namedRefs(id), setOf("next", "before", "after")
            )
            HASH_MAP_NODE_ARRAY, CONCURRENT_HASH_MAP_NODE_ARRAY -> projectTable(raw)
            IMMUTABLE_MAP_ENTRY_ARRAY, JAVA_MAP_ENTRY_ARRAY -> raw.distinct().sorted().toIntArray()
            CONCURRENT_HASH_MAP -> keepNamedTargets(raw, namedRefs(id), "table")
            REGULAR_IMMUTABLE_MAP_KEY_SET, REGULAR_IMMUTABLE_MAP_VALUES,
            IMMUTABLE_MAP_ENTRY_SET_REGULAR -> IntArray(0)
            REGULAR_IMMUTABLE_MAP -> keepNamedTargets(raw, namedRefs(id), "entries")
            IMMUTABLE_MAP_ENTRY_NONTERMINAL -> withoutNamedEdges(
                raw, namedRefs(id), setOf("nextInKeyBucket", "nextInValueBucket")
            )
            DEFAULT_IMMUTABLE_ATTRIBUTES_CONTAINER -> keepNamedTargets(
                raw, namedRefs(id), setOf("hierarchy")
            )
            DEFAULT_IMMUTABLE_ATTRIBUTES_ENTRY -> withoutNamedEdges(
                raw, namedRefs(id), setOf("coercionCache")
            )
            else -> raw
        }
    }

    /** Keep only the semantic entry-array field of an immutable map; views and
     * hash tables are implementation detail. */
    private fun keepNamedTargets(
        raw: IntArray,
        fields: List<Pair<String, Int>>,
        fieldName: String,
    ): IntArray = keepNamedTargets(raw, fields, setOf(fieldName))

    private fun keepNamedTargets(
        raw: IntArray,
        fields: List<Pair<String, Int>>,
        fieldNames: Set<String>,
    ): IntArray {
        val targets = fields.filter { it.first in fieldNames && it.second >= 0 }
            .mapTo(HashSet()) { it.second }
        if (targets.isEmpty()) return raw
        return raw.filter { it in targets }.distinct().toIntArray()
    }

    /** Remove exactly the named edges, not every occurrence of their target. */
    private fun withoutNamedEdges(
        raw: IntArray,
        fields: List<Pair<String, Int>>,
        removedNames: Set<String>,
    ): IntArray {
        val removed = HashSet<Int>()
        val kept = HashSet<Int>()
        for ((field, target) in fields) if (target >= 0) {
            if (field in removedNames) removed.add(target) else kept.add(target)
        }
        // MAT indexes deduplicate target IDs. A target reachable via BOTH
        // next and key must survive; subtracting named-edge counts loses it.
        removed.removeAll(kept)
        if (removed.isEmpty()) return raw
        val emitted = HashSet<Int>()
        return raw.filter { it !in removed && emitted.add(it) }.toIntArray()
    }

    /**
     * A HashMap table is represented by its semantic members rather than its
     * bucket placement.  Bucket references to recognized nodes are replaced by
     * the unique nodes reachable through only technical list links.  All other
     * indexed references (notably MAT's class pseudo-reference) are retained.
     */
    private fun projectTable(raw: IntArray): IntArray {
        val members = IntSet()
        val stack = IntStack()
        val other = IntArray(raw.size)
        var otherSize = 0

        for (target in raw) {
            if (target >= 0 && isEntryNode(className(target))) {
                if (members.add(target)) stack.push(target)
            } else {
                other[otherSize++] = target
            }
        }

        while (!stack.isEmpty()) {
            val member = stack.pop()
            val nodeClass = className(member)
            val links = namedRefs(member)
            for ((field, target) in links) {
                if (target < 0 || !isTechnicalLink(nodeClass, field)) continue
                if (isEntryNode(className(target)) && members.add(target)) stack.push(target)
            }
        }

        val out = IntArray(otherSize + members.size)
        other.copyInto(out, 0, 0, otherSize)
        members.copyInto(out, otherSize)
        return out
    }

    private fun isEntryNode(name: String): Boolean =
        name == HASH_MAP_NODE || name == LINKED_HASH_MAP_ENTRY || name == CONCURRENT_HASH_MAP_NODE

    private fun isTechnicalLink(nodeClass: String, field: String): Boolean = when (nodeClass) {
        HASH_MAP_NODE, CONCURRENT_HASH_MAP_NODE -> field == "next"
        LINKED_HASH_MAP_ENTRY -> field == "next" || field == "before" || field == "after"
        else -> false
    }

    private class IntStack {
        private var values = IntArray(16)
        private var size = 0
        fun push(value: Int) {
            if (size == values.size) values = values.copyOf(values.size * 2)
            values[size++] = value
        }
        fun pop(): Int = values[--size]
        fun isEmpty(): Boolean = size == 0
    }

    private class IntSet {
        private var values = IntArray(16)
        private var sizeInternal = 0
        private var used = BooleanArray(16)
        var size: Int = 0
            private set

        fun add(value: Int): Boolean {
            if (sizeInternal * 2 >= values.size) rehash()
            var slot = (value * -0x61c88647) and (values.size - 1)
            while (used[slot]) {
                if (values[slot] == value) return false
                slot = (slot + 1) and (values.size - 1)
            }
            used[slot] = true
            values[slot] = value
            sizeInternal++
            size++
            return true
        }

        fun copyInto(destination: IntArray, offset: Int) {
            var p = offset
            for (i in values.indices) if (used[i]) destination[p++] = values[i]
        }

        private fun rehash() {
            val oldValues = values
            val oldUsed = used
            values = IntArray(oldValues.size * 2)
            used = BooleanArray(values.size)
            sizeInternal = 0
            for (i in oldValues.indices) if (oldUsed[i]) {
                var slot = (oldValues[i] * -0x61c88647) and (values.size - 1)
                while (used[slot]) slot = (slot + 1) and (values.size - 1)
                used[slot] = true
                values[slot] = oldValues[i]
                sizeInternal++
            }
        }
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
