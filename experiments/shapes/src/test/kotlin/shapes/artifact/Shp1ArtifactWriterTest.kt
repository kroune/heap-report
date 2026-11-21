package shapes.artifact

import shapes.analysis.AnalysisScope
import shapes.analysis.ShapeKind
import shapes.analysis.ShapesAnalyzer
import shapes.graph.ClassName
import shapes.graph.ObjectGraph
import java.io.DataInputStream
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

class Shp1ArtifactWriterTest {
    @Test
    fun `writes shape table roots histogram and canonical SCC members`() {
        val graph = ObjectGraph.of(
            classes = arrayOf(ClassName("A"), ClassName("B")),
            shallow = ulongArrayOf(5U, 5U),
            refs = arrayOf(intArrayOf(1), intArrayOf(0)),
        )
        val result = ShapesAnalyzer(graph).analyze(
            AnalysisScope.ReachableFrom(intArrayOf(0))
        )
        val path = Files.createTempFile("shapes-artifact-", ".shp1")

        try {
            Shp1ArtifactWriter(path).write(result)
            DataInputStream(Files.newInputStream(path).buffered()).use { input ->
                assertEquals("SHP1", input.readMagic())
                assertEquals(listOf("scc[A,B]", "A", "B"), input.readLabels())

                assertEquals(1, input.readInt())
                val entry = result.entries.single()
                assertEquals(entry.digest.mostSignificantBits, input.readLong())
                assertEquals(entry.digest.leastSignificantBits, input.readLong())
                assertEquals(ShapeKind.SCC.ordinal, input.readUnsignedByte())
                assertEquals(0, input.readInt())
                assertEquals(2, input.readLong())
                assertEquals(10, input.readLong())
                assertEquals(5, input.readLong())
                assertEquals(5, input.readLong())
                assertEquals(1, input.readLong())
                assertEquals(2, input.readInt())
                assertEquals(2, input.readInt())
                assertEquals(0, input.readUnsignedByte())

                assertEquals(0, input.readInt())
                assertEquals(1, input.readInt())
                assertEquals(0, input.readInt())
                assertEquals(1, input.readLong())

                assertEquals("SCC2", input.readMagic())
                assertEquals(1, input.readInt())
                assertEquals(0, input.readInt())
                assertEquals(2, input.readInt())
                assertEquals(1, input.readUnsignedByte())

                assertEquals(1, input.readInt())  // label "A"
                assertEquals(1, input.readInt())  // group member count
                assertEquals(1, input.readInt())  // connections
                assertEquals(1, input.readInt())  // target group
                assertEquals(1, input.readInt())  // multiplicity

                assertEquals(2, input.readInt())  // label "B"
                assertEquals(1, input.readInt())  // group member count
                assertEquals(1, input.readInt())  // connections
                assertEquals(0, input.readInt())  // target group
                assertEquals(1, input.readInt())  // multiplicity
                assertEquals(-1, input.read())
            }
        } finally {
            Files.deleteIfExists(path)
        }
    }

    private fun DataInputStream.readMagic(): String =
        readNBytes(4).toString(Charsets.US_ASCII)

    private fun DataInputStream.readLabels(): List<String> {
        val count = readInt()
        return List(count) {
            val bytes = readNBytes(readInt())
            bytes.toString(Charsets.UTF_8)
        }
    }
}
