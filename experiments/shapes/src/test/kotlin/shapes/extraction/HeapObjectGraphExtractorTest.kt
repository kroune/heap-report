package shapes.extraction

import shapes.graph.ClassName
import shapes.input.graph.referenceIds
import shapes.input.heap.NamedReference
import shapes.relaxation.FieldConcealmentRules
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class HeapObjectGraphExtractorTest {
    @Test
    fun `whole heap materializes in one pass without a class prepass`() {
        val heap = FakeHeapObjectSource(
            classes = arrayOf("A", "B", "C"),
            shallowSizes = longArrayOf(16, 24, 32),
            references = arrayOf(intArrayOf(1, 1, 2), intArrayOf(2), intArrayOf()),
        )

        val result = HeapObjectGraphExtractor(heap).extract(HeapExtractionScope.WholeHeap)
        val graph = result.graph

        assertEquals(3, graph.size)
        assertEquals(4, graph.referenceCount)
        assertEquals(
            listOf(ClassName("A"), ClassName("B"), ClassName("C")),
            graph.classNames,
        )
        assertContentEquals(intArrayOf(1, 1, 2), graph.referenceIds(0))
        assertContentEquals(intArrayOf(2), graph.referenceIds(1))
        assertContentEquals(intArrayOf(1, 1, 1), heap.referenceReads)
        assertContentEquals(intArrayOf(1, 1, 1), heap.shallowSizeReads)
        assertEquals(4, result.stats.sourceReferenceCount)
        assertEquals(4, result.stats.projectedReferenceCount)
        assertEquals(72, result.stats.totalShallowSize)
    }

    @Test
    fun `dense remap skips excluded class objects`() {
        val heap = FakeHeapObjectSource(
            classes = arrayOf("A", "java.lang.Class", "B"),
            shallowSizes = longArrayOf(16, 24, 32),
            references = arrayOf(intArrayOf(2), intArrayOf(0), intArrayOf()),
            classObjects = setOf(1),
        )

        val graph = HeapObjectGraphExtractor(heap).extract(HeapExtractionScope.WholeHeap).graph

        assertEquals(2, graph.size)
        assertContentEquals(intArrayOf(1), graph.referenceIds(0))
        assertContentEquals(intArrayOf(1, 0, 1), heap.referenceReads)
    }

    @Test
    fun `boundary stubs are registered during the same materialization pass`() {
        val heap = FakeHeapObjectSource(
            classes = arrayOf("A", "B", "B"),
            shallowSizes = longArrayOf(16, 24, 24),
            references = arrayOf(intArrayOf(1, 2), intArrayOf(), intArrayOf()),
            retained = intArrayOf(0),
        )
        val options = HeapExtractionOptions(boundary = ExtractionBoundary.CreateClassStubs)

        val result = HeapObjectGraphExtractor(heap, options).extract(
            HeapExtractionScope.RetainedByClass(ClassName("A"))
        )
        val graph = result.graph

        assertEquals(2, graph.size)
        assertEquals(ClassName("A"), graph.className(0))
        assertEquals(ClassName("B"), graph.className(1))
        assertContentEquals(intArrayOf(1, 1), graph.referenceIds(0))
        assertEquals(0, graph.referenceCount(1))
        assertEquals(0UL, graph.shallowSize(1))
        assertEquals(1, result.stats.selectedObjectCount)
        assertEquals(2, result.stats.scopeBoundaryReferenceCount)
        assertEquals(2, result.stats.projectedReferenceCount)
    }

    @Test
    fun `concealed fields affect retained scope and materialization consistently`() {
        val heap = FakeHeapObjectSource(
            classes = arrayOf("A", "B", "C"),
            shallowSizes = longArrayOf(16, 24, 32),
            references = arrayOf(intArrayOf(1, 2), intArrayOf(), intArrayOf()),
            namedReferences = arrayOf(
                listOf(NamedReference("value", 1), NamedReference("lazy", 2)),
                emptyList(),
                emptyList(),
            ),
            retained = intArrayOf(0, 1, 2),
        )
        val rules = FieldConcealmentRules(mapOf(ClassName("A") to setOf("lazy")))

        val result = HeapObjectGraphExtractor(
            heap,
            fieldConcealmentRules = rules,
        ).extract(HeapExtractionScope.RetainedByClass(ClassName("A")))
        val graph = result.graph

        assertEquals(2, graph.size)
        assertContentEquals(intArrayOf(1), graph.referenceIds(0))
        assertEquals(1, result.stats.concealedFieldReferenceCount)
        assertEquals(1, result.stats.scopeFilteredOutObjectCount)
        assertEquals(32, result.stats.scopeFilteredOutShallowSize)
        assertEquals(3, result.stats.sourceRetainedObjectCount)
    }
}
