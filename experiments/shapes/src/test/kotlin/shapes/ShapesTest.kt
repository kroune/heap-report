package shapes

import shapes.models.ClassName
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ShapesTest {

    private fun analyze(g: ObjectGraph, roots: IntArray): ShapeTable {
        val t = ShapesAnalyzer(g).analyze(roots)
        val problems = verifyInvariants(g, roots, t)
        assertTrue(problems.isEmpty(), "invariant violations:\n" + problems.joinToString("\n"))
        return t
    }

    private fun ShapeTable.entry(label: String): ShapeEntry =
        entries.single { it.label == label }

    private fun ShapeTable.hasEdge(from: String, to: String, mult: Long): Boolean {
        val f = entry(from); val c = entry(to)
        return edges.any { it.shapeId == f.id && it.childShapeId == c.id && it.mult == mult }
    }

    @Test
    fun `identical subgraphs collapse to one shape`() {
        val b = ObjectGraph.Builder()
        val l1 = b.add(ClassName("L"), 10U)
        val l2 = b.add(ClassName("L"), 10U)
        val m1 = b.add(ClassName("M"), 20U, intArrayOf(l1))
        val m2 = b.add(ClassName("M"), 20U, intArrayOf(l2))
        val t = analyze(b.build(), intArrayOf(m1, m2))

        assertEquals(2, t.entries.size)
        assertEquals(2, t.entry("M").instanceCount)
        assertEquals(2, t.entry("L").instanceCount)
        assertEquals(mapOf(t.entry("M").id to 2L), t.rootsHistogram)
        assertTrue(t.hasEdge("M", "L", 1))
    }

    @Test
    fun `diamond keeps both edges to shared child`() {
        // A -> B -> D, A -> C -> D: B and C differ by class, D is shared (in-degree 2)
        val b = ObjectGraph.Builder()
        val d = b.add(ClassName("D"), 5U)
        val bb = b.add(ClassName("B"), 5U, intArrayOf(d))
        val c = b.add(ClassName("C"), 5U, intArrayOf(d))
        val a = b.add(ClassName("A"), 5U, intArrayOf(bb, c))
        val t = analyze(b.build(), intArrayOf(a))

        assertEquals(1, t.entry("D").instanceCount)
        assertTrue(t.hasEdge("B", "D", 1))
        assertTrue(t.hasEdge("C", "D", 1))
    }

    @Test
    fun `multi-edges are preserved`() {
        // Object[]{x, x} != Object[]{x, y} when x and y differ structurally
        val b = ObjectGraph.Builder()
        val x1 = b.add(ClassName("X"), 5U)
        val x2 = b.add(ClassName("X"), 5U)
        val y = b.add(ClassName("Y"), 5U)
        val a1 = b.add(ClassName("A"), 5U, intArrayOf(x1, x2))
        val a2 = b.add(ClassName("A"), 5U, intArrayOf(x1, y))
        val t = analyze(b.build(), intArrayOf(a1, a2))

        val aShapes = t.entries.filter { it.label == "A" }
        assertEquals(2, aShapes.size) // {X:2} and {X:1, Y:1} are different shapes
        val xId = t.entry("X").id
        val yId = t.entry("Y").id
        val ax2 = aShapes.single { e -> t.edges.any { it.shapeId == e.id && it.mult == 2L } }
        assertEquals(listOf(2L), t.edges.filter { it.shapeId == ax2.id && it.childShapeId == xId }.map { it.mult })
        assertTrue(t.edges.none { it.shapeId == ax2.id && it.childShapeId == yId })
        val axy = aShapes.single { it.id != ax2.id }
        assertTrue(t.edges.any { it.shapeId == axy.id && it.childShapeId == xId && it.mult == 1L })
        assertTrue(t.edges.any { it.shapeId == axy.id && it.childShapeId == yId && it.mult == 1L })
    }

    @Test
    fun `shallow size is not part of the key`() {
        val b = ObjectGraph.Builder()
        val small = b.add(ClassName("byte[]"), 16U)
        val big = b.add(ClassName("byte[]"), 1_000_000U)
        val t = analyze(b.build(), intArrayOf(small, big))

        val e = t.entry("byte[]")
        assertEquals(2, e.instanceCount)
        assertEquals(16U, e.shallowMin)
        assertEquals(1_000_000U, e.shallowMax)
        assertEquals(1_000_016U, e.shallowTotal)
    }

    @Test
    fun `back-pointer scc collapses (discretized by class)`() {
        // two independent parent<->child cycles: common in Gradle heaps (SHAPES.md open point 4)
        val g = ObjectGraph.of(
            classes = listOf("Child", "Parent", "Child", "Parent").map { ClassName(it) }.toTypedArray(),
            shallow = ulongArrayOf(5U, 5U, 5U, 5U),
            refs = arrayOf(intArrayOf(1), intArrayOf(0), intArrayOf(3), intArrayOf(2)),
        )
        val t = analyze(g, intArrayOf(1, 3))

        val sccs = t.entries.filter { it.kind == ShapeKind.SCC }
        assertEquals(1, sccs.size)
        val s = sccs.single()
        assertEquals(4, s.instanceCount)       // 2 collapsed instances x 2 members
        assertEquals(2, s.sccInstanceSize)
        assertEquals(2, s.rootCount)
        assertEquals(2, s.internalEdgesPerInstance) // p->c and c->p
        assertEquals("scc[Child,Parent]", s.label)
        assertTrue(s.membersComplete)
        assertEquals(listOf(
            SccMemberEntry(ClassName("Child"), listOf(SccInternalEdge(1, 1))),
            SccMemberEntry(ClassName("Parent"), listOf(SccInternalEdge(0, 1))),
        ), s.members)
    }

    @Test
    fun `self-loop objects merge via scc path`() {
        val g = ObjectGraph.of(
            classes = listOf("N", "N").map { ClassName(it) }.toTypedArray(),
            shallow = ulongArrayOf(7U, 7U),
            refs = arrayOf(intArrayOf(0), intArrayOf(1)),
        )
        val t = analyze(g, intArrayOf(0, 1))

        val s = t.entries.single()
        assertEquals(ShapeKind.SCC, s.kind)
        assertEquals(2, s.instanceCount)
        assertEquals(1, s.internalEdgesPerInstance)
        assertEquals(listOf(SccMemberEntry(ClassName("N"), listOf(SccInternalEdge(0, 1)))), s.members)
    }

    @Test
    fun `scc member graph folds only parallel refs not distinct connections`() {
        val g = ObjectGraph.of(
            classes = listOf("A", "B").map { ClassName(it) }.toTypedArray(),
            shallow = ulongArrayOf(5U, 5U),
            refs = arrayOf(intArrayOf(1, 1), intArrayOf(0)),
        )
        val t = analyze(g, intArrayOf(0))

        val s = t.entries.single { it.kind == ShapeKind.SCC }
        assertEquals(3, s.internalEdgesPerInstance)
        assertEquals(listOf(
            SccMemberEntry(ClassName("A"), listOf(SccInternalEdge(1, 2))),
            SccMemberEntry(ClassName("B"), listOf(SccInternalEdge(0, 1))),
        ), s.members)
    }

    @Test
    fun `symmetric same-class cycles merge via WL-fixpoint signature`() {
        // two identical directed 3-cycles, all members same class: color
        // refinement cannot tell members apart (no discretization), but the
        // fixpoint signature is automorphism-invariant -> the cycles merge
        // (the old discretize-or-unique rule kept them as 2 unique shapes)
        val g = ObjectGraph.of(
            classes = Array(6) { ClassName("N") },
            shallow = ULongArray(6) { 5U },
            refs = arrayOf(intArrayOf(1), intArrayOf(2), intArrayOf(0),
                           intArrayOf(4), intArrayOf(5), intArrayOf(3)),
        )
        val t = analyze(g, intArrayOf(0, 3))

        val s = t.entries.single { it.kind == ShapeKind.SCC }
        assertEquals(6, s.instanceCount)   // member objects (2 instances × 3)
        assertEquals(3, s.sccInstanceSize)
        assertEquals(3, s.internalEdgesPerInstance)
    }

    @Test
    fun `scc larger than 64 never merges even when it would discretize`() {
        // cycle of 65 nodes, classes distinct per position -> would discretize,
        // but |SCC| > 64 forces the unique fallback
        val n = ShapesAnalyzer.MAX_SCC_MERGE + 1
        val g = ObjectGraph.of(
            classes = Array(2 * n) { ClassName("C${it % n}") },
            shallow = ULongArray(2 * n) { 5U },
            refs = Array(2 * n) { intArrayOf((it + 1) % n + if (it < n) 0 else n) },
        )
        val t = analyze(g, intArrayOf(0, n))

        val sccs = t.entries.filter { it.kind == ShapeKind.SCC_UNIQUE }
        assertEquals(2, sccs.size)
        assertEquals(n.toLong(), sccs[0].instanceCount)
        assertEquals(8, sccs[0].members!!.size)
        assertEquals(false, sccs[0].membersComplete)
        assertTrue(sccs[0].members!!.size < sccs[0].sccInstanceSize)
    }

    @Test
    fun `synthetic modules collapse and share one leaf`() {
        val (g, roots) = SynthGraphs.modules(500)
        val t = analyze(g, roots)

        // 500 modules x 6 objects + 1 shared = 3001 objects in 6 shapes
        // (LibA/LibB share the synth.Lib class -> one shape)
        assertEquals(g.size, t.reachableCount)
        assertEquals(6, t.entries.size)
        assertEquals(mapOf(t.entry("synth.Module").id to 500L), t.rootsHistogram)
        assertEquals(1, t.entry("synth.SharedProps").instanceCount) // shared, in-degree 500
        assertEquals(500, t.entry("synth.Module").instanceCount)
        // per-member multiplicities: each config holds exactly one ref to the shared leaf
        assertTrue(t.hasEdge("synth.Config", "synth.SharedProps", 1))
    }

    @Test
    fun `determinism - two runs produce identical digests and edges`() {
        val (g, roots) = SynthGraphs.modules(100)
        val t1 = ShapesAnalyzer(g).analyze(roots)
        val t2 = ShapesAnalyzer(g).analyze(roots)
        assertEquals(t1.entries.map { it.digest }, t2.entries.map { it.digest })
        assertEquals(t1.edges, t2.edges)
        assertEquals(t1.rootsHistogram, t2.rootsHistogram)
    }

    @Test
    fun `preselected scope keeps disconnected objects without making them roots`() {
        val g = ObjectGraph.of(
            listOf("Root", "Detached").map { ClassName(it) }.toTypedArray(),
            ulongArrayOf(8u, 24u),
            arrayOf(intArrayOf(), intArrayOf()),
        )
        val t = ShapesAnalyzer(g).analyze(intArrayOf(0), wholeGraph = true)
        assertEquals(2, t.reachableCount)
        assertEquals(32U, t.entries.sumOf { it.shallowTotal })
        assertEquals(1, t.entries.sumOf { it.rootCount })
        assertEquals(0, t.entry("Detached").rootCount)
    }

    @Test
    fun `heap-wide analysis needs no class roots`() {
        val g = ObjectGraph.of(
            listOf("A", "B").map { ClassName(it) }.toTypedArray(),
            ulongArrayOf(8U, 24U),
            arrayOf(intArrayOf(1), intArrayOf()),
        )
        val t = ShapesAnalyzer(g).analyze(IntArray(0), wholeGraph = true)
        assertEquals(2, t.reachableCount)
        assertEquals(32U, t.entries.sumOf { it.shallowTotal })
        assertEquals(0, t.entries.sumOf { it.rootCount })
        assertTrue(t.rootsHistogram.isEmpty())
    }

    @Test
    fun `unreachable objects are out of scope`() {
        val b = ObjectGraph.Builder()
        val stray = b.add(ClassName("Stray"), 1000U)
        val l = b.add(ClassName("L"), 10U)
        val m = b.add(ClassName("M"), 20U, intArrayOf(l))
        val t = analyze(b.build(), intArrayOf(m))

        assertEquals(2, t.reachableCount)
        assertTrue(t.entries.none { it.label == "Stray" })
    }
}
