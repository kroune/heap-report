package shapes.verification

import shapes.analysis.AnalysisScope
import shapes.analysis.ShapeAnalysisResult
import shapes.graph.AnalysisGraph
import java.util.ArrayDeque
import java.util.BitSet

/** Independently checks object, root, shallow-size and edge accounting invariants. */
object ShapeInvariantVerifier {
    fun verify(
        graph: AnalysisGraph,
        scope: AnalysisScope,
        result: ShapeAnalysisResult,
    ): List<String> {
        val violations = mutableListOf<String>()
        val rootIds = when (scope) {
            is AnalysisScope.ReachableFrom -> scope.roots
            is AnalysisScope.EntireGraph -> scope.rootsForHistogram
        }
        val truth = recomputeTruth(graph, scope)

        val shapeShallowTotal = result.entries.sumOf { it.shallowTotal }
        if (shapeShallowTotal != truth.shallowTotal) {
            violations += "inv1: Σ shallowTotal ($shapeShallowTotal) != " +
                "Σ shallow over R (${truth.shallowTotal})"
        }

        val shapeRootCount = result.entries.sumOf { it.rootCount }
        if (shapeRootCount != rootIds.size.toLong()) {
            violations += "inv2: Σ rootCount ($shapeRootCount) != roots (${rootIds.size})"
        }

        val shapeInstanceCount = result.entries.sumOf { it.instanceCount }
        if (shapeInstanceCount != truth.objectCount) {
            violations += "inv4: Σ instanceCount ($shapeInstanceCount) != |R| (${truth.objectCount})"
        }

        val edgesByShape = result.edges.groupBy { it.shapeId }
        var accountedReferenceCount = 0L
        for (entry in result.entries) {
            val externalMultiplicity = edgesByShape[entry.id].orEmpty()
                .sumOf { it.multiplicity }
            val collapsedInstanceCount = entry.instanceCount / entry.sccInstanceSize
            accountedReferenceCount += if (entry.edgesPerMember) {
                externalMultiplicity * entry.instanceCount
            } else {
                externalMultiplicity * collapsedInstanceCount
            }
            accountedReferenceCount += entry.internalEdgesPerInstance * collapsedInstanceCount
        }
        if (accountedReferenceCount != truth.referenceCount) {
            violations += "inv3: expanded edges + internal ($accountedReferenceCount) != " +
                "Σ refs in R (${truth.referenceCount})"
        }
        return violations
    }

    private class GraphTruth(
        val objectCount: Long,
        val shallowTotal: ULong,
        val referenceCount: Long,
    )

    private fun recomputeTruth(graph: AnalysisGraph, scope: AnalysisScope): GraphTruth {
        var objectCount = 0L
        var shallowTotal = 0UL
        var referenceCount = 0L

        when (scope) {
            is AnalysisScope.EntireGraph ->
                for (objectId in 0 until graph.size) {
                    objectCount++
                    shallowTotal += graph.shallowSize(objectId)
                    referenceCount += graph.referenceCount(objectId)
                }
            is AnalysisScope.ReachableFrom -> {
                val reachable = BitSet(graph.size)
                val pending = ArrayDeque<Int>()
                for (rootId in scope.roots) {
                    if (!reachable.get(rootId)) {
                        reachable.set(rootId)
                        pending.add(rootId)
                    }
                }
                while (pending.isNotEmpty()) {
                    val objectId = pending.removeLast()
                    objectCount++
                    shallowTotal += graph.shallowSize(objectId)
                    referenceCount += graph.referenceCount(objectId)
                    for (referenceIndex in 0 until graph.referenceCount(objectId)) {
                        val targetId = graph.referenceAt(objectId, referenceIndex)
                        if (targetId >= 0 && !reachable.get(targetId)) {
                            reachable.set(targetId)
                            pending.add(targetId)
                        }
                    }
                }
            }
        }
        for (aggregate in graph.leafShapes) {
            objectCount += aggregate.instanceCount
            shallowTotal += aggregate.shallowTotal
        }
        return GraphTruth(objectCount, shallowTotal, referenceCount)
    }
}
