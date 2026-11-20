package shapes.verification

import shapes.analysis.AnalysisScope
import shapes.analysis.ShapesAnalyzer
import shapes.graph.ClassName
import shapes.graph.ObjectGraph
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ShapeEdgeAccountingVerifierTest {
    @Test
    fun `merged non-uniform SCC emits exact per-instance edge totals`() {
        val graph = ObjectGraph.of(
            classes = listOf("A", "B", "X", "A", "B", "X")
                .map(::ClassName)
                .toTypedArray(),
            shallow = ULongArray(6) { 5U },
            refs = arrayOf(
                intArrayOf(1, 2),
                intArrayOf(0),
                intArrayOf(),
                intArrayOf(4, 5),
                intArrayOf(3),
                intArrayOf(),
            ),
        )
        val analyzer = ShapesAnalyzer(graph)
        val debug = analyzer.analyzeDebug(AnalysisScope.ReachableFrom(intArrayOf(0, 3)))

        val scc = debug.result.entries.single { it.members != null }
        assertEquals(4, scc.instanceCount)
        assertEquals(2, scc.sccInstanceSize)
        assertFalse(scc.edgesPerMember)
        assertEquals(1, debug.result.edges.single().multiplicity)

        val violations = ShapeEdgeAccountingVerifier.verify(
            graph,
            debug,
        )
        assertTrue(violations.isEmpty(), violations.joinToString("\n"))
    }
}
