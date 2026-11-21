package shapes.verification

import shapes.analysis.Analysis
import shapes.analysis.analyzeShapes
import shapes.graph.ClassName
import shapes.graph.ObjectGraph
import shapes.relaxation.ShapeRelaxationRules
import shapes.relaxation.StructuralRelaxation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ShapeEdgeAccountingVerifierTest {
    private fun verify(analysis: Analysis): List<String> = ShapeEdgeAccountingVerifier.verify(
        analysis.graph,
        analysis.components,
        analysis.relaxations,
        analysis.shapes,
        analysis.result,
    )
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
        val analysis = graph.select(intArrayOf(0, 3)).analyzeShapes()

        val scc = analysis.result.entries.single { it.members != null }
        assertEquals(4, scc.instanceCount)
        assertEquals(2, scc.sccInstanceSize)
        assertFalse(scc.edgesPerMember)
        assertEquals(1, analysis.result.edges.single().multiplicity)

        val violations = verify(analysis)
        assertTrue(violations.isEmpty(), violations.joinToString("\n"))
    }

    @Test
    fun `class-only members keep their internal SCC edges in the accounting`() {
        // A (class-only) <-> B (exact); B also holds leaf X. A's external edges
        // are concealed, but its edge back into the cycle is part of the key
        // and must be counted as internal truth.
        val graph = ObjectGraph.of(
            classes = listOf("A", "B", "X").map(::ClassName).toTypedArray(),
            shallow = ULongArray(3) { 5U },
            refs = arrayOf(intArrayOf(1), intArrayOf(0, 2), intArrayOf()),
        )
        val analysis = graph.select(intArrayOf(0)).analyzeShapes(
            ShapeRelaxationRules(mapOf(ClassName("A") to StructuralRelaxation.CLASS_ONLY)),
        )
        val scc = analysis.result.entries.single { it.members != null }
        assertEquals(2, scc.internalEdgesPerInstance)

        val violations = verify(analysis)
        assertTrue(violations.isEmpty(), violations.joinToString("\n"))
    }

    @Test
    fun `quotient groups expand to exact internal and external totals`() {
        // Ring of three identical N members, each also holding one leaf L.
        // WL collapses them to one ×3 group; the accounting must expand the
        // group's per-member edges by its count.
        val graph = ObjectGraph.of(
            classes = listOf("N", "N", "N", "L").map(::ClassName).toTypedArray(),
            shallow = ULongArray(4) { 5U },
            refs = arrayOf(intArrayOf(1, 3), intArrayOf(2, 3), intArrayOf(0, 3), intArrayOf()),
        )
        val analysis = graph.select(intArrayOf(0)).analyzeShapes()
        val scc = analysis.result.entries.single { it.members != null }
        assertEquals(1, scc.members?.size)
        assertEquals(3, scc.members?.single()?.memberCount)
        assertEquals(3, scc.sccInstanceSize)
        assertEquals(3, scc.internalEdgesPerInstance)

        val violations = verify(analysis)
        assertTrue(violations.isEmpty(), violations.joinToString("\n"))
    }
}
