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
    private val setChildren: Boolean,
    private val edgeComparator: Comparator<ChildShapeReference>,
) {
    class Result(
        val orderedMemberIndices: List<Int>,
        val colorRanks: IntArray,
    )

    fun refine(): Result {
        val memberCount = classNames.size
        var internalEdgesColors: Array<List<Int>> = Array(memberCount) {
            emptyList()
        }
        var colorRanks = IntArray(memberCount)
        var previousDistinctColorCount = -1

        while (true) {
            val distinctColorsIndexes = (0 until memberCount).distinctBy {
                SccMemberColor(
                    className = classNames[it],
                    externalEdges = externalEdges[it],
                    internalEdgesColors[it]
                )
            }.sortedWith { first, second ->
                compare(
                    first, internalEdgesColors[first],
                    second, internalEdgesColors[second],
                )
            }
            val rankByColor = HashMap<SccMemberColor, Int>(distinctColorsIndexes.size * 2)
            distinctColorsIndexes.forEachIndexed { rank, distinctColorsIndex ->
                rankByColor[
                    SccMemberColor(
                        className = classNames[distinctColorsIndex],
                        externalEdges = externalEdges[distinctColorsIndex],
                        internalEdgesColors[distinctColorsIndex]
                    )
                ] = rank
            }
            colorRanks = IntArray(memberCount) {
                rankByColor[
                    SccMemberColor(
                        className = classNames[it],
                        externalEdges = externalEdges[it],
                        internalEdgesColors[it]
                    )
                ]!!
            }

            if (distinctColorsIndexes.size == previousDistinctColorCount) break
            if (distinctColorsIndexes.size == memberCount) break
            previousDistinctColorCount = distinctColorsIndexes.size

            internalEdgesColors = Array(memberCount) { memberIndex ->
                val neighborColors = internalEdges[memberIndex]
                    .map { colorRanks[it] }
                    .let { if (setChildren) it.distinct() else it }
                    .sorted()
                neighborColors
            }
        }
        val orderedMemberIndices = (0 until memberCount).sortedWith { first, second ->
            compare(
                first, internalEdgesColors[first],
                second, internalEdgesColors[second]
            )
        }
        return Result(orderedMemberIndices, colorRanks)
    }

    private fun compare(
        firstIndex: Int, firstInternalEdgesColors: List<Int>,
        secondIndex: Int, secondInternalEdgesColors: List<Int>,
    ): Int {
        classNames[firstIndex].compareTo(classNames[secondIndex]).also {
            if (it != 0)
                return it
        }

        val firstExternalEdges = externalEdges[firstIndex]
        val secondExternalEdges = externalEdges[secondIndex]
        firstExternalEdges.size.compareTo(secondExternalEdges.size).also {
            if (it != 0)
                return it
        }
        for (index in firstExternalEdges.indices) {
            edgeComparator.compare(
                firstExternalEdges[index],
                secondExternalEdges[index],
            ).also {
                if (it != 0)
                    return it
            }
        }

        firstInternalEdgesColors.size.compareTo(secondInternalEdgesColors.size).also {
            if (it != 0)
                return it
        }
        for (index in firstInternalEdgesColors.indices) {
            firstInternalEdgesColors[index].compareTo(secondInternalEdgesColors[index]).also {
                if (it != 0)
                    return it
            }
        }
        return 0

    }
}
