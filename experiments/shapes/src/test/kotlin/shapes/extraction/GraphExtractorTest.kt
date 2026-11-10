package shapes.extraction

import shapes.input.HeapClass
import shapes.input.HeapInput
import shapes.models.ClassName
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class GraphExtractorTest {
    @Test
    fun `whole heap materializes in one pass without a class prepass`() {
        val input = FakeHeapInput(
            classes = arrayOf("A", "B", "C"),
            shallow = longArrayOf(16, 24, 32),
            refs = arrayOf(intArrayOf(1, 1, 2), intArrayOf(2), intArrayOf()),
        )

        val result = GraphExtractor(input).extract(ExtractionMode.WholeHeap)
        val graph = result.graph

        assertEquals(3, graph.size)
        assertEquals(4, graph.referenceCount)
        assertContentEquals(arrayOf(ClassName("A"), ClassName("B"), ClassName("C")), graph.classNames)
        assertContentEquals(intArrayOf(1, 1, 2), graph.referencesList[0].toIntArray(graph))
        assertContentEquals(intArrayOf(2), graph.referencesList[1].toIntArray(graph))
        assertContentEquals(intArrayOf(1, 1, 1), input.referenceReads)
        assertContentEquals(intArrayOf(1, 1, 1), input.shallowReads)
        assertEquals(4, result.stats.sourceEdges)
        assertEquals(4, result.stats.projectedEdges)
        assertEquals(72, result.stats.shallowTotal)
    }

    @Test
    fun `dense remap skips excluded class objects`() {
        val input = FakeHeapInput(
            classes = arrayOf("A", "java.lang.Class", "B"),
            shallow = longArrayOf(16, 24, 32),
            refs = arrayOf(intArrayOf(2), intArrayOf(0), intArrayOf()),
            classObjects = setOf(1),
        )

        val graph = GraphExtractor(input).extract(ExtractionMode.WholeHeap).graph

        assertEquals(2, graph.size)
        assertContentEquals(intArrayOf(1), graph.referencesList[0].toIntArray(graph))
        assertContentEquals(intArrayOf(1, 0, 1), input.referenceReads)
    }

    @Test
    fun `boundary stubs are registered during the same materialization pass`() {
        val input = FakeHeapInput(
            classes = arrayOf("A", "B", "B"),
            shallow = longArrayOf(16, 24, 24),
            refs = arrayOf(intArrayOf(1, 2), intArrayOf(), intArrayOf()),
            retained = intArrayOf(0),
        )

        val result = GraphExtractor(input, boundaryStubs = true).extract(
            ExtractionMode.PartialExtraction.Retained(ClassName("A"))
        )
        val graph = result.graph

        assertEquals(2, graph.size)
        assertEquals(ClassName("A"), graph.className(0))
        assertEquals(ClassName("B"), graph.className(1))
        assertContentEquals(intArrayOf(1, 1), graph.referencesList[0].toIntArray(graph))
        assertEquals(0, graph.referencesList[1].size)
        assertEquals(0UL, graph.shallow[1])
        assertEquals(1, result.stats.objects)
        assertEquals(2, result.stats.scopeBoundaryEdges) // two source edges share one stub class
        assertEquals(2, result.stats.projectedEdges)
    }

    @Test
    fun `concealed fields affect retained scope and materialization consistently`() {
        val input = FakeHeapInput(
            classes = arrayOf("A", "B", "C"),
            shallow = longArrayOf(16, 24, 32),
            refs = arrayOf(intArrayOf(1, 2), intArrayOf(), intArrayOf()),
            named = arrayOf(
                listOf("value" to 1, "lazy" to 2),
                emptyList(),
                emptyList(),
            ),
            retained = intArrayOf(0, 1, 2),
        )

        val result = GraphExtractor(
            input,
            fieldRules = mapOf(ClassName("A") to setOf("lazy")),
        ).extract(ExtractionMode.PartialExtraction.Retained(ClassName("A")))
        val graph = result.graph

        assertEquals(2, graph.size)
        assertContentEquals(intArrayOf(1), graph.referencesList[0].toIntArray(graph))
        assertEquals(1, result.stats.concealedFieldEdges)
        assertEquals(1, result.stats.scopeFilteredOutObjects)
        assertEquals(32, result.stats.scopeFilteredOutShallow)
        assertEquals(3, result.stats.matRetainedObjects)
    }

    private class FakeHeapInput(
        private val classes: Array<String>,
        private val shallow: LongArray,
        private val refs: Array<IntArray>,
        private val named: Array<List<Pair<String, Int>>> = refs.map { row ->
            row.mapIndexed { index, target -> "f$index" to target }
        }.toTypedArray(),
        private val retained: IntArray = IntArray(0),
        private val classObjects: Set<Int> = emptySet(),
    ) : HeapInput {
        override val objectCount: Int get() = classes.size
        val referenceReads = IntArray(classes.size)
        val shallowReads = IntArray(classes.size)

        override fun classId(objectId: Int): Int = 1000 + classes[objectId].hashCode()
        override fun className(objectId: Int): String = classes[objectId]
        override fun shallowSize(objectId: Int): Long {
            shallowReads[objectId]++
            return shallow[objectId]
        }

        override fun indexedReferences(objectId: Int): IntArray {
            referenceReads[objectId]++
            return refs[objectId]
        }

        override fun namedReferences(objectId: Int): List<Pair<String, Int>> = named[objectId]
        override fun isClassObject(objectId: Int): Boolean = objectId in classObjects

        override fun classesNamed(name: String, includeSubclasses: Boolean): List<HeapClass> {
            val matching = classes.withIndex().filter { it.value == name }.map { it.index }
            if (matching.isEmpty()) return emptyList()
            return listOf(HeapClass(1000 + name.hashCode(), name, matching.toIntArray()))
        }

        override fun retainedSet(rootIds: IntArray): IntArray = retained
        override fun close() {}
    }
}

private fun shapes.models.ReferencesRange.toIntArray(graph: shapes.ObjectGraph): IntArray =
    IntArray(size) { offset -> graph.referencesList.getReference(this, offset) }
