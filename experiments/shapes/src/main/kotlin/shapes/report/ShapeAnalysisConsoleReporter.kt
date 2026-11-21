package shapes.report

import shapes.analysis.ShapeAnalysisResult

/** Prints the result summary: totals, roots histogram and the largest shapes with their edges. */
fun ShapeAnalysisResult.printSummary() = ShapeAnalysisConsoleReporter.print(this)

internal object ShapeAnalysisConsoleReporter {
    fun print(result: ShapeAnalysisResult) {
        println("reachable objects : ${result.reachableObjectCount}")
        println("unique shapes     : ${result.entries.size}")
        if (result.entries.isNotEmpty()) {
            val collapseRatio = result.reachableObjectCount.toDouble() / result.entries.size
            println("collapse ratio    : %.1fx".format(collapseRatio))
        }

        println("roots histogram   :")
        val sortedRoots = result.rootsHistogram.entries.sortedByDescending { it.value }
        for ((shapeId, count) in sortedRoots) {
            println("  %-56s x %s".format(result.entries[shapeId].label, count))
        }

        println("shapes (by shallowTotal):")
        val largestShapes = result.entries.sortedByDescending { it.shallowTotal }.take(20)
        for (entry in largestShapes) {
            val rowFormat = "  #%-4s %-52s %-9s count=%-7s shallowTotal=%-11s " +
                "min=%-7s max=%-7s roots=%s"
            println(
                rowFormat.format(
                    entry.id,
                    entry.label.take(52),
                    entry.kind,
                    entry.instanceCount,
                    entry.shallowTotal,
                    entry.shallowMin,
                    entry.shallowMax,
                    entry.rootCount,
                )
            )
            printEdges(result, entry.id)
        }
    }

    private fun printEdges(result: ShapeAnalysisResult, shapeId: Int) {
        val entry = result.entries[shapeId]
        val edges = result.edgesOf(shapeId)
        for (edge in edges.take(10)) {
            val child = result.entries[edge.childShapeId]
            println(
                "        -> #%-4s %-46s mult=%s%s".format(
                    edge.childShapeId,
                    child.label.take(46),
                    edge.multiplicity,
                    if (entry.edgesPerMember) " (per member)" else " (total)",
                )
            )
        }
        val remainingCount = edges.size - 10
        if (remainingCount > 0) println("        -> ... and $remainingCount more edges")
    }
}
