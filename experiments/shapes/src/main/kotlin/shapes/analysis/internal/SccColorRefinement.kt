package shapes.analysis.internal

import it.unimi.dsi.fastutil.ints.IntArrays
import it.unimi.dsi.fastutil.ints.IntComparator
import shapes.graph.ClassName
import java.util.Arrays

/** Weisfeiler–Lehman refinement over one strongly connected component. */
internal class SccColorRefinement(
    private val classNames: Array<ClassName>,
    private val externalEdges: Array<out List<ChildShapeReference>>,
    private val internalEdges: Array<out List<Int>>,
    private val setChildren: Boolean,
    private val edgeComparator: Comparator<ChildShapeReference>,
) {
    class Result(
        val orderedMemberIndices: IntArray,
        val colorRanks: IntArray,
    )

    fun refine(): Result {
        val memberCount = classNames.size
        val orderedMemberIndices = IntArray(memberCount) { it }
        val colorRanks = IntArray(memberCount)
        if (memberCount <= 1 || hasUniformRegularColor()) {
            return Result(orderedMemberIndices, colorRanks)
        }

        var previousDistinctColorCount = -1
        var internalColorOffsets: IntArray? = null
        var internalColors = IntArray(0)
        val comparator = IntComparator { first, second ->
            compare(first, second, internalColorOffsets, internalColors)
        }

        while (true) {
            val offsets = internalColorOffsets
            IntArrays.quickSort(orderedMemberIndices, comparator)

            var distinctColorCount = 0
            var previousMemberIndex = -1
            for (memberIndex in orderedMemberIndices) {
                if (previousMemberIndex < 0 ||
                    compare(previousMemberIndex, memberIndex, offsets, internalColors) != 0
                ) {
                    distinctColorCount++
                }
                colorRanks[memberIndex] = distinctColorCount - 1
                previousMemberIndex = memberIndex
            }

            if (distinctColorCount == previousDistinctColorCount ||
                distinctColorCount == memberCount
            ) {
                if (offsets != null) {
                    restoreStableMemberOrder(
                        orderedMemberIndices,
                        colorRanks,
                        distinctColorCount,
                        offsets,
                    )
                }
                break
            }
            previousDistinctColorCount = distinctColorCount

            if (offsets == null) {
                var internalEdgeCount = 0L
                for (edges in internalEdges) internalEdgeCount += edges.size.toLong()
                check(internalEdgeCount <= Int.MAX_VALUE) {
                    "Too many internal SCC edges: $internalEdgeCount"
                }
                internalColorOffsets = IntArray(memberCount + 1)
                internalColors = IntArray(internalEdgeCount.toInt())
            }
            updateInternalColors(
                colorRanks,
                internalColorOffsets,
                internalColors,
            )
        }
        return Result(orderedMemberIndices, colorRanks)
    }

    /** Equal degrees cannot split a uniform initial color, including under set semantics. */
    private fun hasUniformRegularColor(): Boolean {
        val degree = internalEdges[0].size
        for (memberIndex in 1 until classNames.size) {
            if (compareBase(0, memberIndex) != 0 ||
                internalEdges[memberIndex].size != degree
            ) {
                return false
            }
        }
        return true
    }

    /** Compacts each row in place; the backing array is sized once for all original edges. */
    private fun updateInternalColors(
        colorRanks: IntArray,
        offsets: IntArray,
        colors: IntArray,
    ) {
        var writeOffset = 0
        for (memberIndex in classNames.indices) {
            val rowStart = writeOffset
            offsets[memberIndex] = rowStart
            val edges = internalEdges[memberIndex]
            for (edgeIndex in edges.indices) {
                colors[writeOffset++] = colorRanks[edges[edgeIndex]]
            }
            Arrays.sort(colors, rowStart, writeOffset)
            if (setChildren && writeOffset - rowStart > 1) {
                var uniqueOffset = rowStart + 1
                for (readOffset in rowStart + 1 until writeOffset) {
                    val color = colors[readOffset]
                    if (color != colors[uniqueOffset - 1]) {
                        colors[uniqueOffset++] = color
                    }
                }
                writeOffset = uniqueOffset
            }
        }
        offsets[classNames.size] = writeOffset
    }

    /** Reuses the offsets as counting-sort positions to retain source order within each color. */
    private fun restoreStableMemberOrder(
        orderedMemberIndices: IntArray,
        colorRanks: IntArray,
        distinctColorCount: Int,
        positionsByColor: IntArray,
    ) {
        Arrays.fill(positionsByColor, 0)
        for (colorRank in colorRanks) positionsByColor[colorRank + 1]++
        for (rank in 1 until distinctColorCount) {
            positionsByColor[rank] += positionsByColor[rank - 1]
        }
        for (memberIndex in classNames.indices) {
            val colorRank = colorRanks[memberIndex]
            orderedMemberIndices[positionsByColor[colorRank]++] = memberIndex
        }
    }

    private fun compare(
        firstIndex: Int,
        secondIndex: Int,
        internalColorOffsets: IntArray?,
        internalColors: IntArray,
    ): Int {
        compareBase(firstIndex, secondIndex).also {
            if (it != 0) return it
        }

        if (internalColorOffsets != null) {
            val firstStart = internalColorOffsets[firstIndex]
            val secondStart = internalColorOffsets[secondIndex]
            val firstSize = internalColorOffsets[firstIndex + 1] - firstStart
            val secondSize = internalColorOffsets[secondIndex + 1] - secondStart
            firstSize.compareTo(secondSize).also {
                if (it != 0) return it
            }
            for (offset in 0 until firstSize) {
                internalColors[firstStart + offset]
                    .compareTo(internalColors[secondStart + offset])
                    .also {
                        if (it != 0) return it
                    }
            }
        }
        return 0
    }

    private fun compareBase(firstIndex: Int, secondIndex: Int): Int {
        val firstClassName = classNames[firstIndex].name
        val secondClassName = classNames[secondIndex].name
        if (firstClassName !== secondClassName) {
            firstClassName.compareTo(secondClassName).also {
                if (it != 0) return it
            }
        }

        val firstExternalEdges = externalEdges[firstIndex]
        val secondExternalEdges = externalEdges[secondIndex]
        firstExternalEdges.size.compareTo(secondExternalEdges.size).also {
            if (it != 0) return it
        }
        for (index in firstExternalEdges.indices) {
            edgeComparator.compare(
                firstExternalEdges[index],
                secondExternalEdges[index],
            ).also {
                if (it != 0) return it
            }
        }
        return 0
    }
}
