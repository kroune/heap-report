package shapes.extraction

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertSame

class MapProjectionTest {
    private class FakeGraph(
        val classes: Array<String>,
        val indexed: Array<IntArray>,
        val named: Array<List<Pair<String, Int>>>,
    ) {
        var classReads = 0
        var indexedReads = 0
        var namedReads = 0
        fun projection() = MapProjection(
            { classReads++; classes[it] },
            { indexedReads++; indexed[it] },
            { namedReads++; named[it] },
        )
    }

    @Test
    fun `named links are removed but aliases through key or value survive`() {
        val g = FakeGraph(
            arrayOf("java.util.HashMap" + '$' + "Node"),
            arrayOf(intArrayOf(7, 8)),
            arrayOf(listOf("key" to 7, "next" to 7, "value" to 8)),
        )
        assertContentEquals(intArrayOf(7, 8), g.projection().refs(0))
    }

    @Test
    fun `linked map and entry remove only their technical named links`() {
        val g = FakeGraph(
            arrayOf("java.util.LinkedHashMap", "java.util.LinkedHashMap" + '$' + "Entry"),
            arrayOf(intArrayOf(1, 2, 1), intArrayOf(4, 5, 4, 6)),
            arrayOf(
                listOf("head" to 1, "tail" to 2, "size" to 1),
                listOf("next" to 4, "before" to 5, "after" to 4, "key" to 6, "value" to 4),
            ),
        )
        val p = g.projection()
        assertContentEquals(intArrayOf(1), p.refs(0))
        assertContentEquals(intArrayOf(4, 6), p.refs(1))
    }

    @Test
    fun `table follows only recognized technical links and preserves other references`() {
        val g = FakeGraph(
            arrayOf(
                "java.util.HashMap" + '$' + "Node[]", "java.util.HashMap" + '$' + "Node",
                "java.util.HashMap" + '$' + "Node", "java.util.LinkedHashMap" + '$' + "Entry",
                "java.util.HashMap" + '$' + "Node", "java.lang.Object",
            ),
            arrayOf(intArrayOf(1, 5), intArrayOf(2, 4), intArrayOf(3), intArrayOf(1), intArrayOf(), intArrayOf()),
            arrayOf(
                emptyList(),
                listOf("next" to 2, "key" to 4),
                listOf("next" to 3),
                listOf("before" to 1),
                listOf("next" to 4),
                emptyList(),
            ),
        )
        assertEquals(setOf(5, 1, 2, 3), g.projection().refs(0).toSet())
    }

    @Test
    fun `immutable map keeps entries and drops views and bucket links`() {
        val g = FakeGraph(
            arrayOf(
                "com.google.common.collect.RegularImmutableMap",
                "java.util.Map" + '$' + "Entry[]",
                "com.google.common.collect.ImmutableMapEntry" + '$' + "NonTerminalImmutableMapEntry",
                "com.google.common.collect.ImmutableMapEntry",
            ),
            arrayOf(intArrayOf(1, 2, 3), intArrayOf(2, 3), intArrayOf(3, 3), intArrayOf()),
            arrayOf(
                listOf("entries" to 1, "table" to 2, "entrySet" to 3),
                emptyList(),
                listOf("nextInKeyBucket" to 3, "key" to 3, "value" to 3),
                emptyList(),
            ),
        )
        assertContentEquals(intArrayOf(1), g.projection().refs(0))
        assertEquals(setOf(3), g.projection().refs(2).toSet())
    }

    @Test
    fun `concurrent hash map drops node chains but keeps key and value`() {
        val g = FakeGraph(
            arrayOf(
                "java.util.concurrent.ConcurrentHashMap" + '$' + "Node",
            ),
            arrayOf(intArrayOf(7, 8, 9)),
            arrayOf(listOf("key" to 7, "next" to 8, "val" to 9)),
        )
        assertContentEquals(intArrayOf(7, 9), g.projection().refs(0))
    }

