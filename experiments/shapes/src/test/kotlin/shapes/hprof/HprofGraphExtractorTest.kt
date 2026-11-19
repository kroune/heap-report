package shapes.hprof

import shapes.relaxation.FieldConcealmentRules
import shapes.graph.ClassName
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HprofGraphExtractorTest {
    private fun extract(
        dump: HprofTestDump,
        foldLeaves: Boolean = true,
        scopeClassName: String? = null,
        concealmentRules: FieldConcealmentRules = FieldConcealmentRules(),
    ): HprofExtractionOutput {
        val path = dump.write()
        try {
            HprofFile.open(path).use { file ->
                val directory = HprofDirectory.scan(file)
                val index = HprofHeapIndex.build(file, directory)
                return HprofGraphExtractor(
                    file = file,
                    directory = directory,
                    index = index,
                    concealmentRules = concealmentRules,
                    foldLeaves = foldLeaves,
                    scopeClassName = scopeClassName,
                ).extract()
            }
        } finally {
            Files.deleteIfExists(path)
        }
    }

    /** Edges of [nodeId] as (className, leaf) pairs, leaf targets resolved. */
    private fun HeapGraph.targetsOf(nodeId: Int): List<String> {
        val result = ArrayList<String>()
        forEachTarget(nodeId) { target ->
            result.add(
                if (target < 0) {
                    "leaf:" + classNames[leafShapes[HeapGraph.leafOrdinalOf(target)].classIndex].name
                } else {
                    classNameOf(target).name
                }
            )
        }
        return result.sorted()
    }

    private fun HeapGraph.nodesOf(name: String): List<Int> =
        (0 until nodeCount).filter { classNameOf(it).name == name }

    @Test
    fun `plain fields with duplicate targets collapse to one edge`() {
        val dump = HprofTestDump()
        dump.clazz(100, "java/lang/Object")
        dump.clazz(101, "demo/Holder", 100, fields = listOf("a" to HprofType.OBJECT, "b" to HprofType.OBJECT))
        dump.clazz(102, "demo/Value", 100)
        dump.instance(1000, 101, 2000, 2000) // both fields point at the same object
        dump.instance(2000, 102)
        dump.root(1000)

        val output = extract(dump)
        val graph = output.graph
        assertEquals(1, graph.nodesOf("demo.Holder").size)
        val holder = graph.nodesOf("demo.Holder").single()
        assertEquals(listOf("leaf:demo.Value"), graph.targetsOf(holder))
        assertEquals(1L, output.counters.dedupDropped)
    }

    @Test
    fun `unreachable objects are excluded by liveness marking`() {
        val dump = HprofTestDump()
        dump.clazz(100, "java/lang/Object")
        dump.clazz(101, "demo/Live", 100, fields = listOf("next" to HprofType.OBJECT))
        dump.instance(1000, 101, 1001)
        dump.instance(1001, 101, 0)
        dump.instance(1002, 101, 1003) // dead pair
        dump.instance(1003, 101, 0)
        dump.root(1000)

        val graph = extract(dump).graph
        assertEquals(2L, graph.totalObjectCount)
    }

    @Test
    fun `reference mechanics edges drop but keep the referent alive`() {
        val dump = HprofTestDump()
        dump.clazz(100, "java/lang/Object")
        dump.clazz(
            101,
            "java/lang/ref/Reference",
            100,
            fields = listOf("referent" to HprofType.OBJECT, "queue" to HprofType.OBJECT),
        )
        dump.clazz(102, "java/lang/ref/WeakReference", 101)
        dump.clazz(103, "demo/Payload", 100)
        dump.instance(1000, 102, 2000, 0) // weak ref with referent only
        dump.instance(2000, 103)
        dump.root(1000)

        val output = extract(dump)
        val graph = output.graph
        // The reference has no structural edges, so it folds to a leaf; the
        // weakly-held payload stays in the live set as its own leaf class.
        assertEquals(2L, graph.totalObjectCount)
        assertEquals(0, graph.nodeCount)
        assertEquals(2, graph.leafShapes.size)
        assertEquals(1L, output.counters.mechanicsDropped)
    }

    @Test
    fun `class statics keep objects alive when the class has live instances`() {
        val dump = HprofTestDump()
        dump.clazz(100, "java/lang/Object")
        dump.clazz(101, "demo/WithStatic", 100, staticRefs = listOf(2000))
        dump.clazz(102, "demo/StaticPayload", 100)
        dump.instance(1000, 101) // live instance fires the class
        dump.instance(2000, 102) // reachable only through the static
        dump.root(1000)

        val graph = extract(dump).graph
        assertEquals(2L, graph.totalObjectCount)
    }

    @Test
    fun `hash table arrays expand bucket chains to entry sets`() {
        val dump = HprofTestDump()
        dump.clazz(100, "java/lang/Object")
        dump.clazz(
            101,
            "java/util/HashMap\$Node",
            100,
            fields = listOf(
                "hash" to HprofType.INT,
                "key" to HprofType.OBJECT,
                "value" to HprofType.OBJECT,
                "next" to HprofType.OBJECT,
            ),
        )
        dump.clazz(102, "java/util/HashMap\$Node[]", 100)
        dump.clazz(103, "demo/Key", 100)
        // bucket chain: node1 -> node2; the chain's second node is only
        // reachable through `next`, which the projection re-attaches to the array
        dump.instance(1000, 101, 1, 3000, 3001, 1001)
        dump.instance(1001, 101, 2, 3002, 3003, 0)
        dump.instance(3000, 103)
        dump.instance(3001, 103)
        dump.instance(3002, 103)
        dump.instance(3003, 103)
        dump.objectArray(2000, 102, 1000, 0)
        dump.root(2000)

        val graph = extract(dump).graph
        val table = graph.nodesOf("java.util.HashMap\$Node[]").single()
        assertEquals(
            listOf("java.util.HashMap\$Node", "java.util.HashMap\$Node"),
            graph.targetsOf(table),
        )
        for (node in graph.nodesOf("java.util.HashMap\$Node")) {
            // next edges are gone; key and value stay
            assertEquals(listOf("leaf:demo.Key", "leaf:demo.Key"), graph.targetsOf(node))
        }
    }

    @Test
    fun `concurrent hash map keeps only its table edge`() {
        val dump = HprofTestDump()
        dump.clazz(100, "java/lang/Object")
        dump.clazz(
            101,
            "java/util/concurrent/ConcurrentHashMap",
            100,
            fields = listOf(
                "table" to HprofType.OBJECT,
                "nextTable" to HprofType.OBJECT,
                "keySetView" to HprofType.OBJECT,
            ),
        )
        dump.clazz(102, "java/util/concurrent/ConcurrentHashMap\$Node[]", 100)
        dump.clazz(103, "demo/View", 100)
        dump.instance(1000, 101, 2000, 0, 3000)
        dump.objectArray(2000, 102)
        dump.instance(3000, 103)
        dump.root(1000)

        val output = extract(dump)
        val graph = output.graph
        val map = graph.nodesOf("java.util.concurrent.ConcurrentHashMap").single()
        assertEquals(listOf("leaf:java.util.concurrent.ConcurrentHashMap\$Node[]"), graph.targetsOf(map))
        // the dropped view edge still keeps the view object alive
        assertEquals(3L, graph.totalObjectCount)
        assertEquals(1L, output.counters.projectionDropped)
    }

    @Test
    fun `thread stack locals become thread edges`() {
        val dump = HprofTestDump()
        dump.clazz(100, "java/lang/Object")
        dump.clazz(101, "java/lang/Thread", 100)
        dump.clazz(102, "demo/Local", 100)
        dump.instance(1000, 101)
        dump.instance(2000, 102)
        dump.threadRoot(1000, 7)
        dump.javaFrameLocal(2000, 7)

        val graph = extract(dump).graph
        val thread = graph.nodesOf("java.lang.Thread").single()
        assertEquals(listOf("leaf:demo.Local"), graph.targetsOf(thread))
    }

    @Test
    fun `fold and no-fold agree on totals`() {
        fun build(): HprofTestDump {
            val built = HprofTestDump()
            built.clazz(100, "java/lang/Object")
            built.clazz(101, "demo/Node", 100, fields = listOf("next" to HprofType.OBJECT))
            built.clazz(102, "java/lang/Object[]", 100)
            built.clazz(110, "[B", 100)
            built.instance(1000, 101, 1001)
            built.instance(1001, 101, 0)
            built.objectArray(2000, 102, 1000, 1000, 1001)
            built.primitiveArray(3000, 16)
            built.root(2000)
            built.root(3000)
            return built
        }

        val folded = extract(build(), foldLeaves = true).graph
        val unfolded = extract(build(), foldLeaves = false).graph

        assertEquals(unfolded.totalObjectCount, folded.totalObjectCount)
        assertEquals(unfolded.totalEdgeCount, folded.totalEdgeCount)
        assertTrue(folded.nodeCount < unfolded.nodeCount)
        // the array deduplicates its repeated element
        val array = folded.nodesOf("java.lang.Object[]").single()
        assertEquals(2, folded.targetCount(array))
    }

    @Test
    fun `excluded class objects drop from the graph with their edges`() {
        val dump = HprofTestDump()
        dump.clazz(100, "java/lang/Object")
        dump.clazz(101, "java/lang/Class", 100, fields = listOf("name" to HprofType.OBJECT))
        dump.clazz(102, "demo/Holder", 100, fields = listOf("type" to HprofType.OBJECT))
        dump.instance(1000, 102, 2000) // holder -> primitive class instance
        dump.instance(2000, 101, 3000) // class instance -> name
        dump.instance(3000, 100)
        dump.root(1000)

        val graph = extract(dump).graph
        val holder = graph.nodesOf("demo.Holder").single()
        assertEquals(emptyList<String>(), graph.targetsOf(holder))
        // the class instance is excluded, but its edge keeps the name alive
        assertEquals(2L, graph.totalObjectCount)
    }

    @Test
    fun `scoped extraction selects the reachable closure and roots`() {
        val dump = HprofTestDump()
        dump.clazz(100, "java/lang/Object")
        dump.clazz(101, "demo/Root", 100, fields = listOf("child" to HprofType.OBJECT))
        dump.clazz(102, "demo/Child", 100)
        dump.instance(1000, 101, 2000)
        dump.instance(2000, 102)
        dump.instance(3000, 102) // live but outside the scope
        dump.root(1000)
        dump.root(3000)

        val graph = extract(dump, foldLeaves = false, scopeClassName = "demo.Root").graph
        assertEquals(2L, graph.totalObjectCount)
        assertEquals(1, graph.roots.size)
        assertEquals(ClassName("demo.Root"), graph.classNameOf(graph.roots[0]))
    }
}
