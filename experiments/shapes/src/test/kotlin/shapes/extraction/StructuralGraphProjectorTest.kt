package shapes.extraction

import shapes.input.heap.NamedReference
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertSame

class StructuralGraphProjectorTest {
    private class Fixture(
        classes: Array<String>,
        indexedReferences: Array<IntArray>,
        namedReferences: Array<List<NamedReference>>,
    ) {
        val heap = FakeHeapObjectSource(
            classes,
            LongArray(classes.size) { 16 },
            indexedReferences,
            namedReferences,
        )

        fun projector() = StructuralGraphProjector(heap, referenceFilterFor(heap))
    }

    @Test
    fun `named links are removed but aliases through key or value survive`() {
        val fixture = Fixture(
            arrayOf("java.util.HashMap" + '$' + "Node"),
            arrayOf(intArrayOf(7, 8)),
            arrayOf(fields("key" to 7, "next" to 7, "value" to 8)),
        )
        assertContentEquals(intArrayOf(7, 8), fixture.projector().projectReferences(0))
    }

    @Test
    fun `linked map and entry remove only their technical named links`() {
        val fixture = Fixture(
            arrayOf("java.util.LinkedHashMap", "java.util.LinkedHashMap" + '$' + "Entry"),
            arrayOf(intArrayOf(1, 2, 1), intArrayOf(4, 5, 4, 6)),
            arrayOf(
                fields("head" to 1, "tail" to 2, "size" to 1),
                fields("next" to 4, "before" to 5, "after" to 4, "key" to 6, "value" to 4),
            ),
        )
        val projector = fixture.projector()
        assertContentEquals(intArrayOf(1), projector.projectReferences(0))
        assertContentEquals(intArrayOf(4, 6), projector.projectReferences(1))
    }

    @Test
    fun `table follows only recognized technical links and preserves other references`() {
        val fixture = Fixture(
            arrayOf(
                "java.util.HashMap" + '$' + "Node[]",
                "java.util.HashMap" + '$' + "Node",
                "java.util.HashMap" + '$' + "Node",
                "java.util.LinkedHashMap" + '$' + "Entry",
                "java.util.HashMap" + '$' + "Node",
                "java.lang.Object",
            ),
            arrayOf(
                intArrayOf(1, 5),
                intArrayOf(2, 4),
                intArrayOf(3),
                intArrayOf(1),
                intArrayOf(),
                intArrayOf(),
            ),
            arrayOf(
                emptyList(),
                fields("next" to 2, "key" to 4),
                fields("next" to 3),
                fields("before" to 1),
                fields("next" to 4),
                emptyList(),
            ),
        )
        assertEquals(setOf(5, 1, 2, 3), fixture.projector().projectReferences(0).toSet())
    }

    @Test
    fun `immutable map keeps entries and drops views and bucket links`() {
        val fixture = Fixture(
            arrayOf(
                "com.google.common.collect.RegularImmutableMap",
                "java.util.Map" + '$' + "Entry[]",
                "com.google.common.collect.ImmutableMapEntry" + '$' + "NonTerminalImmutableMapEntry",
                "com.google.common.collect.ImmutableMapEntry",
            ),
            arrayOf(intArrayOf(1, 2, 3), intArrayOf(2, 3), intArrayOf(3, 3), intArrayOf()),
            arrayOf(
                fields("entries" to 1, "table" to 2, "entrySet" to 3),
                emptyList(),
                fields("nextInKeyBucket" to 3, "key" to 3, "value" to 3),
                emptyList(),
            ),
        )
        val projector = fixture.projector()
        assertContentEquals(intArrayOf(1), projector.projectReferences(0))
        assertEquals(setOf(3), projector.projectReferences(2).toSet())
    }

    @Test
    fun `concurrent hash map drops node chains but keeps key and value`() {
        val fixture = Fixture(
            arrayOf("java.util.concurrent.ConcurrentHashMap" + '$' + "Node"),
            arrayOf(intArrayOf(7, 8, 9)),
            arrayOf(fields("key" to 7, "next" to 8, "val" to 9)),
        )
        assertContentEquals(intArrayOf(7, 9), fixture.projector().projectReferences(0))
    }

    @Test
    fun `immutable views are representation-only`() {
        val fixture = Fixture(
            arrayOf(
                "com.google.common.collect.RegularImmutableMap" + '$' + "KeySet",
                "com.google.common.collect.RegularImmutableMap",
            ),
            arrayOf(intArrayOf(1, 1), intArrayOf()),
            arrayOf(fields("map" to 1, "asList" to 1), emptyList()),
        )
        assertContentEquals(IntArray(0), fixture.projector().projectReferences(0))
    }

    @Test
    fun `immutable attributes container keeps hierarchy but drops duplicate indexes`() {
        val fixture = Fixture(
            arrayOf("org.gradle.api.internal.attributes.DefaultImmutableAttributesContainer"),
            arrayOf(intArrayOf(1, 2, 3, 4)),
            arrayOf(fields("head" to 1, "hierarchy" to 2, "hierarchyByName" to 3, "singleEntry" to 4)),
        )
        assertContentEquals(intArrayOf(2), fixture.projector().projectReferences(0))
    }

