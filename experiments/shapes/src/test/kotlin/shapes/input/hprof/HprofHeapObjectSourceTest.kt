package shapes.input.hprof

import shapes.extraction.HeapExtractionScope
import shapes.extraction.HeapObjectGraphExtractor
import shapes.graph.ClassName
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HprofHeapObjectSourceTest {
    @Test
    fun `reads objects fields arrays classes and shallow sizes directly`() {
        val directory = Files.createTempDirectory("hprof-source-test")
        val hprof = directory.resolve("fixture.hprof")
        writeTestHprof(hprof)

        HprofHeapObjectSource.open(hprof).use { heap ->
            assertEquals(9, heap.objectCount)
            assertEquals(ClassName("example.Node"), heap.className(5))
            assertEquals(2, heap.classId(5))
            assertEquals(10L, heap.objectAddress(5))
            assertEquals(5, heap.resolveObjectAddress(10L))
            assertEquals(-1, heap.resolveObjectAddress(999L))
            assertEquals(32.toULong(), heap.shallowSize(5))
            assertEquals(40.toULong(), heap.shallowSize(7))
            assertEquals(32.toULong(), heap.shallowSize(8))
            assertEquals(16.toULong(), heap.shallowSize(0))
            assertContentEquals(intArrayOf(6, 6), heap.indexedReferences(5))
            assertContentEquals(intArrayOf(5, 5), heap.indexedReferences(6))
            assertContentEquals(intArrayOf(5, 6), heap.indexedReferences(7))
            assertContentEquals(intArrayOf(), heap.indexedReferences(8))
            assertEquals(listOf("next", "baseRef"), heap.namedReferences(5).map { it.fieldName })
            assertTrue(heap.isClassObject(0))
            assertFalse(heap.isClassObject(5))

            val nodeClasses = heap.classesNamed(ClassName("example.Node"))
            assertEquals(1, nodeClasses.size)
            assertContentEquals(intArrayOf(5, 6), nodeClasses.single().instanceIds)
            val baseClasses = heap.classesNamed(ClassName("example.Base"), includeSubclasses = true)
            assertEquals(2, baseClasses.size)
            assertContentEquals(intArrayOf(), baseClasses[0].instanceIds)
            assertContentEquals(intArrayOf(5, 6), baseClasses[1].instanceIds)
            assertEquals(5, heap.classesNamed(ClassName("java.lang.Class")).single().instanceIds.size)
        }

        // The persistent cache is read-only on the second open and preserves all source ids.
        HprofHeapObjectSource.open(hprof).use { reopened ->
            assertEquals(9, reopened.objectCount)
            assertContentEquals(intArrayOf(6, 6), reopened.indexedReferences(5))
        }
    }

    @Test
    fun `matches MAT shallow sizes when compressed references are detected`() {
        val directory = Files.createTempDirectory("hprof-compressed-ref-test")
        val hprof = directory.resolve("fixture.hprof")
        writeTestHprof(hprof, compressedReferenceDetection = true)

        HprofHeapObjectSource.open(hprof).use { heap ->
            assertEquals(24.toULong(), heap.shallowSize(7))
            assertEquals(24.toULong(), heap.shallowSize(8))
            assertEquals(16.toULong(), heap.shallowSize(9))
        }
    }

    @Test
    fun `supports heap records that are not ordered by address`() {
        val directory = Files.createTempDirectory("hprof-unsorted-test")
        val hprof = directory.resolve("fixture.hprof")
        writeTestHprof(hprof, reverseInstances = true)

        HprofHeapObjectSource.open(hprof).use { heap ->
            assertEquals(6, heap.resolveObjectAddress(10L))
            assertEquals(5, heap.resolveObjectAddress(11L))
            assertEquals(11L, heap.objectAddress(5))
            assertEquals(10L, heap.objectAddress(6))
            assertContentEquals(intArrayOf(5, 5), heap.indexedReferences(6))
            assertContentEquals(intArrayOf(6, 6), heap.indexedReferences(5))
            assertContentEquals(intArrayOf(6, 5), heap.indexedReferences(7))
        }
    }

    @Test
    fun `filters GC-unreachable objects like MAT`() {
        val directory = Files.createTempDirectory("hprof-liveness-test")
        val hprof = directory.resolve("fixture.hprof")
        writeTestHprof(hprof, keepAllLive = false)

        val result = HprofHeapObjectSource.open(hprof).use { heap ->
            assertTrue(heap.isObjectLive(5))
            assertTrue(heap.isObjectLive(6))
            assertFalse(heap.isObjectLive(7))
            assertFalse(heap.isObjectLive(8))
            assertContentEquals(
                intArrayOf(5, 6),
                heap.classesNamed(ClassName("example.Node")).single().instanceIds,
            )
            assertContentEquals(
                intArrayOf(7),
                heap.classesNamedIncludingUnreachable(ClassName("example.Node[]")).single().instanceIds,
            )
            HeapObjectGraphExtractor(heap).extract(HeapExtractionScope.WholeHeap)
        }
        assertEquals(2, result.graph.size)
        assertEquals(4, result.graph.referenceCount)
    }

    @Test
    fun `works with the unchanged graph extractor`() {
        val directory = Files.createTempDirectory("hprof-extractor-test")
        val hprof = directory.resolve("fixture.hprof")
        writeTestHprof(hprof)

        val result = HprofHeapObjectSource.open(hprof).use { heap ->
            HeapObjectGraphExtractor(heap).extract(HeapExtractionScope.WholeHeap)
        }
        assertEquals(4, result.graph.size)
        assertEquals(6, result.graph.referenceCount)
        assertContentEquals(intArrayOf(0, 1), result.graph.instancesOf(ClassName("example.Node")))
    }
}
