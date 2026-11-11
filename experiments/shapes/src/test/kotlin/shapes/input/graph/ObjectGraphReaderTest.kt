package shapes.input.graph

import shapes.graph.ClassName
import shapes.graph.ObjectGraph
import java.io.DataOutputStream
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class ObjectGraphReaderTest {
    @Test
    fun `text output and input roundtrip dense ids and multi-edges`() {
        val original = ObjectGraph.of(
            classes = arrayOf(ClassName("A"), ClassName("B")),
            shallow = ulongArrayOf(16UL, 24UL),
            refs = arrayOf(intArrayOf(1, 1), intArrayOf()),
        )
        val path = Files.createTempFile("shapes-text-", ".txt")
        try {
            TextObjectGraphWriter(path).write(original)
            val restored = TextObjectGraphReader(path).read()

            assertEquals(2, restored.size)
            assertEquals(ClassName("A"), restored.className(0))
            assertEquals(24UL, restored.shallowSize(1))
            assertContentEquals(intArrayOf(1, 1), restored.referenceIds(0))
        } finally {
            Files.deleteIfExists(path)
        }
    }

    @Test
    fun `text input orders arbitrarily ordered dense rows`() {
        val path = Files.createTempFile("shapes-text-unordered-", ".txt")
        try {
            Files.writeString(path, "# comment\n1 B 24\n0 A 16 1\n")
            val graph = TextObjectGraphReader(path).read()

            assertEquals(ClassName("A"), graph.className(0))
            assertEquals(ClassName("B"), graph.className(1))
            assertContentEquals(intArrayOf(1), graph.referenceIds(0))
        } finally {
            Files.deleteIfExists(path)
        }
    }

    @Test
    fun `GRPH is an independent serialized input source`() {
        val path = Files.createTempFile("shapes-input-", ".grph")
        try {
            DataOutputStream(Files.newOutputStream(path)).use { output ->
                output.writeInt(0x47525048)
                output.writeLong(10)
                output.writeInt(2)
                for (name in listOf("A", "B")) {
                    val bytes = name.toByteArray()
                    output.writeInt(bytes.size)
                    output.write(bytes)
                }
                output.writeInt(2)
                output.writeInt(0)
                output.writeLong(16)
                output.writeInt(2)
                output.writeInt(1)
                output.writeInt(1)
                output.writeInt(1)
                output.writeLong(24)
                output.writeInt(0)
            }
            val graph = GrphObjectGraphReader(path).read()

            assertEquals(10, graph.sourceObjectCount)
            assertEquals(2, graph.size)
            assertContentEquals(intArrayOf(1, 1), graph.referenceIds(0))
        } finally {
            Files.deleteIfExists(path)
        }
    }
}

internal fun ObjectGraph.referenceIds(objectId: Int): IntArray {
    val result = IntArray(referenceCount(objectId))
    var index = 0
    forEachReference(objectId) { targetId -> result[index++] = targetId }
    return result
}
