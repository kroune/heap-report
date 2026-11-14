package shapes.analysis.internal

import shapes.graph.ClassName

internal data class SccMemberColor(
    val className: ClassName,
    val externalEdges: List<ChildShapeReference>,
    val internalNeighborColors: List<Int>,
)

/** Weisfeiler–Lehman refinement over one strongly connected component. */
internal class SccColorRefinement(
    private val classNames: Array<ClassName>,
    private val externalEdges: Array<out List<ChildShapeReference>>,
    private val internalNeighbors: Array<out List<Int>>,
    private val setSemantics: Boolean,
    private val edgeComparator: Comparator<ChildShapeReference>,
) {
    class Result(
        val colors: Array<SccMemberColor>,
        val colorRanks: IntArray,
    )

    fun refine(): Result {
        val memberCount = classNames.size
        var colors = Array(memberCount) { memberIndex ->
            SccMemberColor(classNames[memberIndex], externalEdges[memberIndex], emptyList())
        }
        var colorRanks = IntArray(memberCount)
        var previousDistinctColorCount = -1

        while (true) {
            val distinctColors = colors.distinct().sortedWith(::compare)
            val rankByColor = HashMap<SccMemberColor, Int>(distinctColors.size * 2)
            distinctColors.forEachIndexed { rank, color -> rankByColor[color] = rank }
            colorRanks = IntArray(memberCount) { rankByColor.getValue(colors[it]) }

            if (distinctColors.size == previousDistinctColorCount) break
            if (distinctColors.size == memberCount) break
            previousDistinctColorCount = distinctColors.size

            colors = Array(memberCount) { memberIndex ->
                val neighborColors = internalNeighbors[memberIndex]
                    .map { colorRanks[it] }
                    .let { if (setSemantics) it.distinct() else it }
                    .sorted()
                SccMemberColor(
                    colors[memberIndex].className,
                    colors[memberIndex].externalEdges,
                    neighborColors,
                )
            }
        }
        return Result(colors, colorRanks)
    }

    fun compare(first: SccMemberColor, second: SccMemberColor): Int {
        var comparison = first.className.name.compareTo(second.className.name)
        if (comparison != 0) return comparison

        comparison = first.externalEdges.size.compareTo(second.externalEdges.size)
        if (comparison != 0) return comparison
        for (index in first.externalEdges.indices) {
            comparison = edgeComparator.compare(
                first.externalEdges[index],
                second.externalEdges[index],
            )
            if (comparison != 0) {
                return comparison
            }
        }

        comparison = first.internalNeighborColors.size.compareTo(second.internalNeighborColors.size)
        if (comparison != 0) {
            return comparison
        }
        for (index in first.internalNeighborColors.indices) {
            comparison = first.internalNeighborColors[index].compareTo(second.internalNeighborColors[index])
            if (comparison != 0) {
                return comparison
            }
        }
        return 0
    }
}
