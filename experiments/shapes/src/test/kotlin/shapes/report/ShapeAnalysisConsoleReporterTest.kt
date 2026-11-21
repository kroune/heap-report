package shapes.report

import shapes.analysis.analyzeShapes
import shapes.graph.ClassName
import shapes.graph.ObjectGraph
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import kotlin.test.Test
import kotlin.test.assertTrue

class ShapeAnalysisConsoleReporterTest {
    @Test
    fun `prints shape aggregates and edges without formatting errors`() {
        val graph = ObjectGraph.of(
            classes = arrayOf(ClassName("A"), ClassName("B")),
            shallow = ulongArrayOf(10U, 5U),
            refs = arrayOf(intArrayOf(1), intArrayOf()),
        )
        val result = graph.select(intArrayOf(0)).analyzeShapes().result
        val output = ByteArrayOutputStream()
        val originalOut = System.out

        try {
            System.setOut(PrintStream(output, true))
            ShapeAnalysisConsoleReporter.print(result)
        } finally {
            System.setOut(originalOut)
        }

        val report = output.toString()
        assertTrue("unique shapes" in report)
        assertTrue("count=1" in report.replace(" ", ""), report)
        assertTrue("mult=1" in report.replace(" ", ""), report)
    }
}
