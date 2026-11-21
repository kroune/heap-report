package shapes.cli

// Investigation tooling, parked during the pipeline refactoring. Both reports
// read a graph plus its components, so they come back as drivers over the
// cached outputs of `graph.findComponents()` once per-stage persistence
// exists; until then nothing wires them in. The code below is kept verbatim
// (minus the first package line) and compiles as a nested block comment.
/*

import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap
import shapes.analysis.internal.StronglyConnectedComponents
import shapes.analysis.internal.StronglyConnectedComponentsFinder
import shapes.graph.AnalysisGraph
import java.nio.file.Files
import java.nio.file.Path

/**
 * Investigation report for the largest strongly connected components: which
 * classes their members belong to, and the class-to-class internal edge
 * matrix. This is the tool for finding the bookkeeping edges that weld
 * unrelated object clusters into one giant SCC (the output names the hub
 * classes; mapping a hub to its field is then a source-reading exercise).
 */
internal object SccClassMatrixReport {
    private const val TOP_COMPONENTS = 5
    private const val MIN_MEMBERS = 1_000
    private const val TOP_EDGES = 100_000

    fun write(
        path: Path,
        graph: AnalysisGraph,
        components: StronglyConnectedComponents,
        progress: (String) -> Unit,
    ) {
        val componentIds = (0 until components.members.count)
            .filter { components.members.size(it) >= MIN_MEMBERS }
            .sortedByDescending { components.members.size(it) }
            .take(TOP_COMPONENTS)

        Files.newBufferedWriter(path).use { writer ->
            for (componentId in componentIds) {
                val memberCount = components.members.size(componentId)
                progress("scc-report: component $componentId, $memberCount members")

                val classCount = graph.classNames.size
                val membersByClass = LongArray(classCount)
                val outInternalByClass = LongArray(classCount)
                val inInternalByClass = LongArray(classCount)
                val edgeCounts = Long2LongOpenHashMap()
                var internalEdges = 0L
                var externalEdges = 0L

                components.members.forEachMember(componentId) { objectId ->
                    val sourceClass = graph.classIndex(objectId)
                    membersByClass[sourceClass]++
                    for (referenceIndex in 0 until graph.referenceCount(objectId)) {
                        val targetId = graph.referenceAt(objectId, referenceIndex)
                        if (targetId >= 0 &&
                            components.assignment.componentOf(targetId) == componentId
                        ) {
                            val targetClass = graph.classIndex(targetId)
                            internalEdges++
                            outInternalByClass[sourceClass]++
                            inInternalByClass[targetClass]++
                            edgeCounts.addTo(
                                (sourceClass.toLong() shl 32) or targetClass.toLong(),
                                1L,
                            )
                        } else {
                            externalEdges++
                        }
                    }
                }

                writer.write(
                    "scc\t$componentId\tmembers=$memberCount\t" +
                        "internalEdges=$internalEdges\texternalEdges=$externalEdges\n"
                )
                (0 until classCount)
                    .filter { membersByClass[it] > 0 }
                    .sortedByDescending { membersByClass[it] }
                    .forEach { classIndex ->
                        writer.write(
                            "class\t${graph.classNames[classIndex].name}\t" +
                                "${membersByClass[classIndex]}\t" +
                                "${outInternalByClass[classIndex]}\t" +
                                "${inInternalByClass[classIndex]}\n"
                        )
                    }
                edgeCounts.long2LongEntrySet()
                    .sortedByDescending { it.longValue }
                    .take(TOP_EDGES)
                    .forEach { entry ->
                        val sourceClass = (entry.longKey ushr 32).toInt()
                        val targetClass = entry.longKey.toInt()
                        writer.write(
                            "edge\t${graph.classNames[sourceClass].name}\t" +
                                "${graph.classNames[targetClass].name}\t${entry.longValue}\n"
                        )
                    }
            }
        }
        progress("scc-report written: $path")
    }
}

/**
 * SCC probe: recomputes components with class-to-class edges dropped, without
 * re-extracting. Each rule is `srcRegex->dstRegex` (full-match, `*` = any);
 * dropped edges are invisible to the finder only — the graph is unchanged.
 * This answers "which cut set un-welds the giant SCC" in one extraction.
 * When [reportPath] is given, the class matrix of the largest surviving
 * components is written for the probed graph.
 */