    @Test
    fun `immutable views are representation-only`() {
        val g = FakeGraph(
            arrayOf(
                "com.google.common.collect.RegularImmutableMap" + '$' + "KeySet",
                "com.google.common.collect.RegularImmutableMap",
            ),
            arrayOf(intArrayOf(1, 1), intArrayOf()),
            arrayOf(listOf("map" to 1, "asList" to 1), emptyList()),
        )
        assertContentEquals(IntArray(0), g.projection().refs(0))
    }

    @Test
    fun `immutable attributes container keeps hierarchy but drops duplicate indexes`() {
        val g = FakeGraph(
            arrayOf("org.gradle.api.internal.attributes.DefaultImmutableAttributesContainer"),
            arrayOf(intArrayOf(1, 2, 3, 4)),
            arrayOf(listOf("head" to 1, "hierarchy" to 2, "hierarchyByName" to 3, "singleEntry" to 4)),
        )
        assertContentEquals(intArrayOf(2), g.projection().refs(0))
    }

    @Test
    fun `immutable attribute entry drops coercion cache but keeps attribute and value`() {
        val g = FakeGraph(
            arrayOf("org.gradle.api.internal.attributes.DefaultImmutableAttributesEntry"),
            arrayOf(intArrayOf(1, 2, 3)),
            arrayOf(listOf("coercionCache" to 1, "attribute" to 2, "value" to 3)),
        )
        assertContentEquals(intArrayOf(2, 3), g.projection().refs(0))
    }

    @Test
    fun `cycles terminate and duplicate buckets become one member`() {
        val g = FakeGraph(
            arrayOf("java.util.HashMap" + '$' + "Node[]", "java.util.HashMap" + '$' + "Node", "java.util.HashMap" + '$' + "Node"),
            arrayOf(intArrayOf(1, 1), intArrayOf(2), intArrayOf(1)),
            arrayOf(emptyList(), listOf("next" to 2), listOf("next" to 1)),
        )
        assertEquals(setOf(1, 2), g.projection().refs(0).toSet())
    }

    @Test
    fun `unrecognized entries and ordinary objects pass through without field reads`() {
        val raw = intArrayOf(1, 2)
        val g = FakeGraph(
            arrayOf("java.util.HashMap" + '$' + "TreeNode", "custom.Entry", "ordinary"),
            arrayOf(raw, intArrayOf(2), intArrayOf(1)),
            arrayOf(listOf("next" to 2), listOf("next" to 2), emptyList()),
        )
        val p = g.projection()
        assertSame(raw, p.refs(0))
        assertSame(g.indexed[2], p.refs(2))
        assertEquals(0, g.namedReads)
    }

    @Test
    fun `already filtered references do not trigger a second indexed read`() {
        val g = FakeGraph(
            arrayOf("ordinary"),
            arrayOf(intArrayOf(9)),
            arrayOf(emptyList()),
        )
        val filtered = intArrayOf(1, 2)
        assertSame(filtered, g.projection().refs(0, filtered))
        assertEquals(0, g.indexedReads)
    }

    @Test
    fun `large table has no traversal cap`() {
        val n = 129
        val classes = Array(n + 1) { if (it == 0) "java.util.HashMap" + '$' + "Node[]" else "java.util.HashMap" + '$' + "Node" }
        val indexed = Array(n + 1) { IntArray(0) }
        val named = Array(n + 1) { emptyList<Pair<String, Int>>() }
        for (i in 1 until n) {
            indexed[i] = if (i + 1 < n) intArrayOf(i + 1) else IntArray(0)
            named[i] = if (i + 1 < n) listOf("next" to (i + 1)) else emptyList()
        }
        indexed[0] = (1 until n step 2).toList().toIntArray()
        val result = MapProjection({ classes[it] }, { indexed[it] }, { named[it] }).refs(0)
        assertEquals(n - 1, result.toSet().size)
        assertEquals((1 until n).toSet(), result.toSet())
    }
}
