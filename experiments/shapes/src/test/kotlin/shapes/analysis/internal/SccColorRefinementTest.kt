package shapes.analysis.internal

import shapes.graph.ClassName
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

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

    @Test
    fun `canonical order is invariant under member relabeling`() {
        val random = Random(2718)
        repeat(200) { testIndex ->
            val size = random.nextInt(2, 40)
            val classes = Array(size) { ClassName("Class${random.nextInt(3)}") }
            val external = Array(size) {
                List(random.nextInt(3)) {
                    ChildShapeReference(random.nextInt(4), random.nextInt(1, 3))
                }.sortedWith(edgeComparator)
            }
            val internal = Array(size) { List(random.nextInt(5)) { random.nextInt(size) } }
            val setChildren = testIndex % 3 == 0

            // Relabel members by a random permutation: member i becomes permutation[i].
            val permutation = (0 until size).shuffled(random)
            val relabeledClasses = Array(size) { classes[permutation.indexOf(it)] }
            val relabeledExternal = Array(size) { external[permutation.indexOf(it)] }
            val relabeledInternal = Array(size) { member ->
                internal[permutation.indexOf(member)].map { permutation[it] }
            }

            val original = SccColorRefinement(classes, external, internal, setChildren, edgeComparator).refine()
            val relabeled = SccColorRefinement(
                relabeledClasses, relabeledExternal, relabeledInternal, setChildren, edgeComparator,
            ).refine()

            assertContentEquals(
                IntArray(size) { original.colorRanks[permutation.indexOf(it)] },
                relabeled.colorRanks,
                "ranks must follow the relabeling, test=$testIndex",
            )
            assertEquals(original.converged, relabeled.converged)
        }
    }

    /**
     * Plain, non-incremental WL with the production id rule: round 0 orders
     * classes by base color with ids 0 until count; every later round splits
     * each class by its members' sorted neighbor-id lists into parts in list
     * order, the largest part (ties: first) keeps the id and the others take
     * ascending free ids, classes allocating in ascending id order. A split
     * class is replaced by its parts in the canonical class order. Stops when
     * nothing splits. Member order is (rank, source index).
     */
    private fun referenceRefine(
        classNames: Array<ClassName>,
        externalEdges: Array<List<ChildShapeReference>>,
        internalEdges: Array<List<Int>>,
        setChildren: Boolean,
    ): ReferenceResult {
        val memberCount = classNames.size
        val baseComparator = Comparator<Int> { first, second ->
            referenceCompareBase(first, second, classNames, externalEdges)
        }
        val idOf = IntArray(memberCount)
        var classes = ArrayList<RefClass>()
        for (member in (0 until memberCount).sortedWith(baseComparator)) {
            val last = classes.lastOrNull()
            if (last == null || baseComparator.compare(last.members[0], member) != 0) {
                classes += RefClass(classes.size, mutableListOf(member))
            } else {
                last.members += member
            }
            idOf[member] = classes.last().id
        }
        val freeIds = ArrayDeque((classes.size until memberCount).toList())

        while (classes.size < memberCount) {
            val lists = Array(memberCount) { member ->
                internalEdges[member]
                    .map { idOf[it] }
                    .let { if (setChildren) it.distinct() else it }
                    .sorted()
            }
            val listComparator = Comparator<Int> { first, second ->
                lists[first].size.compareTo(lists[second].size).also {
                    if (it != 0) return@Comparator it
                }
                for (index in lists[first].indices) {
                    lists[first][index].compareTo(lists[second][index]).also {
                        if (it != 0) return@Comparator it
                    }
                }
                0
            }
            val partsOf = classes.associateWith { refClass ->
                refClass.members.sortedWith(listComparator).fold(ArrayList<MutableList<Int>>()) { parts, member ->
                    if (parts.isEmpty() || listComparator.compare(parts.last()[0], member) != 0) {
                        parts += mutableListOf(member)
                    } else {
                        parts.last() += member
                    }
                    parts
                }
            }
            val splitClasses = classes.filter { partsOf.getValue(it).size > 1 }
            if (splitClasses.isEmpty()) break

            val replacement = HashMap<RefClass, List<RefClass>>()
            for (refClass in splitClasses.sortedBy { it.id }) {
                val parts = partsOf.getValue(refClass)
                val kept = parts.maxBy { it.size }
                replacement[refClass] = parts.map { part ->
                    val id = if (part === kept) refClass.id else freeIds.removeFirst()
                    for (member in part) idOf[member] = id
                    RefClass(id, part)
                }
            }
            classes = classes.flatMapTo(ArrayList()) { replacement[it] ?: listOf(it) }
        }

        val colorRanks = IntArray(memberCount)
        for ((rank, refClass) in classes.withIndex()) {
            for (member in refClass.members) colorRanks[member] = rank
        }
        val ordered = (0 until memberCount).sortedBy { colorRanks[it] }.toIntArray()
        return ReferenceResult(ordered, colorRanks)
    }

    private class RefClass(val id: Int, val members: MutableList<Int>)

    private fun referenceCompareBase(
        firstIndex: Int,
        secondIndex: Int,
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
        return 0
    }

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