internal object SccEdgeDropProbe {
    fun run(
        graph: AnalysisGraph,
        spec: String,
        progress: (String) -> Unit,
        reportPath: Path? = null,
        splitSpec: String? = null,
        anchorClass: String? = null,
    ) {
        val probeGraph = dropping(graph, spec)
        val objects = shapes.analysis.internal.AnalysisScopeResolver(probeGraph)
            .resolve(shapes.analysis.AnalysisScope.EntireGraph(IntArray(0)))
        val components = StronglyConnectedComponentsFinder(probeGraph).find(objects)
        val sizes = (0 until components.members.count)
            .map { components.members.size(it) }
            .filter { it > 1 }
            .sortedDescending()
        val inCycles = sizes.sumOf { it.toLong() }
        progress(
            "probe [$spec]:\n" +
                "  components>1: ${sizes.size}, objects in cycles: $inCycles\n" +
                "  top sizes: ${sizes.take(12).joinToString(", ")}"
        )
        if (reportPath != null) {
            SccClassMatrixReport.write(reportPath, probeGraph, components, progress)
        }
        if (splitSpec != null) {
            reportCrossPieceEdges(graph, probeGraph, components, "$spec,$splitSpec", progress)
        }
        if (anchorClass != null) {
            reportAnchorAttribution(graph, probeGraph, components, Regex(anchorClass), progress)
            reportAnchorCycleWitness(graph, probeGraph, components, Regex(anchorClass), progress)
        }
    }

    /**
     * Concrete witness of a cross-anchor cycle: the shortest path from one
     * anchor instance to any other anchor instance inside the giant component,
     * and the shortest path back. Every edge on these paths is a candidate
     * weld; naming them is the proof the class tallies cannot give.
     */
    private fun reportAnchorCycleWitness(
        graph: AnalysisGraph,
        probeGraph: AnalysisGraph,
        components: StronglyConnectedComponents,
        anchorPattern: Regex,
        progress: (String) -> Unit,
    ) {
        val giant = (0 until components.members.count).maxByOrNull { components.members.size(it) }
            ?: return
        val classCount = graph.classNames.size
        val isAnchorClass = BooleanArray(classCount) { anchorPattern.matches(graph.classNames[it].name) }
        val anchors = ArrayList<Int>()
        components.members.forEachMember(giant) { objectId ->
            if (isAnchorClass[graph.classIndex(objectId)]) anchors.add(objectId)
        }
        if (anchors.size < 2) return
        val parent = IntArray(graph.size) { -1 }

        fun bfs(start: Int, isGoal: (Int) -> Boolean): Int {
            java.util.Arrays.fill(parent, -1)
            parent[start] = start
            var frontier = it.unimi.dsi.fastutil.ints.IntArrayList().apply { add(start) }
            while (!frontier.isEmpty) {
                val next = it.unimi.dsi.fastutil.ints.IntArrayList()
                for (frontierIndex in 0 until frontier.size) {
                    val objectId = frontier.getInt(frontierIndex)
                    for (referenceIndex in 0 until probeGraph.referenceCount(objectId)) {
                        val targetId = probeGraph.referenceAt(objectId, referenceIndex)
                        if (targetId < 0 || parent[targetId] >= 0) continue
                        if (components.assignment.componentOf(targetId) != giant) continue
                        parent[targetId] = objectId
                        if (isGoal(targetId)) return targetId
                        next.add(targetId)
                    }
                }
                frontier = next
            }
            return -1
        }

        fun pathOf(end: Int): String {
            val chain = ArrayList<Int>()
            var cursor = end
            while (parent[cursor] != cursor) {
                chain.add(cursor)
                cursor = parent[cursor]
            }
            chain.add(cursor)
            chain.reverse()
            return chain.joinToString("\n") { "      ${graph.classNames[graph.classIndex(it)].name} #$it" }
        }

        for (witness in 0 until 3) {
            val start = anchors[witness * (anchors.size / 3)]
            val other = bfs(start) { isAnchorClass[graph.classIndex(it)] && it != start }
            if (other < 0) {
                progress("  witness $witness: anchor #$start reaches no other anchor")
                continue
            }
            val forward = pathOf(other)
            val back = bfs(other) { it == start }
            val backward = if (back < 0) "      (no path back)" else pathOf(back)
            progress("  witness $witness forward:\n$forward\n  witness $witness back:\n$backward")
        }
    }

