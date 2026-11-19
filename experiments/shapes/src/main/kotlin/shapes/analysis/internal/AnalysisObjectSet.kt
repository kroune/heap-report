package shapes.analysis.internal

import shapes.analysis.AnalysisScope
import shapes.graph.AnalysisGraph
import java.util.BitSet

internal class AnalysisObjectSet(
    val membership: BitSet,
    private val orderedObjectIds: IntArray?,
    val objectCount: Int,
    val rootIds: IntArray,
) {
    fun objectIdAt(index: Int): Int = orderedObjectIds?.get(index) ?: index
}

internal class AnalysisScopeResolver(private val graph: AnalysisGraph) {
    fun resolve(scope: AnalysisScope): AnalysisObjectSet = when (scope) {
        is AnalysisScope.EntireGraph -> AnalysisObjectSet(
            BitSet(graph.size).apply { set(0, graph.size) },
            orderedObjectIds = null,
            objectCount = graph.size,
            rootIds = scope.rootsForHistogram,
        )
        is AnalysisScope.ReachableFrom -> findReachableObjects(scope.roots)
    }

    private fun findReachableObjects(roots: IntArray): AnalysisObjectSet {
        val reachable = BitSet(graph.size)
        var orderedIds = IntArray(graph.size)
        var head = 0
        var tail = 0

        for (rootId in roots) {
            if (!reachable.get(rootId)) {
                reachable.set(rootId)
                orderedIds[tail++] = rootId
            }
        }
        while (head < tail) {
            val sourceId = orderedIds[head++]
            val referenceCount = graph.referenceCount(sourceId)
            for (referenceIndex in 0 until referenceCount) {
                val targetId = graph.referenceAt(sourceId, referenceIndex)
                if (targetId >= 0 && !reachable.get(targetId)) {
                    reachable.set(targetId)
                    orderedIds[tail++] = targetId
                }
            }
        }

        val coversEntireGraph = tail == graph.size
        if (!coversEntireGraph) orderedIds = orderedIds.copyOf(tail)
        return AnalysisObjectSet(
            reachable,
            orderedObjectIds = if (coversEntireGraph) null else orderedIds,
            objectCount = tail,
            rootIds = roots,
        )
    }
}
