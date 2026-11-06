package shapes

import shapes.models.ClassName
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class RelaxTest {

    @Test
    fun `parse requires a reason`() {
        assertFailsWith(IllegalArgumentException::class) {
            Relax.parse("com.example.Foo\tclass-only")
        }
        assertFailsWith(IllegalArgumentException::class) {
            Relax.parse("com.example.Foo\tclass-only\t")
        }
        assertFailsWith(IllegalStateException::class) {
            Relax.parse("com.example.Foo\tweird-level\treason")
        }
    }

    @Test
    fun `parse reads both levels`() {
        val rules = Relax.parse(
            "# comment\n\ncom.example.Foo\tclass-only\thides state below Foo\n" +
                    "com.example.Bar\tset-children\tmultiplicity is noise below Bar\n"
        )
        assertEquals(
            mapOf(
                ClassName("com.example.Foo") to Relax.CLASS_ONLY,
                ClassName("com.example.Bar") to Relax.SET_CHILDREN
            ),
            rules.classRules
        )
    }

    @Test
    fun `parse reads conceal-field rules`() {
        val rules = Relax.parse(
            "com.example.Foo\tconceal-field:lazyCache\tlazy field, set only after first download\n" +
                    "com.example.Foo\tconceal-field:otherLazy\tsecond lazy field\n" +
                    "com.example.Bar\tclass-only\tclass rule\n"
        )
        assertEquals(
            mapOf(ClassName("com.example.Foo") to setOf("lazyCache", "otherLazy")),
            rules.fieldRules
        )
        assertEquals(mapOf(ClassName("com.example.Bar") to Relax.CLASS_ONLY), rules.classRules)
        assertFailsWith(IllegalArgumentException::class) {
            Relax.parse("com.example.Foo\tconceal-field:\ttreason")
        }
    }

    @Test
    fun `class-only merges state differences below the relaxed object`() {
        // root -> A1 -> state1, root2 -> A2 -> state2: A is relaxed class-only
        val b = ObjectGraph.Builder()
        val s1 = b.add(ClassName("State"), 10U)
        val s2 = b.add(ClassName("State"), 10U, intArrayOf())
        val a1 = b.add(ClassName("Relaxed"), 20U, intArrayOf(s1))
        val a2 = b.add(ClassName("Relaxed"), 20U, intArrayOf(s2))
        val r1 = b.add(ClassName("Root"), 30U, intArrayOf(a1))
        val r2 = b.add(ClassName("Root"), 30U, intArrayOf(a2))
        val g = b.build()
        val z = ShapesAnalyzer(g, mapOf(ClassName("Relaxed") to Relax.CLASS_ONLY))
        val t = z.analyze(intArrayOf(r1, r2))

        assertEquals(1, t.entries.single { it.label == "Relaxed" }.instanceCount.let {
            t.entries.count { it.label == "Relaxed" }
        }, "relaxed A1/A2 must merge into one shape")
        assertEquals(1, t.entries.count { it.label == "Root" }, "roots must merge too")
        assertEquals(2, t.entries.single { it.label == "Relaxed" }.instanceCount)
        assertEquals(2, z.relaxStats.classOnlyHiddenEdges)
        assertEquals(0, z.relaxStats.setChildrenHiddenEdges)
        assertEquals(2, z.relaxStats.relaxedObjects)
        // children stay in scope with their counts
        assertEquals(2, t.entries.single { it.label == "State" }.instanceCount)
        // the relaxed shape conceals its edges
        assertTrue(t.edges.none { t.entries[it.shapeId].label == "Relaxed" })
    }

    @Test
    fun `analyzer reuse resets relaxation accounting`() {
        val b = ObjectGraph.Builder()
        val state = b.add(ClassName("State"), 10U)
        val relaxed = b.add(ClassName("Relaxed"), 20U, intArrayOf(state))
        val root = b.add(ClassName("Root"), 30U, intArrayOf(relaxed))
        val analyzer = ShapesAnalyzer(b.build(), mapOf(ClassName("Relaxed") to Relax.CLASS_ONLY))
        repeat(2) {
            analyzer.analyze(intArrayOf(root))
            assertEquals(1, analyzer.relaxStats.relaxedObjects)
            assertEquals(1, analyzer.relaxStats.classOnlyHiddenEdges)
        }
    }

    @Test
    fun `class-only does NOT merge different classes`() {
        val b = ObjectGraph.Builder()
        val a1 = b.add(ClassName("RelaxedA"), 20U, intArrayOf(b.add(ClassName("State"), 10U)))
        val a2 = b.add(ClassName("RelaxedB"), 20U, intArrayOf())
        val r = b.add(ClassName("Root"), 30U, intArrayOf(a1, a2))
        val t = ShapesAnalyzer(
            b.build(),
            mapOf(ClassName("RelaxedA") to Relax.CLASS_ONLY, ClassName("RelaxedB") to Relax.CLASS_ONLY)
        )
            .analyze(intArrayOf(r))
        assertEquals(2, t.entries.count { it.label.startsWith("Relaxed") })
    }

    @Test
    fun `set-children collapses multiplicities only`() {
        // M1 -> {X, X}, M2 -> {X}: same child shape SET, different mults
        val b = ObjectGraph.Builder()
        val x = b.add(ClassName("X"), 5U)
        val m1 = b.add(ClassName("M"), 10U, intArrayOf(x, x))
        val m2 = b.add(ClassName("M"), 10U, intArrayOf(x))
        val r = b.add(ClassName("R"), 10U, intArrayOf(m1, m2))
        val g = b.build()

        // exact: two M shapes (mult 2 vs mult 1)
        val exact = ShapesAnalyzer(g).analyze(intArrayOf(r))
        assertEquals(2, exact.entries.count { it.label == "M" })

        // set-children: one M shape
        val z = ShapesAnalyzer(g, mapOf(ClassName("M") to Relax.SET_CHILDREN))
        val t = z.analyze(intArrayOf(r))
        assertEquals(1, t.entries.count { it.label == "M" })
        assertEquals(2, t.entries.single { it.label == "M" }.instanceCount)
        // M1 had 2 refs -> 1 emitted edge; M2 had 1 ref -> 1 emitted edge:
        // hidden = (2-1) + (1-1) = 1
        assertEquals(1, z.relaxStats.setChildrenHiddenEdges)
        // but a DIFFERENT child shape set must still not merge:
        val b2 = ObjectGraph.Builder()
        val x2 = b2.add(ClassName("X"), 5U)
        val y2 = b2.add(ClassName("Y"), 5U)
        val n1 = b2.add(ClassName("M"), 10U, intArrayOf(x2, x2))
        val n2 = b2.add(ClassName("M"), 10U, intArrayOf(y2))
        val r2 = b2.add(ClassName("R"), 10U, intArrayOf(n1, n2))
        val t2 = ShapesAnalyzer(b2.build(), mapOf(ClassName("M") to Relax.SET_CHILDREN)).analyze(intArrayOf(r2))
        assertEquals(2, t2.entries.count { it.label == "M" }, "{X,X} vs {Y} must not merge")
    }

    @Test
    fun `set-children quotients the whole SCC`() {
        // two SCC instances {x<->y, y<->z_i} with k=2 vs k=3 shadow members:
        // exact analysis distinguishes them (member count differs), set-
        // children on C collapses the SCC to its WL-color quotient.
        // ids: 0=x2 1=y2 2,3=z  4=x3 5=y3 6,7,8=z
        val g = ObjectGraph.of(
            classes = listOf("C", "C", "S", "S", "C", "C", "S", "S", "S").map { ClassName(it) }.toTypedArray(),
            shallow = ULongArray(9) { 5U },
            refs = arrayOf(
                intArrayOf(1),          // x2 -> y2
                intArrayOf(0, 2, 3),    // y2 -> x2, z, z
                intArrayOf(1),          // z -> y2
                intArrayOf(1),          // z -> y2
                intArrayOf(5),          // x3 -> y3
                intArrayOf(4, 6, 7, 8), // y3 -> x3, z, z, z
                intArrayOf(5),          // z -> y3
                intArrayOf(5),          // z -> y3
                intArrayOf(5),          // z -> y3
            ),
        )
        val exact = ShapesAnalyzer(g).analyze(intArrayOf(0, 4))
        assertEquals(
            2, exact.entries.count { it.kind == ShapeKind.SCC },
            "k=2 vs k=3 member count splits the SCCs exactly"
        )

        val z = ShapesAnalyzer(g, mapOf(ClassName("C") to Relax.SET_CHILDREN))
        val t = z.analyze(intArrayOf(0, 4))
        val sccs = t.entries.filter { it.kind == ShapeKind.SCC }
        assertEquals(1, sccs.size, "the quotient merges k=2 and k=3")
        assertEquals(3, sccs[0].sccInstanceSize) // quotient: x-color, y-color, z-color
        assertEquals(
            2, sccs[0].instanceCount / sccs[0].sccInstanceSize,
            "collapsed instances = both SCC instances"
        )
        val members = sccs[0].members!!
        assertEquals(3, members.size)
        assertEquals(
            4, members.sumOf { m -> m.internal.sumOf { it.mult } },
            "quotient adjacency is the visible x↔y↔z graph"
        )
        assertEquals(
            sccs[0].internalEdgesPerInstance,
            members.sumOf { m -> m.internal.sumOf { it.mult } })
        assertTrue(
            z.relaxStats.setChildrenHiddenEdges > 0,
            "the quotient's concealed edges are accounted"
        )
    }

    @Test
    fun `relax applies inside scc members`() {
        // two isomorphic 2-cycles A<->B; each A also holds a DIFFERENT
        // external state subtree. Exact: the SCCs do not merge (A's external
        // kids differ). With A relaxed class-only, A's external refs are
        // concealed, the colors agree, and the SCCs merge; internal SCC
        // wiring stays exact.
        // ids: 0=A0 1=B0 2=S0 3=X (under S0)  4=A1 5=B1 6=S1 (childless)
        val g = ObjectGraph.of(
            classes = listOf("A", "B", "S", "X", "A", "B", "S").map { ClassName(it) }.toTypedArray(),
            shallow = ULongArray(7) { 5U },
            refs = arrayOf(
                intArrayOf(1, 2), // A0 -> B0, S0
                intArrayOf(0),    // B0 -> A0
                intArrayOf(3),    // S0 -> X
                intArrayOf(),     // X
                intArrayOf(5, 6), // A1 -> B1, S1
                intArrayOf(4),    // B1 -> A1
                intArrayOf(),     // S1
            ),
        )
        val exact = ShapesAnalyzer(g).analyze(intArrayOf(0, 4))
        assertEquals(
            2, exact.entries.count { it.kind == ShapeKind.SCC },
            "external kid differences split the two cycles exactly"
        )

        val z = ShapesAnalyzer(g, mapOf(ClassName("A") to Relax.CLASS_ONLY))
        val t = z.analyze(intArrayOf(0, 4))
        val sccs = t.entries.filter { it.kind == ShapeKind.SCC }
        assertEquals(1, sccs.size, "with A relaxed, the cycles merge")
        assertEquals(4, sccs[0].instanceCount)
        assertEquals(2, sccs[0].internalEdgesPerInstance, "internal wiring is preserved")
        assertEquals(2, sccs[0].members!!.sumOf { m -> m.internal.sumOf { it.mult } })
        assertEquals(2, z.relaxStats.classOnlyHiddenEdges, "one external ref concealed per A")
        assertEquals(2, z.relaxStats.relaxedObjects)
    }
}
