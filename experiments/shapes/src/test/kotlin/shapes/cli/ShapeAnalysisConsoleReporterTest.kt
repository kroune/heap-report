package shapes.cli

import shapes.analysis.AnalysisScope
import shapes.analysis.ShapesAnalyzer
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
        val result = ShapesAnalyzer(graph).analyze(AnalysisScope.ReachableFrom(intArrayOf(0)))
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
