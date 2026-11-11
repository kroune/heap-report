package shapes.analysis

import shapes.graph.ClassName
import shapes.graph.ObjectGraph
import shapes.synthetic.SyntheticObjectGraphs
import shapes.verification.ShapeInvariantVerifier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ShapesAnalyzerTest {
    private fun analyze(graph: ObjectGraph, roots: IntArray): ShapeAnalysisResult {
        val scope = AnalysisScope.ReachableFrom(roots)
        val result = ShapesAnalyzer(graph).analyze(scope)
        val violations = ShapeInvariantVerifier.verify(graph, scope, result)
        assertTrue(violations.isEmpty(), "invariant violations:\n" + violations.joinToString("\n"))
        return result
    }

    private fun ShapeAnalysisResult.entry(label: String): ShapeEntry =
        entries.single { it.label == label }

    private fun ShapeAnalysisResult.hasEdge(
        fromLabel: String,
        toLabel: String,
        multiplicity: Long,
    ): Boolean {
        val source = entry(fromLabel)
        val target = entry(toLabel)
        return edges.any {
            it.shapeId == source.id && it.childShapeId == target.id && it.multiplicity == multiplicity
        }
    }

    @Test
    fun `identical subgraphs collapse to one shape`() {
        val builder = ObjectGraph.Builder()
        val firstLeaf = builder.addObject(ClassName("L"), 10U)
        val secondLeaf = builder.addObject(ClassName("L"), 10U)
        val firstParent = builder.addObject(ClassName("M"), 20U, intArrayOf(firstLeaf))
        val secondParent = builder.addObject(ClassName("M"), 20U, intArrayOf(secondLeaf))
        val result = analyze(builder.build(), intArrayOf(firstParent, secondParent))

        assertEquals(2, result.entries.size)
        assertEquals(2, result.entry("M").instanceCount)
        assertEquals(2, result.entry("L").instanceCount)
        assertEquals(mapOf(result.entry("M").id to 2L), result.rootsHistogram)
        assertTrue(result.hasEdge("M", "L", 1))
    }

    @Test
    fun `diamond keeps both edges to shared child`() {
        val builder = ObjectGraph.Builder()
        val shared = builder.addObject(ClassName("D"), 5U)
        val left = builder.addObject(ClassName("B"), 5U, intArrayOf(shared))
        val right = builder.addObject(ClassName("C"), 5U, intArrayOf(shared))
        val root = builder.addObject(ClassName("A"), 5U, intArrayOf(left, right))
        val result = analyze(builder.build(), intArrayOf(root))

        assertEquals(1, result.entry("D").instanceCount)
        assertTrue(result.hasEdge("B", "D", 1))
        assertTrue(result.hasEdge("C", "D", 1))
    }

    @Test
    fun `multi-edges are preserved`() {
        val builder = ObjectGraph.Builder()
        val firstX = builder.addObject(ClassName("X"), 5U)
        val secondX = builder.addObject(ClassName("X"), 5U)
        val y = builder.addObject(ClassName("Y"), 5U)
        val repeatedX = builder.addObject(ClassName("A"), 5U, intArrayOf(firstX, secondX))
        val differentChildren = builder.addObject(ClassName("A"), 5U, intArrayOf(firstX, y))
        val result = analyze(builder.build(), intArrayOf(repeatedX, differentChildren))

        val parentShapes = result.entries.filter { it.label == "A" }
        assertEquals(2, parentShapes.size)
        val repeatedShape = parentShapes.single { shape ->
            result.edges.any { it.shapeId == shape.id && it.multiplicity == 2L }
        }
        assertTrue(
            result.edges.any {
                it.shapeId == repeatedShape.id &&
                    it.childShapeId == result.entry("X").id &&
                    it.multiplicity == 2L
            }
        )
        assertTrue(
            result.edges.none {
                it.shapeId == repeatedShape.id && it.childShapeId == result.entry("Y").id
            }
        )
    }

    @Test
    fun `shallow size is not part of the key`() {
        val builder = ObjectGraph.Builder()
        val small = builder.addObject(ClassName("byte[]"), 16U)
        val large = builder.addObject(ClassName("byte[]"), 1_000_000U)
        val result = analyze(builder.build(), intArrayOf(small, large))

        val entry = result.entry("byte[]")
        assertEquals(2, entry.instanceCount)
        assertEquals(16U, entry.shallowMin)
        assertEquals(1_000_000U, entry.shallowMax)
        assertEquals(1_000_016U, entry.shallowTotal)
    }

    @Test
    fun `back-pointer scc collapses when classes discretize members`() {
        val graph = ObjectGraph.of(
            classes = listOf("Child", "Parent", "Child", "Parent")
                .map(::ClassName)
                .toTypedArray(),
            shallow = ulongArrayOf(5U, 5U, 5U, 5U),
            refs = arrayOf(intArrayOf(1), intArrayOf(0), intArrayOf(3), intArrayOf(2)),
        )
        val result = analyze(graph, intArrayOf(1, 3))

        val cyclicShape = result.entries.single { it.kind == ShapeKind.SCC }
        assertEquals(4, cyclicShape.instanceCount)
        assertEquals(2, cyclicShape.sccInstanceSize)
        assertEquals(2, cyclicShape.rootCount)
        assertEquals(2, cyclicShape.internalEdgesPerInstance)
        assertEquals("scc[Child,Parent]", cyclicShape.label)
        assertTrue(cyclicShape.membersComplete)
        assertEquals(
            listOf(
                SccMemberEntry(ClassName("Child"), listOf(SccMemberConnection(1, 1))),
                SccMemberEntry(ClassName("Parent"), listOf(SccMemberConnection(0, 1))),
            ),
            cyclicShape.members,
        )
    }

    @Test
    fun `self-loop objects merge via scc path`() {
        val graph = ObjectGraph.of(
            classes = arrayOf(ClassName("N"), ClassName("N")),
            shallow = ulongArrayOf(7U, 7U),
            refs = arrayOf(intArrayOf(0), intArrayOf(1)),
        )
        val result = analyze(graph, intArrayOf(0, 1))

        val shape = result.entries.single()
        assertEquals(ShapeKind.SCC, shape.kind)
        assertEquals(2, shape.instanceCount)
        assertEquals(1, shape.internalEdgesPerInstance)
        assertEquals(
            listOf(SccMemberEntry(ClassName("N"), listOf(SccMemberConnection(0, 1)))),
            shape.members,
        )
    }

    @Test
    fun `scc member graph folds only parallel refs not distinct connections`() {
        val graph = ObjectGraph.of(
            classes = arrayOf(ClassName("A"), ClassName("B")),
            shallow = ulongArrayOf(5U, 5U),
            refs = arrayOf(intArrayOf(1, 1), intArrayOf(0)),
        )
        val result = analyze(graph, intArrayOf(0))

        val shape = result.entries.single { it.kind == ShapeKind.SCC }
        assertEquals(3, shape.internalEdgesPerInstance)
        assertEquals(
            listOf(
                SccMemberEntry(ClassName("A"), listOf(SccMemberConnection(1, 2))),
                SccMemberEntry(ClassName("B"), listOf(SccMemberConnection(0, 1))),
            ),
            shape.members,
        )
    }

    @Test
    fun `symmetric same-class cycles merge via fixpoint signature`() {
        val graph = ObjectGraph.of(
            classes = Array(6) { ClassName("N") },
            shallow = ULongArray(6) { 5U },
            refs = arrayOf(
                intArrayOf(1), intArrayOf(2), intArrayOf(0),
                intArrayOf(4), intArrayOf(5), intArrayOf(3),
            ),
        )
        val result = analyze(graph, intArrayOf(0, 3))

        val shape = result.entries.single()
        assertEquals(6, shape.instanceCount)
        assertEquals(3, shape.sccInstanceSize)
        assertEquals(3, shape.internalEdgesPerInstance)
    }

    @Test
    fun `synthetic modules collapse and share one leaf`() {
        val (graph, roots) = SyntheticObjectGraphs.modules(500)
        val result = analyze(graph, roots)

        assertEquals(graph.size, result.reachableObjectCount)
        assertEquals(6, result.entries.size)
        assertEquals(mapOf(result.entry("synth.Module").id to 500L), result.rootsHistogram)
        assertEquals(1, result.entry("synth.SharedProps").instanceCount)
        assertEquals(500, result.entry("synth.Module").instanceCount)
        assertTrue(result.hasEdge("synth.Config", "synth.SharedProps", 1))
    }

    @Test
    fun `two runs produce identical digests and edges`() {
        val (graph, roots) = SyntheticObjectGraphs.modules(100)
        val first = ShapesAnalyzer(graph).analyze(AnalysisScope.ReachableFrom(roots))
        val second = ShapesAnalyzer(graph).analyze(AnalysisScope.ReachableFrom(roots))

        assertEquals(first.entries.map { it.digest }, second.entries.map { it.digest })
        assertEquals(first.edges, second.edges)
        assertEquals(first.rootsHistogram, second.rootsHistogram)
    }

    @Test
    fun `preselected scope keeps disconnected objects without making them roots`() {
        val graph = ObjectGraph.of(
            classes = arrayOf(ClassName("Root"), ClassName("Detached")),
            shallow = ulongArrayOf(8U, 24U),
            refs = arrayOf(intArrayOf(), intArrayOf()),
        )
        val result = ShapesAnalyzer(graph).analyze(
            AnalysisScope.EntireGraph(rootsForHistogram = intArrayOf(0))
        )

        assertEquals(2, result.reachableObjectCount)
        assertEquals(32U, result.entries.sumOf { it.shallowTotal })
        assertEquals(1, result.entries.sumOf { it.rootCount })
        assertEquals(0, result.entry("Detached").rootCount)
    }

    @Test
    fun `heap-wide analysis needs no class roots`() {
        val graph = ObjectGraph.of(
            classes = arrayOf(ClassName("A"), ClassName("B")),
            shallow = ulongArrayOf(8U, 24U),
            refs = arrayOf(intArrayOf(1), intArrayOf()),
        )
        val result = ShapesAnalyzer(graph).analyze(AnalysisScope.EntireGraph())

        assertEquals(2, result.reachableObjectCount)
        assertEquals(32U, result.entries.sumOf { it.shallowTotal })
        assertEquals(0, result.entries.sumOf { it.rootCount })
        assertTrue(result.rootsHistogram.isEmpty())
    }

    @Test
    fun `unreachable objects are out of scope`() {
        val builder = ObjectGraph.Builder()
        builder.addObject(ClassName("Stray"), 1000U)
        val leaf = builder.addObject(ClassName("L"), 10U)
        val root = builder.addObject(ClassName("M"), 20U, intArrayOf(leaf))
        val result = analyze(builder.build(), intArrayOf(root))

        assertEquals(2, result.reachableObjectCount)
        assertTrue(result.entries.none { it.label == "Stray" })
    }
}