    /**
     * Attributes every member of the giant component to the anchor instance
     * (e.g. a Project object) that reaches it once edges INTO anchors are
     * dropped; objects reached from two anchors are "shared". Edges of the
     * giant between different attributions are the welds that make one
     * component out of per-anchor clusters.
     */
    private fun reportAnchorAttribution(
        graph: AnalysisGraph,
        probeGraph: AnalysisGraph,
        components: StronglyConnectedComponents,
        anchorPattern: Regex,
        progress: (String) -> Unit,
    ) {
        val giant = (0 until components.members.count).maxByOrNull { components.members.size(it) }
            ?: return
        val classCount = graph.classNames.size
        val isAnchorClass = BooleanArray(classCount) { anchorPattern.matches(graph.classNames[it].name) }
        val anchors = ArrayList<Int>()
        components.members.forEachMember(giant) { objectId ->
            if (isAnchorClass[graph.classIndex(objectId)]) anchors.add(objectId)
        }
        progress("  anchors in giant: ${anchors.size}")
        if (anchors.isEmpty()) return

        val none = -1
        val shared = -2
        val label = IntArray(graph.size) { none }
        val queue = it.unimi.dsi.fastutil.ints.IntArrayList()
        for ((anchorIndex, anchor) in anchors.withIndex()) {
            label[anchor] = anchorIndex
            queue.clear()
            queue.add(anchor)
            while (!queue.isEmpty) {
                val objectId = queue.removeInt(queue.size - 1)
                for (referenceIndex in 0 until probeGraph.referenceCount(objectId)) {
                    val targetId = probeGraph.referenceAt(objectId, referenceIndex)
                    if (targetId < 0) continue
                    if (isAnchorClass[graph.classIndex(targetId)]) continue // split edge
                    if (components.assignment.componentOf(targetId) != giant) continue
                    when (label[targetId]) {
                        none -> { label[targetId] = anchorIndex; queue.add(targetId) }
                        anchorIndex, shared -> {}
                        else -> label[targetId] = shared // reached from a second anchor; stop here
                    }
                }
            }
        }
        var sharedCount = 0L
        var noneCount = 0L
        val sharedByClass = LongArray(classCount)
        val direct = Long2LongOpenHashMap() // anchor X object -> anchor Y object
        val intoShared = Long2LongOpenHashMap()
        val outOfShared = Long2LongOpenHashMap()
        var directCount = 0L
        var intoSharedCount = 0L
        var outOfSharedCount = 0L
        components.members.forEachMember(giant) { objectId ->
            val sourceLabel = label[objectId]
            when (sourceLabel) {
                shared -> { sharedCount++; sharedByClass[graph.classIndex(objectId)]++ }
                none -> noneCount++
            }
            if (sourceLabel == none) return@forEachMember
            for (referenceIndex in 0 until probeGraph.referenceCount(objectId)) {
                val targetId = probeGraph.referenceAt(objectId, referenceIndex)
                if (targetId < 0 || components.assignment.componentOf(targetId) != giant) continue
                val targetLabel = label[targetId]
                if (targetLabel == sourceLabel) continue
                val key = (graph.classIndex(objectId).toLong() shl 32) or graph.classIndex(targetId).toLong()
                when {
                    sourceLabel == shared -> { outOfSharedCount++; outOfShared.addTo(key, 1L) }
                    targetLabel == shared -> { intoSharedCount++; intoShared.addTo(key, 1L) }
                    else -> { directCount++; direct.addTo(key, 1L) }
                }
            }
        }
        fun lines(tally: Long2LongOpenHashMap, limit: Int) = tally.long2LongEntrySet()
            .sortedByDescending { it.longValue }.take(limit)
            .joinToString("\n") { entry ->
                "    ${entry.longValue}\t${graph.classNames[(entry.longKey ushr 32).toInt()].name}" +
                    " -> ${graph.classNames[entry.longKey.toInt()].name}"
            }
        val sharedLines = (0 until classCount).filter { sharedByClass[it] > 0 }
            .sortedByDescending { sharedByClass[it] }.take(40)
            .joinToString("\n") { "    ${sharedByClass[it]}\t${graph.classNames[it].name}" }
        progress(
            "  attribution: shared=$sharedCount unreached=$noneCount\n" +
                "  shared objects by class:\n$sharedLines\n" +
                "  DIRECT anchor-X -> anchor-Y edges ($directCount):\n${lines(direct, 60)}\n" +
                "  shared -> anchor-X edges ($outOfSharedCount):\n${lines(outOfShared, 60)}\n" +
                "  anchor-X -> shared edges ($intoSharedCount):\n${lines(intoShared, 30)}"
        )
    }

