package shapes.analysis.internal

import shapes.graph.ClassName
import java.lang.management.ManagementFactory
import kotlin.test.Test
import kotlin.test.assertTrue

/** Run individual tests with IDEA's profiler. -Dwl.scale=N multiplies their load. */
class SccColorRefinementMemoryTest {
    private val scale = Integer.getInteger("wl.scale", 1)

    @Test
    fun `jfr38 small map cycles`() {
        val inputs = listOf(2, 4, 9, 32).map(::mapCycle)
        measure("jfr38 small map cycles") {
            var checksum = 0L
            repeat(2_000 * scale) {
                for (input in inputs) checksum += refine(input)
            }
            checksum
        }
    }

    @Test
    fun `jfr38 configuration scc`() {
        val input = configurationScc()
        measure("jfr38 configuration SCC") {
            var checksum = 0L
            repeat(100 * scale) { checksum += refine(input) }
            checksum
        }
    }

    @Test
    fun `large uniform scc`() {
        val input = uniformRing(13_050_000 * scale)
        measure("large uniform SCC") { refine(input) }
    }

    @Test
    fun `deep color propagation`() {
        val input = deepRing(1_000 * scale)
        measure("deep color propagation") { refine(input) }
    }

    /**
     * Real SCC member graphs exported from a heap (`-Dwl.fixtures=/dir` holding
     * `atn.txt` / `zip.txt`: first line member count, then one line per member
     * `classIndex target:mult ...`). Skipped when the directory is absent.
     */
    @Test
    fun `jfr38 exported sccs`() {
        val directory = System.getProperty("wl.fixtures") ?: return
        for (name in listOf("atn", "zip")) {
            val file = java.io.File(directory, "$name.txt")
            if (!file.exists()) continue
            val input = loadFixture(file)
            repeat(3) {
                val startedAt = System.nanoTime()
                val result = SccColorRefinement(
                    input.classNames,
                    input.externalEdges,
                    input.internalNeighbors,
                    input.setChildren,
                    edgeComparator,
                    maxRounds = Integer.getInteger("wl.maxRounds", 256),
                ).refine()
                val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000.0
                println(
                    "$name: ${input.classNames.size} members -> " +
                        "${result.colorRanks.max() + 1} colors, rounds=${result.rounds}, " +
                        "converged=${result.converged}, time=$elapsedMs ms"
                )
            }
        }
    }

    private fun loadFixture(file: java.io.File): Input {
        val lines = file.readLines()
        val size = lines[0].trim().toInt()
        val classNames = arrayOfNulls<ClassName>(size)
        val internal = arrayOfNulls<List<Int>>(size)
        val classCache = HashMap<Int, ClassName>()
        for (index in 0 until size) {
            val parts = lines[index + 1].trim().split(' ').filter { it.isNotEmpty() }
            classNames[index] = classCache.getOrPut(parts[0].toInt()) { ClassName("fixture.Class${parts[0]}") }
            val targets = ArrayList<Int>()
            for (part in parts.drop(1)) {
                val (target, multiplicity) = part.split(':')
                repeat(multiplicity.toInt()) { targets += target.toInt() }
            }
            internal[index] = targets
        }
        @Suppress("UNCHECKED_CAST")
        return Input(
            classNames as Array<ClassName>,
            Array(size) { emptyList() },
            internal as Array<List<Int>>,
        )
    }

    private fun refine(input: Input): Long {
        val result = SccColorRefinement(
            input.classNames,
            input.externalEdges,
            input.internalNeighbors,
            input.setChildren,
            edgeComparator,
        ).refine()
        return result.colorRanks.sum().toLong() + result.colorRanks.max() + 1
    }

    private fun measure(name: String, block: () -> Long) {
        val allocationBean = ManagementFactory.getThreadMXBean() as? com.sun.management.ThreadMXBean
        if (allocationBean?.isThreadAllocatedMemorySupported == true) {
            allocationBean.isThreadAllocatedMemoryEnabled = true
        }
        val allocatedBefore = allocationBean?.currentThreadAllocatedBytes ?: 0L
        val startedAt = System.nanoTime()
        val checksum = block()
        val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000.0
        val allocatedMiB = ((allocationBean?.currentThreadAllocatedBytes ?: 0L) - allocatedBefore) /
            (1024.0 * 1024.0)
        println("$name: time=$elapsedMs ms, allocated=$allocatedMiB MiB, checksum=$checksum")
        assertTrue(checksum > 0)
    }

    private class Input(
        val classNames: Array<ClassName>,
        val externalEdges: Array<List<ChildShapeReference>>,
        val internalNeighbors: Array<List<Int>>,
        val setChildren: Boolean = false,
    )

    private fun mapCycle(size: Int): Input {
        val entryClass = ClassName("java.util.LinkedHashMap\$Entry")
        val external = listOf(ChildShapeReference(0, 1), ChildShapeReference(1, 1))
        return Input(
            Array(size) { entryClass },
            Array(size) { external },
            Array(size) { listOf((it + size - 1) % size, (it + 1) % size) },
        )
    }

    /** Largest common configuration SCC in the retained run-jfr-38 graph. */
    private fun configurationScc(): Input {
        val size = 1_266
        val classes = arrayOf(
            ClassName("org.gradle.api.internal.artifacts.configurations.DefaultLegacyConfiguration_Decorated"),
            ClassName("org.gradle.api.internal.collections.DefaultDomainObjectSet"),
            ClassName("org.gradle.api.internal.collections.ElementShadow"),
            ClassName("org.gradle.api.internal.artifacts.dependencies.DefaultDependencySet"),
            ClassName("org.gradle.api.internal.artifacts.DefaultPublishArtifactSet"),
            ClassName("org.gradle.internal.event.ListenerBroadcast"),
            ClassName("java.lang.String"),
            ClassName("java.util.LinkedHashMap\$Entry"),
            ClassName("java.lang.Object[]"),
        )
        return Input(
            Array(size) { classes[it % classes.size] },
            Array(size) { index ->
                when {
                    index % 7 == 0 -> listOf(
                        ChildShapeReference(index % 31, 1),
                        ChildShapeReference(index % 31, 1),
                    )
                    index % 3 == 0 -> listOf(ChildShapeReference(index % 31, 1 + index % 4))
                    else -> emptyList()
                }
            },
            Array(size) { listOf((it + 1) % size, (it + 7) % size) },
            setChildren = true,
        )
    }

    private fun uniformRing(size: Int): Input {
        val nodeClass = ClassName("java.util.LinkedList\$Node")
        val external = emptyList<ChildShapeReference>()
        return Input(
            Array(size) { nodeClass },
            Array(size) { external },
            Array(size) { listOf((it + 1) % size) },
        )
    }

    private fun deepRing(size: Int): Input {
        val nodeClass = ClassName("stress.DeepRingNode")
        val external = emptyList<ChildShapeReference>()
        return Input(
            Array(size) { nodeClass },
            Array(size) { if (it == 0) listOf(ChildShapeReference(0, 1)) else external },
            Array(size) { listOf((it + 1) % size) },
        )
    }

    private companion object {
        val edgeComparator = Comparator<ChildShapeReference> { first, second ->
            val child = first.childShapeId.compareTo(second.childShapeId)
            if (child != 0) child else first.multiplicity.compareTo(second.multiplicity)
        }
    }
}