    @Test
    fun `immutable attribute entry drops coercion cache but keeps attribute and value`() {
        val fixture = Fixture(
            arrayOf("org.gradle.api.internal.attributes.DefaultImmutableAttributesEntry"),
            arrayOf(intArrayOf(1, 2, 3)),
            arrayOf(fields("coercionCache" to 1, "attribute" to 2, "value" to 3)),
        )
        assertContentEquals(intArrayOf(2, 3), fixture.projector().projectReferences(0))
    }

    @Test
    fun `cycles terminate and duplicate buckets become one member`() {
        val fixture = Fixture(
            arrayOf(
                "java.util.HashMap" + '$' + "Node[]",
                "java.util.HashMap" + '$' + "Node",
                "java.util.HashMap" + '$' + "Node",
            ),
            arrayOf(intArrayOf(1, 1), intArrayOf(2), intArrayOf(1)),
            arrayOf(emptyList(), fields("next" to 2), fields("next" to 1)),
        )
        assertEquals(setOf(1, 2), fixture.projector().projectReferences(0).toSet())
    }

    @Test
    fun `unrecognized entries and ordinary objects pass through without field reads`() {
        val raw = intArrayOf(1, 2)
        val fixture = Fixture(
            arrayOf("java.util.HashMap" + '$' + "TreeNode", "custom.Entry", "ordinary"),
            arrayOf(raw, intArrayOf(2), raw),
            arrayOf(fields("next" to 1), fields("next" to 2), emptyList()),
        )
        val projector = fixture.projector()

        assertSame(raw, projector.projectReferences(0))
        assertSame(raw, projector.projectReferences(2))
        assertEquals(0, fixture.heap.namedReferenceReads)
    }

    @Test
    fun `already filtered references do not trigger a second indexed read`() {
        val fixture = Fixture(
            arrayOf("ordinary"),
            arrayOf(intArrayOf(9, 2)),
            arrayOf(emptyList()),
        )
        val filtered = intArrayOf(1, 2)
        assertSame(filtered, fixture.projector().projectReferences(0, filtered))
        assertEquals(0, fixture.heap.referenceReads[0])
    }

    @Test
    fun `only removed addresses are resolved after projection`() {
        val fixture = Fixture(
            arrayOf("java.util.HashMap" + '$' + "Node"),
            arrayOf(intArrayOf(7, 8, 9)),
            arrayOf(
                listOf(
                    NamedReference("key", -1, 7),
                    NamedReference("next", -1, 8),
                    NamedReference("value", -1, 9),
                )
            ),
        )

        assertContentEquals(intArrayOf(7, 9), fixture.projector().projectReferences(0))
        assertEquals(1, fixture.heap.addressResolutionReads)
    }

    @Test
    fun `retained-only projection resolves only retained addresses`() {
        val fixture = Fixture(
            arrayOf("com.google.common.collect.RegularImmutableMap"),
            arrayOf(intArrayOf(1, 2, 3)),
            arrayOf(
                listOf(
                    NamedReference("entries", -1, 1),
                    NamedReference("table", -1, 2),
                    NamedReference("entrySet", -1, 3),
                )
            ),
        )

        assertContentEquals(intArrayOf(1), fixture.projector().projectReferences(0))
        assertEquals(1, fixture.heap.addressResolutionReads)
    }

    @Test
    fun `table traversal resolves only newly discovered members`() {
        val fixture = Fixture(
            arrayOf(
                "java.util.HashMap" + '$' + "Node[]",
                "java.util.HashMap" + '$' + "Node",
                "java.util.HashMap" + '$' + "Node",
            ),
            arrayOf(intArrayOf(1), intArrayOf(2), intArrayOf(1)),
            arrayOf(
                emptyList(),
                listOf(NamedReference("next", -1, 2)),
                listOf(NamedReference("next", -1, 1)),
            ),
        )

        assertEquals(setOf(1, 2), fixture.projector().projectReferences(0).toSet())
        assertEquals(1, fixture.heap.addressResolutionReads)
    }

    @Test
    fun `large table has no traversal cap`() {
        val entryCount = 129
        val classes = Array(entryCount + 1) { index ->
            if (index == 0) "java.util.HashMap" + '$' + "Node[]"
            else "java.util.HashMap" + '$' + "Node"
        }
        val indexed = Array(entryCount + 1) { IntArray(0) }
        val named = Array(entryCount + 1) { emptyList<NamedReference>() }
        for (index in 1 until entryCount) {
            indexed[index] = if (index + 1 < entryCount) intArrayOf(index + 1) else IntArray(0)
            named[index] = if (index + 1 < entryCount) fields("next" to index + 1) else emptyList()
        }
        indexed[0] = (1 until entryCount step 2).toList().toIntArray()
        val fixture = Fixture(classes, indexed, named)

        val result = fixture.projector().projectReferences(0)
        assertEquals(entryCount - 1, result.toSet().size)
        assertEquals((1 until entryCount).toSet(), result.toSet())
    }

    private fun fields(vararg references: Pair<String, Int>): List<NamedReference> =
        references.map { NamedReference(it.first, it.second) }
}