    /**
     * With [splitSpec] additionally dropped the giant component shatters into
     * pieces; the edges of the giant component that cross between pieces are
     * exactly the welds. Tallies them by class pair.
     */
    private fun reportCrossPieceEdges(
        graph: AnalysisGraph,
        probeGraph: AnalysisGraph,
        components: StronglyConnectedComponents,
        splitSpec: String,
        progress: (String) -> Unit,
    ) {
        val giant = (0 until components.members.count).maxByOrNull { components.members.size(it) }
            ?: return
        val splitGraph = dropping(graph, splitSpec)
        val splitObjects = shapes.analysis.internal.AnalysisScopeResolver(splitGraph)
            .resolve(shapes.analysis.AnalysisScope.EntireGraph(IntArray(0)))
        val pieces = StronglyConnectedComponentsFinder(splitGraph).find(splitObjects)
        // Only edges between two big pieces (two different cores) are welds;
        // small pieces are satellites severed from their own core by the split.
        val bigPiece = BooleanArray(pieces.members.count) { pieces.members.size(it) >= 1000 }
        val tally = Long2LongOpenHashMap()
        var crossing = 0L
        components.members.forEachMember(giant) { objectId ->
            val sourcePiece = pieces.assignment.componentOf(objectId)
            if (!bigPiece[sourcePiece]) return@forEachMember
            for (referenceIndex in 0 until probeGraph.referenceCount(objectId)) {
                val targetId = probeGraph.referenceAt(objectId, referenceIndex)
                if (targetId < 0 || components.assignment.componentOf(targetId) != giant) continue
                val targetPiece = pieces.assignment.componentOf(targetId)
                if (targetPiece == sourcePiece || !bigPiece[targetPiece]) continue
                crossing++
                tally.addTo(
                    (graph.classIndex(objectId).toLong() shl 32) or graph.classIndex(targetId).toLong(),
                    1L,
                )
            }
        }
        val lines = tally.long2LongEntrySet()
            .sortedByDescending { it.longValue }
            .take(60)
            .joinToString("\n") { entry ->
                "    ${entry.longValue}\t${graph.classNames[(entry.longKey ushr 32).toInt()].name}" +
                    " -> ${graph.classNames[entry.longKey.toInt()].name}"
            }
        progress(
            "  big pieces: ${bigPiece.count { it }}, core-to-core edges in giant component: $crossing\n$lines"
        )
    }

    private fun dropping(graph: AnalysisGraph, spec: String): AnalysisGraph {
        val rules = spec.split(',').map(String::trim).filter(String::isNotEmpty).map { rule ->
            val parts = rule.split("->")
            require(parts.size == 2) { "probe rule must be src->dst: $rule" }
            fun compile(text: String) = if (text.trim() == "*") null else Regex(text.trim())
            compile(parts[0]) to compile(parts[1])
        }
        val classCount = graph.classNames.size
        val sourceMatch = Array(rules.size) { ruleIndex ->
            BooleanArray(classCount) { classIndex ->
                rules[ruleIndex].first?.matches(graph.classNames[classIndex].name) ?: true
            }
        }
        val targetMatch = Array(rules.size) { ruleIndex ->
            BooleanArray(classCount) { classIndex ->
                rules[ruleIndex].second?.matches(graph.classNames[classIndex].name) ?: true
            }
        }
        return object : AnalysisGraph by graph {
            override fun referenceAt(objectId: Int, referenceIndex: Int): Int {
                val targetId = graph.referenceAt(objectId, referenceIndex)
                if (targetId < 0) return targetId
                val sourceClass = graph.classIndex(objectId)
                val targetClass = graph.classIndex(targetId)
                for (ruleIndex in sourceMatch.indices) {
                    if (sourceMatch[ruleIndex][sourceClass] && targetMatch[ruleIndex][targetClass]) {
                        return Int.MIN_VALUE
                    }
                }
                return targetId
            }
        }
    }
}
*/
