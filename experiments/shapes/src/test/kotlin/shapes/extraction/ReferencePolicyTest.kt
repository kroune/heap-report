package shapes.extraction

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertSame

class ReferencePolicyTest {
    private class Fixture(
        private val reference: Boolean = false,
        private val concealed: Set<String> = emptySet(),
    ) {
        val indexed = intArrayOf(1, 2, 3, 4)
        val named = listOf("referent" to 1, "value" to 2, "lazy" to 3, "discovered" to 4)
        var namedReads = 0

        fun policy(filterReferences: Boolean = true) = ReferencePolicy(
            { indexed },
            { namedReads++; named },
            { reference },
            { concealed },
            filterReferences,
        )
    }

    @Test
    fun `ordinary object keeps indexed fast path`() {
        val fixture = Fixture()
        val policy = fixture.policy()
        assertSame(fixture.indexed, policy.refs(0))
        assertEquals(0, fixture.namedReads)
    }

    @Test
    fun `reference mechanics and subclass payload are distinguished`() {
        val fixture = Fixture(reference = true)
        assertContentEquals(intArrayOf(2, 3), fixture.policy().refs(0))
    }

    @Test
    fun `reference and conceal rules compose instead of replacing each other`() {
        val fixture = Fixture(reference = true, concealed = setOf("lazy"))
        val read = fixture.policy().read(0)
        assertContentEquals(intArrayOf(2), read.refs)
        assertEquals(1, read.concealedFieldEdges)
    }

    @Test
    fun `raw reference mode still drops GC discovered linkage`() {
        val fixture = Fixture(reference = true)
        assertContentEquals(intArrayOf(1, 2, 3), fixture.policy(filterReferences = false).refs(0))
    }

    @Test
    fun `reads are pure and named projection view has identical filters`() {
        val fixture = Fixture(reference = true, concealed = setOf("lazy"))
        val policy = fixture.policy()
        repeat(3) {
            assertEquals(1, policy.read(0).concealedFieldEdges)
            assertContentEquals(intArrayOf(2), policy.refs(0))
            assertEquals(listOf("value" to 2), policy.namedRefs(0))
        }
    }
}
