package shapes.relaxation

import shapes.analysis.AnalysisScope
import shapes.analysis.ShapeKind
import shapes.analysis.ShapesAnalyzer
import shapes.graph.ClassName
import shapes.graph.ObjectGraph
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class RelaxationRulesTest {
    @Test
    fun `parse requires a reason`() {
        assertFailsWith<IllegalArgumentException> {
            RelaxationRulesTsvParser.parse("com.example.Foo\tclass-only")
        }
        assertFailsWith<IllegalArgumentException> {
            RelaxationRulesTsvParser.parse("com.example.Foo\tclass-only\t")
        }
        assertFailsWith<IllegalStateException> {
            RelaxationRulesTsvParser.parse("com.example.Foo\tunknown\treason")
        }
    }

    @Test
    fun `parse reads both structural levels`() {
        val rules = RelaxationRulesTsvParser.parse(
            "# comment\n\ncom.example.Foo\tclass-only\thides state below Foo\n" +
                "com.example.Bar\tset-children\tmultiplicity is noise below Bar\n"
        )
        assertEquals(
            mapOf(
                ClassName("com.example.Foo") to StructuralRelaxation.CLASS_ONLY,
                ClassName("com.example.Bar") to StructuralRelaxation.SET_CHILDREN,
            ),
            rules.shapeRules.byClass,
        )
    }

    @Test
    fun `parse reads conceal-field rules`() {
        val rules = RelaxationRulesTsvParser.parse(
            "com.example.Foo\tconceal-field:lazyCache\tlazy field\n" +
                "com.example.Foo\tconceal-field:otherLazy\tsecond lazy field\n" +
                "com.example.Bar\tclass-only\tclass rule\n"
        )
        assertEquals(
            mapOf(ClassName("com.example.Foo") to setOf("lazyCache", "otherLazy")),
            rules.fieldConcealmentRules.fieldsByClass,
        )
        assertEquals(
            mapOf(ClassName("com.example.Bar") to StructuralRelaxation.CLASS_ONLY),
            rules.shapeRules.byClass,
        )
        assertFailsWith<IllegalArgumentException> {
            RelaxationRulesTsvParser.parse("com.example.Foo\tconceal-field:\treason")
        }
    }

    @Test
    fun `class-only merges state differences below the relaxed object`() {
        val builder = ObjectGraph.Builder()
        val firstState = builder.addObject(ClassName("State"), 10U)
        val secondState = builder.addObject(ClassName("State"), 10U)
        val firstRelaxed = builder.addObject(ClassName("Relaxed"), 20U, intArrayOf(firstState))
        val secondRelaxed = builder.addObject(ClassName("Relaxed"), 20U, intArrayOf(secondState))
        val firstRoot = builder.addObject(ClassName("Root"), 30U, intArrayOf(firstRelaxed))
        val secondRoot = builder.addObject(ClassName("Root"), 30U, intArrayOf(secondRelaxed))
        val analyzer = ShapesAnalyzer(
            builder.build(),
            rules(ClassName("Relaxed") to StructuralRelaxation.CLASS_ONLY),
        )

        val result = analyzer.analyze(AnalysisScope.ReachableFrom(intArrayOf(firstRoot, secondRoot)))

        assertEquals(1, result.entries.count { it.label == "Relaxed" })
        assertEquals(1, result.entries.count { it.label == "Root" })
        assertEquals(2, result.entries.single { it.label == "Relaxed" }.instanceCount)
        assertEquals(2, result.entries.single { it.label == "State" }.instanceCount)
        assertEquals(2, analyzer.relaxationStats.classOnlyHiddenEdges)
        assertEquals(0, analyzer.relaxationStats.setChildrenHiddenEdges)
        assertEquals(2, analyzer.relaxationStats.relaxedObjects)
        assertTrue(result.edges.none { result.entries[it.shapeId].label == "Relaxed" })
    }

    @Test
    fun `analyzer reuse resets relaxation accounting`() {
        val builder = ObjectGraph.Builder()
        val state = builder.addObject(ClassName("State"), 10U)
        val relaxed = builder.addObject(ClassName("Relaxed"), 20U, intArrayOf(state))
        val root = builder.addObject(ClassName("Root"), 30U, intArrayOf(relaxed))
        val analyzer = ShapesAnalyzer(
            builder.build(),
            rules(ClassName("Relaxed") to StructuralRelaxation.CLASS_ONLY),
        )

        repeat(2) {
            analyzer.analyze(AnalysisScope.ReachableFrom(intArrayOf(root)))
            assertEquals(1, analyzer.relaxationStats.relaxedObjects)
            assertEquals(1, analyzer.relaxationStats.classOnlyHiddenEdges)
        }
    }

    @Test
    fun `class-only does not merge different classes`() {
        val builder = ObjectGraph.Builder()
        val state = builder.addObject(ClassName("State"), 10U)
        val first = builder.addObject(ClassName("RelaxedA"), 20U, intArrayOf(state))
        val second = builder.addObject(ClassName("RelaxedB"), 20U)
        val root = builder.addObject(ClassName("Root"), 30U, intArrayOf(first, second))
        val analyzer = ShapesAnalyzer(
            builder.build(),
            ShapeRelaxationRules(
                mapOf(
                    ClassName("RelaxedA") to StructuralRelaxation.CLASS_ONLY,
                    ClassName("RelaxedB") to StructuralRelaxation.CLASS_ONLY,
                )
            ),
        )

        val result = analyzer.analyze(AnalysisScope.ReachableFrom(intArrayOf(root)))
        assertEquals(2, result.entries.count { it.label.startsWith("Relaxed") })
    }

    @Test
    fun `set-children collapses multiplicities only`() {
        val builder = ObjectGraph.Builder()
        val x = builder.addObject(ClassName("X"), 5U)
        val first = builder.addObject(ClassName("M"), 10U, intArrayOf(x, x))
        val second = builder.addObject(ClassName("M"), 10U, intArrayOf(x))
        val root = builder.addObject(ClassName("R"), 10U, intArrayOf(first, second))
        val graph = builder.build()
        val scope = AnalysisScope.ReachableFrom(intArrayOf(root))

        val exact = ShapesAnalyzer(graph).analyze(scope)
        assertEquals(2, exact.entries.count { it.label == "M" })

        val relaxedAnalyzer = ShapesAnalyzer(
            graph,
            rules(ClassName("M") to StructuralRelaxation.SET_CHILDREN),
        )
        val relaxed = relaxedAnalyzer.analyze(scope)
        assertEquals(1, relaxed.entries.count { it.label == "M" })
        assertEquals(2, relaxed.entries.single { it.label == "M" }.instanceCount)
        assertEquals(1, relaxedAnalyzer.relaxationStats.setChildrenHiddenEdges)

        val otherBuilder = ObjectGraph.Builder()
        val otherX = otherBuilder.addObject(ClassName("X"), 5U)
        val y = otherBuilder.addObject(ClassName("Y"), 5U)
        val xParent = otherBuilder.addObject(ClassName("M"), 10U, intArrayOf(otherX, otherX))
        val yParent = otherBuilder.addObject(ClassName("M"), 10U, intArrayOf(y))
        val otherRoot = otherBuilder.addObject(ClassName("R"), 10U, intArrayOf(xParent, yParent))
        val differentChildren = ShapesAnalyzer(
            otherBuilder.build(),
            rules(ClassName("M") to StructuralRelaxation.SET_CHILDREN),
        ).analyze(AnalysisScope.ReachableFrom(intArrayOf(otherRoot)))
        assertEquals(2, differentChildren.entries.count { it.label == "M" })
    }

    @Test
    fun `set-children quotients the whole SCC`() {
        val graph = ObjectGraph.of(
            classes = listOf("C", "C", "S", "S", "C", "C", "S", "S", "S")
                .map(::ClassName)
                .toTypedArray(),
            shallow = ULongArray(9) { 5U },
            refs = arrayOf(
                intArrayOf(1),
                intArrayOf(0, 2, 3),
                intArrayOf(1),
                intArrayOf(1),
                intArrayOf(5),
                intArrayOf(4, 6, 7, 8),
                intArrayOf(5),
                intArrayOf(5),
                intArrayOf(5),
            ),
        )
        val scope = AnalysisScope.ReachableFrom(intArrayOf(0, 4))
        val exact = ShapesAnalyzer(graph).analyze(scope)
        assertEquals(2, exact.entries.count { it.kind == ShapeKind.SCC })

        val analyzer = ShapesAnalyzer(
            graph,
            rules(ClassName("C") to StructuralRelaxation.SET_CHILDREN),
        )
        val relaxed = analyzer.analyze(scope)
        val scc = relaxed.entries.single { it.kind == ShapeKind.SCC }
        assertEquals(3, scc.sccInstanceSize)
        assertEquals(2, scc.instanceCount / scc.sccInstanceSize)
        assertEquals(3, scc.members?.size)
        assertEquals(4, scc.members.orEmpty().sumOf { member ->
            member.internalConnections.sumOf { it.multiplicity }
        })
        assertTrue(analyzer.relaxationStats.setChildrenHiddenEdges > 0)
    }

    @Test
    fun `relaxation applies inside scc members`() {
        val graph = ObjectGraph.of(
            classes = listOf("A", "B", "S", "X", "A", "B", "S")
                .map(::ClassName)
                .toTypedArray(),
            shallow = ULongArray(7) { 5U },
            refs = arrayOf(
                intArrayOf(1, 2),
                intArrayOf(0),
                intArrayOf(3),
                intArrayOf(),
                intArrayOf(5, 6),
                intArrayOf(4),
                intArrayOf(),
            ),
        )
        val scope = AnalysisScope.ReachableFrom(intArrayOf(0, 4))
        val exact = ShapesAnalyzer(graph).analyze(scope)
        assertEquals(2, exact.entries.count { it.kind == ShapeKind.SCC })

        val analyzer = ShapesAnalyzer(
            graph,
            rules(ClassName("A") to StructuralRelaxation.CLASS_ONLY),
        )
        val relaxed = analyzer.analyze(scope)
        val scc = relaxed.entries.single { it.kind == ShapeKind.SCC }
        assertEquals(4, scc.instanceCount)
        assertEquals(2, scc.internalEdgesPerInstance)
        assertEquals(2, scc.members.orEmpty().sumOf { member ->
            member.internalConnections.sumOf { it.multiplicity }
        })
        assertEquals(2, analyzer.relaxationStats.classOnlyHiddenEdges)
        assertEquals(2, analyzer.relaxationStats.relaxedObjects)
    }

    private fun rules(
        vararg rules: Pair<ClassName, StructuralRelaxation>,
    ) = ShapeRelaxationRules(mapOf(*rules))
}
