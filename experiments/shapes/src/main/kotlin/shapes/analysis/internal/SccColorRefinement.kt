package shapes.analysis.internal

import shapes.graph.ClassName

internal data class SccMemberColor(
    val className: ClassName,
    val externalEdges: List<ChildShapeReference>,
    val internalEdgesColors: List<Int>,
)

/** Weisfeiler–Lehman refinement over one strongly connected component. */
internal class SccColorRefinement(
    private val classNames: Array<ClassName>,
    private val externalEdges: Array<out List<ChildShapeReference>>,
    private val internalEdges: Array<out List<Int>>,
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
            SccMemberColor(
                className = classNames[memberIndex],
                externalEdges = externalEdges[memberIndex],
                internalEdgesColors = emptyList()
            )
        }
        var colorRanks = IntArray(memberCount)
        var previousDistinctColorCount = -1

        while (true) {
            val distinctColors = colors.distinct().sortedWith(::compare)
            val rankByColor = HashMap<SccMemberColor, Int>(distinctColors.size * 2)
            distinctColors.forEachIndexed { rank, color ->
                rankByColor[color] = rank
            }
            colorRanks = IntArray(memberCount) { rankByColor.getValue(colors[it]) }

            if (distinctColors.size == previousDistinctColorCount) break
            if (distinctColors.size == memberCount) break
            previousDistinctColorCount = distinctColors.size

            colors = Array(memberCount) { memberIndex ->
                val neighborColors = internalEdges[memberIndex]
                    .map { colorRanks[it] }
                    .let { if (setSemantics) it.distinct() else it }
                    .sorted()
                SccMemberColor(
                    className = colors[memberIndex].className,
                    externalEdges = colors[memberIndex].externalEdges,
                    internalEdgesColors = neighborColors,
                )
            }
        }
        return Result(colors, colorRanks)
    }

    fun compare(first: SccMemberColor, second: SccMemberColor): Int {
        first.className.name.compareTo(second.className.name).also {
            if (it != 0)
                return it
        }

        first.externalEdges.size.compareTo(second.externalEdges.size).also {
            if (it != 0)
                return it
        }
        for (index in first.externalEdges.indices) {
            edgeComparator.compare(
                first.externalEdges[index],
                second.externalEdges[index],
            ).also {
                if (it != 0)
                    return it
            }
        }

        first.internalEdgesColors.size.compareTo(second.internalEdgesColors.size).also {
            if (it != 0)
                return it
        }
        for (index in first.internalEdgesColors.indices) {
            first.internalEdgesColors[index].compareTo(second.internalEdgesColors[index]).also {
                if (it != 0)
                    return it
            }
        }
        return 0
    }
}
