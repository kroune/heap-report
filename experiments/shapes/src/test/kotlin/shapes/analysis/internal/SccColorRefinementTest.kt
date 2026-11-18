package shapes.analysis.internal

import shapes.graph.ClassName
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals

class SccColorRefinementTest {
    @Test
    fun `matches object-key reference implementation`() {
        val random = Random(83471)
        repeat(200) { testIndex ->
            val size = random.nextInt(1, 25)
            val classes = Array(size) { ClassName("Class${random.nextInt(4)}") }
            val external = Array(size) {
                List(random.nextInt(4)) {
                    ChildShapeReference(random.nextInt(5), random.nextInt(1, 4))
                }.sortedWith(edgeComparator)
            }
            val internal = Array(size) {
                List(random.nextInt(7)) { random.nextInt(size) }
            }
            val setChildren = testIndex % 2 == 0

            val expected = referenceRefine(classes, external, internal, setChildren)
            val actual = SccColorRefinement(
                classes,
                external,
                internal,
                setChildren,
                edgeComparator,
            ).refine()

            assertContentEquals(expected.colorRanks, actual.colorRanks, "colors, test=$testIndex")
            assertContentEquals(
                expected.orderedMemberIndices,
                actual.orderedMemberIndices,
                "member order, test=$testIndex",
            )
        }
    }

    private fun referenceRefine(
        classNames: Array<ClassName>,
        externalEdges: Array<List<ChildShapeReference>>,
        internalEdges: Array<List<Int>>,
        setChildren: Boolean,
    ): ReferenceResult {
        val memberCount = classNames.size
        var internalEdgesColors = Array(memberCount) { emptyList<Int>() }
        var colorRanks = IntArray(memberCount)
        var previousDistinctColorCount = -1
        while (true) {
            val distinctColorsIndexes = (0 until memberCount).distinctBy {
                ReferenceColor(classNames[it], externalEdges[it], internalEdgesColors[it])
            }.sortedWith { first, second ->
                referenceCompare(
                    first,
                    internalEdgesColors[first],
                    second,
                    internalEdgesColors[second],
                    classNames,
                    externalEdges,
                )
            }
            val rankByColor = HashMap<ReferenceColor, Int>(distinctColorsIndexes.size * 2)
            distinctColorsIndexes.forEachIndexed { rank, index ->
                rankByColor[
                    ReferenceColor(classNames[index], externalEdges[index], internalEdgesColors[index])
                ] = rank
            }
            colorRanks = IntArray(memberCount) {
                rankByColor[
                    ReferenceColor(classNames[it], externalEdges[it], internalEdgesColors[it])
                ]!!
            }
            if (distinctColorsIndexes.size == previousDistinctColorCount ||
                distinctColorsIndexes.size == memberCount
            ) {
                val ordered = (0 until memberCount).sortedWith { first, second ->
                    referenceCompare(
                        first,
                        internalEdgesColors[first],
                        second,
                        internalEdgesColors[second],
                        classNames,
                        externalEdges,
                    )
                }.toIntArray()
                return ReferenceResult(ordered, colorRanks)
            }
            previousDistinctColorCount = distinctColorsIndexes.size
            internalEdgesColors = Array(memberCount) { memberIndex ->
                internalEdges[memberIndex]
                    .map { colorRanks[it] }
                    .let { if (setChildren) it.distinct() else it }
                    .sorted()
            }
        }
    }

    private fun referenceCompare(
        firstIndex: Int,
        firstInternalColors: List<Int>,
        secondIndex: Int,
        secondInternalColors: List<Int>,
        classNames: Array<ClassName>,
        externalEdges: Array<List<ChildShapeReference>>,
    ): Int {
        classNames[firstIndex].compareTo(classNames[secondIndex]).also {
            if (it != 0) return it
        }
        val firstExternal = externalEdges[firstIndex]
        val secondExternal = externalEdges[secondIndex]
        firstExternal.size.compareTo(secondExternal.size).also {
            if (it != 0) return it
        }
        for (index in firstExternal.indices) {
            edgeComparator.compare(firstExternal[index], secondExternal[index]).also {
                if (it != 0) return it
            }
        }
        firstInternalColors.size.compareTo(secondInternalColors.size).also {
            if (it != 0) return it
        }
        for (index in firstInternalColors.indices) {
            firstInternalColors[index].compareTo(secondInternalColors[index]).also {
                if (it != 0) return it
            }
        }
        return 0
    }

    private data class ReferenceColor(
        val className: ClassName,
        val externalEdges: List<ChildShapeReference>,
        val internalColors: List<Int>,
    )

    private class ReferenceResult(
        val orderedMemberIndices: IntArray,
        val colorRanks: IntArray,
    )

    private companion object {
        val edgeComparator = Comparator<ChildShapeReference> { first, second ->
            val child = first.childShapeId.compareTo(second.childShapeId)
            if (child != 0) child else first.multiplicity.compareTo(second.multiplicity)
        }
    }
}
