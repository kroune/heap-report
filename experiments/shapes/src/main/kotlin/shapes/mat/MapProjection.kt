package shapes.mat

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
    fun refs(id: Int): IntArray {
        val name = className(id)
        return when (name) {
            LINKED_HASH_MAP -> withoutNamedEdges(indexedRefs(id), namedRefs(id), setOf("head", "tail"))
            HASH_MAP_NODE -> withoutNamedEdges(indexedRefs(id), namedRefs(id), setOf("next"))
            LINKED_HASH_MAP_ENTRY -> withoutNamedEdges(
                indexedRefs(id), namedRefs(id), setOf("next", "before", "after")
            )
            HASH_MAP_NODE_ARRAY -> projectTable(indexedRefs(id))
            else -> indexedRefs(id)
        }
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
        name == HASH_MAP_NODE || name == LINKED_HASH_MAP_ENTRY

    private fun isTechnicalLink(nodeClass: String, field: String): Boolean = when (nodeClass) {
        HASH_MAP_NODE -> field == "next"
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
    }
}
