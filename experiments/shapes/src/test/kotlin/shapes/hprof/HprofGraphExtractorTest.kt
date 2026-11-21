package shapes.hprof

import shapes.relaxation.FieldConcealmentRules
import shapes.relaxation.PatternFieldConcealment
import shapes.graph.ClassName
import java.nio.file.Files
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
        assertEquals(emptyList(), graph.targetsOf(holder))
        // the class instance is excluded, but its edge keeps the name alive
        assertEquals(2L, graph.totalObjectCount)
    }

    @Test
    fun `conceal-field star makes instances opaque leaves but keeps their targets alive`() {
        val dump = HprofTestDump()
        dump.clazz(100, "java/lang/Object")
        dump.clazz(101, "demo/Service", 100, fields = listOf("dep" to HprofType.OBJECT))
        dump.clazz(102, "demo/SubService", 101)
        dump.clazz(103, "demo/Holder", 100, fields = listOf("svc" to HprofType.OBJECT, "value" to HprofType.OBJECT))
        dump.clazz(104, "demo/Value", 100)
        dump.instance(1000, 103, 2000, 4000) // holder -> service, value
        dump.instance(2000, 102, 3000) // sub-service -> dependency (only reachable through it)
        dump.instance(3000, 104)
        dump.instance(4000, 104)
        dump.root(1000)

        val output = extract(
            dump,
            concealmentRules = FieldConcealmentRules(
                fieldsByPattern = listOf(
                    PatternFieldConcealment(Regex("demo\\.Service"), setOf(FieldConcealmentRules.ALL_FIELDS)),
                ),
            ),
        )
        val graph = output.graph
        val holder = graph.nodesOf("demo.Holder").single()
        // the subclass instance is a leaf now: the holder still points at it
        assertEquals(listOf("leaf:demo.SubService", "leaf:demo.Value"), graph.targetsOf(holder))
        // holder + service + 2 values: the dependency stays live (liveness-only extra)
        assertEquals(4L, graph.totalObjectCount)
        assertEquals(1L, output.counters.concealedDropped)
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

    @Test
    fun `treeified hash map bins expand and tree nodes project to plain nodes`() {
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
        dump.clazz(
            102,
            "java/util/LinkedHashMap\$Entry",
            101,
            fields = listOf("before" to HprofType.OBJECT, "after" to HprofType.OBJECT),
        )
        dump.clazz(
            103,
            "java/util/HashMap\$TreeNode",
            102,
            fields = listOf(
                "parent" to HprofType.OBJECT,
                "left" to HprofType.OBJECT,
                "right" to HprofType.OBJECT,
                "prev" to HprofType.OBJECT,
                "red" to HprofType.BOOLEAN,
            ),
        )
        dump.clazz(104, "java/util/HashMap\$Node[]", 100)
        dump.clazz(105, "demo/Key", 100)
        // tree root with one child; serialization order: own, Entry, Node fields
        dump.instance(1000, 103, 0, 1001, 0, 0, 0, 0, 0, 1, 3000, 3001, 1001)
        dump.instance(1001, 103, 1000, 0, 0, 1000, 0, 0, 0, 2, 3002, 3003, 0)
        dump.instance(3000, 105)
        dump.instance(3001, 105)
        dump.instance(3002, 105)
        dump.instance(3003, 105)
        dump.objectArray(2000, 104, 1000)
        dump.root(2000)

        val graph = extract(dump).graph
        val table = graph.nodesOf("java.util.HashMap\$Node[]").single()
        assertEquals(
            listOf("java.util.HashMap\$Node", "java.util.HashMap\$Node"),
            graph.targetsOf(table),
        )
        assertEquals(emptyList(), graph.nodesOf("java.util.HashMap\$TreeNode"))
        for (node in graph.nodesOf("java.util.HashMap\$Node")) {
            assertEquals(listOf("leaf:demo.Key", "leaf:demo.Key"), graph.targetsOf(node))
        }
        assertEquals(7L, graph.totalObjectCount)
    }

    @Test
    fun `tree map expands its entry tree and drops lazy views`() {
        val dump = HprofTestDump()
        dump.clazz(100, "java/lang/Object")
        dump.clazz(
            101,
            "java/util/TreeMap",
            100,
            fields = listOf(
                "comparator" to HprofType.OBJECT,
                "root" to HprofType.OBJECT,
                "entrySet" to HprofType.OBJECT,
                "navigableKeySet" to HprofType.OBJECT,
                "descendingMap" to HprofType.OBJECT,
                "keySet" to HprofType.OBJECT,
                "values" to HprofType.OBJECT,
            ),
        )
        dump.clazz(
            102,
            "java/util/TreeMap\$Entry",
            100,
            fields = listOf(
                "key" to HprofType.OBJECT,
                "value" to HprofType.OBJECT,
                "left" to HprofType.OBJECT,
                "right" to HprofType.OBJECT,
                "parent" to HprofType.OBJECT,
            ),
        )
        dump.clazz(103, "java/util/TreeMap\$EntrySet", 100, fields = listOf("this\$0" to HprofType.OBJECT))
        dump.clazz(105, "demo/Key", 100)
        dump.instance(1000, 101, 0, 2000, 4000, 0, 0, 0, 0)
        dump.instance(2000, 102, 3000, 3001, 2001, 2002, 0)
        dump.instance(2001, 102, 3002, 3003, 0, 0, 2000)
        dump.instance(2002, 102, 3004, 3005, 0, 0, 2000)
        dump.instance(4000, 103, 1000) // lazily instantiated view
        for (key in 3000L..3005L) dump.instance(key, 105)
        dump.root(1000)

        val graph = extract(dump).graph
        val map = graph.nodesOf("java.util.TreeMap").single()
        assertEquals(
            listOf("java.util.TreeMap\$Entry", "java.util.TreeMap\$Entry", "java.util.TreeMap\$Entry"),
            graph.targetsOf(map),
        )
        for (entry in graph.nodesOf("java.util.TreeMap\$Entry")) {
            // balance topology is gone; key and value stay
            assertEquals(listOf("leaf:demo.Key", "leaf:demo.Key"), graph.targetsOf(entry))
        }
        // the dropped view stays alive but carries no structure in either direction
        assertEquals(11L, graph.totalObjectCount)
        assertEquals(emptyList(), graph.nodesOf("java.util.TreeMap\$EntrySet"))
    }

    @Test
    fun `concurrent linked queue flattens its node chain`() {
        val dump = HprofTestDump()
        dump.clazz(100, "java/lang/Object")
        dump.clazz(
            101,
            "java/util/concurrent/ConcurrentLinkedQueue",
            100,
            fields = listOf("head" to HprofType.OBJECT, "tail" to HprofType.OBJECT),
        )
        dump.clazz(
            102,
            "java/util/concurrent/ConcurrentLinkedQueue\$Node",
            100,
            fields = listOf("item" to HprofType.OBJECT, "next" to HprofType.OBJECT),
        )
        dump.clazz(103, "demo/Item", 100)
        dump.instance(1000, 101, 2000, 2002)
        dump.instance(2000, 102, 3000, 2001)
        dump.instance(2001, 102, 3001, 2002)
        dump.instance(2002, 102, 3002, 2002) // self-linked: expansion must terminate
        dump.instance(3000, 103)
        dump.instance(3001, 103)
        dump.instance(3002, 103)
        dump.root(1000)

        val graph = extract(dump).graph
        val queue = graph.nodesOf("java.util.concurrent.ConcurrentLinkedQueue").single()
        assertEquals(3, graph.targetCount(queue))
        for (node in graph.nodesOf("java.util.concurrent.ConcurrentLinkedQueue\$Node")) {
            assertEquals(listOf("leaf:demo.Item"), graph.targetsOf(node))
        }
    }

    @Test
    fun `linked list spine flattens to the element set`() {
        val dump = HprofTestDump()
        dump.clazz(100, "java/lang/Object")
        dump.clazz(
            101,
            "java/util/LinkedList",
            100,
            fields = listOf("first" to HprofType.OBJECT, "last" to HprofType.OBJECT),
        )
        dump.clazz(
            102,
            "java/util/LinkedList\$Node",
            100,
            fields = listOf(
                "item" to HprofType.OBJECT,
                "next" to HprofType.OBJECT,
                "prev" to HprofType.OBJECT,
            ),
        )
        dump.clazz(103, "demo/Item", 100)
        dump.instance(1000, 101, 2000, 2002)
        dump.instance(2000, 102, 3000, 2001, 0)
        dump.instance(2001, 102, 3001, 2002, 2000)
        dump.instance(2002, 102, 3002, 0, 2001)
        dump.instance(3000, 103)
        dump.instance(3001, 103)
        dump.instance(3002, 103)
        dump.root(1000)

        val graph = extract(dump).graph
        val list = graph.nodesOf("java.util.LinkedList").single()
        assertEquals(3, graph.targetCount(list))
        for (node in graph.nodesOf("java.util.LinkedList\$Node")) {
            assertEquals(listOf("leaf:demo.Item"), graph.targetsOf(node))
        }
    }

    @Test
    fun `meta method index tables expand through both chain kinds`() {
        val dump = HprofTestDump()
        dump.clazz(100, "java/lang/Object")
        dump.clazz(
            101,
            "org/codehaus/groovy/runtime/metaclass/MetaMethodIndex\$Entry",
            100,
            fields = listOf(
                "nextHashEntry" to HprofType.OBJECT,
                "nextClassEntry" to HprofType.OBJECT,
                "methods" to HprofType.OBJECT,
            ),
        )
        dump.clazz(102, "org/codehaus/groovy/runtime/metaclass/MetaMethodIndex\$Entry[]", 100)
        dump.clazz(103, "demo/Method", 100)
        dump.instance(1000, 101, 1001, 0, 3000)
        dump.instance(1001, 101, 0, 1002, 3001)
        dump.instance(1002, 101, 0, 0, 3002)
        dump.instance(3000, 103)
        dump.instance(3001, 103)
        dump.instance(3002, 103)
        dump.objectArray(2000, 102, 1000)
        dump.root(2000)

        val graph = extract(dump).graph
        val table = graph.nodesOf("org.codehaus.groovy.runtime.metaclass.MetaMethodIndex\$Entry[]").single()
        assertEquals(3, graph.targetCount(table))
        for (entry in graph.nodesOf("org.codehaus.groovy.runtime.metaclass.MetaMethodIndex\$Entry")) {
            assertEquals(listOf("leaf:demo.Method"), graph.targetsOf(entry))
        }
    }

    @Test
    fun `weak hash map chains keep values while reference mechanics stay dropped`() {
        val dump = HprofTestDump()
        dump.clazz(100, "java/lang/Object")
        dump.clazz(
            201,
            "java/lang/ref/Reference",
            100,
            fields = listOf(
                "referent" to HprofType.OBJECT,
                "queue" to HprofType.OBJECT,
                "next" to HprofType.OBJECT,
                "discovered" to HprofType.OBJECT,
            ),
        )
        dump.clazz(202, "java/lang/ref/WeakReference", 201)
        dump.clazz(
            203,
            "java/util/WeakHashMap\$Entry",
            202,
            fields = listOf(
                "value" to HprofType.OBJECT,
                "next" to HprofType.OBJECT,
                "hash" to HprofType.INT,
            ),
        )
        dump.clazz(204, "java/util/WeakHashMap\$Entry[]", 100)
        dump.clazz(205, "demo/Value", 100)
        dump.clazz(206, "demo/Key", 100)
        // entry fields: own (value, next, hash), then Reference mechanics
        dump.instance(1000, 203, 3000, 1001, 1, 4000, 0, 0, 0)
        dump.instance(1001, 203, 3001, 0, 2, 4001, 0, 0, 0)
        dump.instance(3000, 205)
        dump.instance(3001, 205)
        dump.instance(4000, 206)
        dump.instance(4001, 206)
        dump.objectArray(2000, 204, 1000)
        dump.root(2000)

        val output = extract(dump)
        val graph = output.graph
        val table = graph.nodesOf("java.util.WeakHashMap\$Entry[]").single()
        assertEquals(2, graph.targetCount(table))
        for (entry in graph.nodesOf("java.util.WeakHashMap\$Entry")) {
            // the chained value is structural; the weakly-held key is not
            assertEquals(listOf("leaf:demo.Value"), graph.targetsOf(entry))
        }
        // table + 2 entries + 2 values + 2 weakly-held keys
        assertEquals(7L, graph.totalObjectCount)
        assertEquals(2L, output.counters.mechanicsDropped)
    }

    @Test
    fun `lazy map views drop from the map but stay alive`() {
        val dump = HprofTestDump()
        dump.clazz(100, "java/lang/Object")
        dump.clazz(
            101,
            "java/util/HashMap",
            100,
            fields = listOf(
                "table" to HprofType.OBJECT,
                "entrySet" to HprofType.OBJECT,
                "keySet" to HprofType.OBJECT,
                "values" to HprofType.OBJECT,
            ),
        )
        dump.clazz(102, "java/util/HashMap\$KeySet", 100, fields = listOf("this\$0" to HprofType.OBJECT))
        dump.clazz(103, "java/util/HashMap\$Node[]", 100)
        dump.instance(1000, 101, 2000, 0, 3000, 0)
        dump.instance(3000, 102, 1000) // view -> map back edge must not survive
        dump.objectArray(2000, 103)
        dump.root(1000)

        val graph = extract(dump).graph
        val map = graph.nodesOf("java.util.HashMap").single()
        // no map <-> view cycle in either direction; the empty table is a leaf
        assertEquals(listOf("leaf:java.util.HashMap\$Node[]"), graph.targetsOf(map))
        assertEquals(emptyList(), graph.nodesOf("java.util.HashMap\$KeySet"))
        assertEquals(3L, graph.totalObjectCount)
    }

    @Test
    fun `chm tree bins are walked through and concealed`() {
        val dump = HprofTestDump()
        dump.clazz(100, "java/lang/Object")
        dump.clazz(
            101,
            "java/util/concurrent/ConcurrentHashMap",
            100,
            fields = listOf("table" to HprofType.OBJECT, "keySet" to HprofType.OBJECT),
        )
        dump.clazz(
            102,
            "java/util/concurrent/ConcurrentHashMap\$Node",
            100,
            fields = listOf(
                "key" to HprofType.OBJECT,
                "val" to HprofType.OBJECT,
                "next" to HprofType.OBJECT,
            ),
        )
        dump.clazz(
            103,
            "java/util/concurrent/ConcurrentHashMap\$TreeBin",
            102,
            fields = listOf(
                "root" to HprofType.OBJECT,
                "first" to HprofType.OBJECT,
                "waiter" to HprofType.OBJECT,
                "lockState" to HprofType.INT,
            ),
        )
        dump.clazz(
            104,
            "java/util/concurrent/ConcurrentHashMap\$TreeNode",
            102,
            fields = listOf(
                "parent" to HprofType.OBJECT,
                "left" to HprofType.OBJECT,
                "right" to HprofType.OBJECT,
                "prev" to HprofType.OBJECT,
                "red" to HprofType.BOOLEAN,
            ),
        )
        dump.clazz(105, "java/util/concurrent/ConcurrentHashMap\$Node[]", 100)
        dump.clazz(106, "demo/Key", 100)
        dump.instance(1000, 101, 2000, 0)
        // tree bin: own (root, first, waiter, lockState), then Node (key, val, next)
        dump.instance(1500, 103, 1600, 1600, 0, 0, 0, 0, 0)
        // tree nodes: own (parent, left, right, prev, red), then Node fields
        dump.instance(1600, 104, 0, 1601, 0, 0, 0, 4000, 4001, 1601)
        dump.instance(1601, 104, 1600, 0, 0, 1600, 0, 4002, 4003, 0)
        for (key in 4000L..4003L) dump.instance(key, 106)
        dump.objectArray(2000, 105, 1500)
        dump.root(1000)

        val graph = extract(dump).graph
        val table = graph.nodesOf("java.util.concurrent.ConcurrentHashMap\$Node[]").single()
        assertEquals(
            listOf(
                "java.util.concurrent.ConcurrentHashMap\$Node",
                "java.util.concurrent.ConcurrentHashMap\$Node",
            ),
            graph.targetsOf(table),
        )
        assertEquals(emptyList(), graph.nodesOf("java.util.concurrent.ConcurrentHashMap\$TreeBin"))
        for (node in graph.nodesOf("java.util.concurrent.ConcurrentHashMap\$Node")) {
            assertEquals(listOf("leaf:demo.Key", "leaf:demo.Key"), graph.targetsOf(node))
        }
        // map + table + concealed bin + 2 nodes + 4 keys/values
        assertEquals(9L, graph.totalObjectCount)
    }

    @Test
    fun `cleaner chain links drop while thunks and buffers stay`() {
        val dump = HprofTestDump()
        dump.clazz(100, "java/lang/Object")
        dump.clazz(
            201,
            "java/lang/ref/Reference",
            100,
            fields = listOf(
                "referent" to HprofType.OBJECT,
                "queue" to HprofType.OBJECT,
                "next" to HprofType.OBJECT,
                "discovered" to HprofType.OBJECT,
            ),
        )
        dump.clazz(202, "java/lang/ref/PhantomReference", 201)
        dump.clazz(
            203,
            "jdk/internal/ref/Cleaner",
            202,
            fields = listOf(
                "next" to HprofType.OBJECT,
                "prev" to HprofType.OBJECT,
                "thunk" to HprofType.OBJECT,
            ),
        )
        dump.clazz(204, "java/nio/DirectByteBuffer", 100, fields = listOf("cleaner" to HprofType.OBJECT))
        dump.clazz(205, "demo/Deallocator", 100)
        // two buffers whose cleaners are adjacent in the global chain
        dump.instance(1000, 204, 2000)
        dump.instance(1001, 204, 2001)
        dump.instance(2000, 203, 2001, 0, 3000, 1000, 0, 0, 0)
        dump.instance(2001, 203, 0, 2000, 3001, 1001, 0, 0, 0)
        dump.instance(3000, 205)
        dump.instance(3001, 205)
        dump.root(1000)
        dump.root(1001)

        val graph = extract(dump).graph
        // chain position no longer differentiates: every cleaner is (thunk) only
        for (cleaner in graph.nodesOf("jdk.internal.ref.Cleaner")) {
            assertEquals(listOf("leaf:demo.Deallocator"), graph.targetsOf(cleaner))
        }
        assertEquals(6L, graph.totalObjectCount)
    }

    @Test
    fun `guava access ring flattens to the queue member set`() {
        val dump = HprofTestDump()
        dump.clazz(100, "java/lang/Object")
        dump.clazz(
            101,
            "com/google/common/cache/LocalCache\$AccessQueue",
            100,
            fields = listOf("head" to HprofType.OBJECT),
        )
        dump.clazz(
            102,
            "com/google/common/cache/LocalCache\$AccessQueue\$1",
            100,
            fields = listOf("nextAccess" to HprofType.OBJECT, "previousAccess" to HprofType.OBJECT),
        )
        dump.clazz(
            103,
            "com/google/common/cache/LocalCache\$StrongAccessEntry",
            100,
            fields = listOf(
                "key" to HprofType.OBJECT,
                "valueReference" to HprofType.OBJECT,
                "next" to HprofType.OBJECT,
                "nextAccess" to HprofType.OBJECT,
                "previousAccess" to HprofType.OBJECT,
            ),
        )
        dump.clazz(104, "demo/Key", 100)
        // ring: sentinel -> e1 -> e2 -> sentinel (doubly linked)
        dump.instance(1000, 101, 1500)
        dump.instance(1500, 102, 2000, 2001)
        dump.instance(2000, 103, 3000, 3001, 0, 2001, 1500)
        dump.instance(2001, 103, 3002, 3003, 0, 1500, 2000)
        for (key in 3000L..3003L) dump.instance(key, 104)
        dump.root(1000)

        val graph = extract(dump).graph
        val queue = graph.nodesOf("com.google.common.cache.LocalCache\$AccessQueue").single()
        // the ring SCC is gone: the queue holds its member set directly
        assertEquals(3, graph.targetCount(queue))
        for (entry in graph.nodesOf("com.google.common.cache.LocalCache\$StrongAccessEntry")) {
            assertEquals(listOf("leaf:demo.Key", "leaf:demo.Key"), graph.targetsOf(entry))
        }
        assertEquals(8L, graph.totalObjectCount)
    }

    @Test
    fun `reflective copy chains drop the root edge but keep the root object`() {
        val dump = HprofTestDump()
        dump.clazz(100, "java/lang/Object")
        dump.clazz(
            101,
            "java/lang/reflect/Method",
            100,
            fields = listOf("root" to HprofType.OBJECT, "signature" to HprofType.OBJECT),
        )
        dump.clazz(102, "demo/Sig", 100)
        dump.instance(1000, 101, 1001, 3000) // copy -> VM root duplicate
        dump.instance(1001, 101, 0, 3000)
        dump.instance(3000, 102)
        dump.root(1000)

        val graph = extract(dump).graph
        assertEquals(2, graph.nodesOf("java.lang.reflect.Method").size)
        for (method in graph.nodesOf("java.lang.reflect.Method")) {
            assertEquals(listOf("leaf:demo.Sig"), graph.targetsOf(method))
        }
        assertEquals(3L, graph.totalObjectCount)
    }
}
