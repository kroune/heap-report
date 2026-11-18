package shapes.extraction

import shapes.graph.ClassName
import shapes.input.heap.NamedReference
import shapes.relaxation.FieldConcealmentRules
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertSame

class HeapReferenceFilterTest {
    private class Fixture(referenceObject: Boolean = false) {
        val className = if (referenceObject) "java.lang.ref.Reference" else "custom.Type"
        val indexed = intArrayOf(1, 2, 3, 4)
        val named = listOf(
            NamedReference("referent", 1),
            NamedReference("value", 2),
            NamedReference("lazy", 3),
            NamedReference("discovered", 4),
        )
        val heap = FakeHeapObjectSource(
            arrayOf(className),
            longArrayOf(16),
            arrayOf(indexed),
            arrayOf(named),
        )
    }

    @Test
    fun `ordinary object keeps indexed fast path`() {
        val fixture = Fixture()
        val filter = referenceFilterFor(fixture.heap)

        assertSame(fixture.indexed, filter.references(0))
        assertEquals(0, fixture.heap.namedReferenceReads)
    }

    @Test
    fun `reference mechanics and subclass payload are distinguished`() {
        val fixture = Fixture(referenceObject = true)
        val filter = referenceFilterFor(fixture.heap)
        assertContentEquals(intArrayOf(2, 3), filter.references(0))
    }

    @Test
    fun `reference and conceal rules compose instead of replacing each other`() {
        val fixture = Fixture(referenceObject = true)
        val rules = FieldConcealmentRules(
            mapOf(ClassName(fixture.className) to setOf("lazy"))
        )
        val filter = HeapReferenceFilter.create(fixture.heap, rules, true) {}

        val filtered = filter.read(0)
        assertContentEquals(intArrayOf(2), filtered.targetIds)
        assertEquals(1, filtered.concealedFieldCount)
    }

    @Test
    fun `raw reference mode still drops GC discovered linkage`() {
        val fixture = Fixture(referenceObject = true)
        val filter = HeapReferenceFilter.create(
            fixture.heap,
            FieldConcealmentRules(),
            filterReferenceMechanics = false,
        ) {}
        assertContentEquals(intArrayOf(1, 2, 3), filter.references(0))
    }

    @Test
    fun `reference filtering resolves only retained targets`() {
        val heap = FakeHeapObjectSource(
            arrayOf("java.lang.ref.Reference"),
            longArrayOf(16),
            arrayOf(intArrayOf(1, 2, 3)),
            arrayOf(
                listOf(
                    NamedReference("referent", -1, 1),
                    NamedReference("value", -1, 2),
                    NamedReference("discovered", -1, 3),
                )
            ),
        )
        val filter = referenceFilterFor(heap)

        assertContentEquals(intArrayOf(2), filter.references(0))
        assertEquals(1, heap.addressResolutionReads)
    }

    @Test
    fun `reads are pure and named projection view has identical filters`() {
        val fixture = Fixture(referenceObject = true)
        val rules = FieldConcealmentRules(
            mapOf(ClassName(fixture.className) to setOf("lazy"))
        )
        val filter = HeapReferenceFilter.create(fixture.heap, rules, true) {}

        repeat(3) {
            assertEquals(1, filter.read(0).concealedFieldCount)
            assertContentEquals(intArrayOf(2), filter.references(0))
            assertEquals(listOf(NamedReference("value", 2)), filter.namedReferences(0))
        }
    }
}
