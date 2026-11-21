package shapes.analysis.internal

import it.unimi.dsi.fastutil.ints.IntArrays
import it.unimi.dsi.fastutil.ints.IntComparator
import shapes.graph.ClassName
import java.util.Arrays

/**
 * Weisfeiler–Lehman color refinement over one strongly connected component,
 * as incremental partition refinement in the Paige–Tarjan style.
 *
 * Round 0 partitions members by base color (class name and external edges).
 * Every later round refines classes by their members' sorted neighbor-color
 * lists. The partition only ever splits, so a member's list can change only
 * when a neighbor's class id changed in the previous round: only those
 * "dirty" members have their lists recomputed and compared; all other
 * members of a class share one list by construction. When a class splits,
 * its largest part (ties: the first in list order) keeps the class id and
 * its members stay in place; every other part moves to a fresh class. Each
 * member therefore moves O(log n) times, and total work is O((n + m) log n)
 * in the member and internal-edge counts regardless of the number of rounds.
 *
 * Class ids are abstract labels below the member count, allocated from a free
 * list while dirty classes are processed in ascending id order, so that ids
 * — and the list comparisons that use them — depend on the refinement
 * history alone. The refinement tree (each split appends its parts, in list
 * order, as children of the class's node) yields the final class order by
 * depth-first traversal. That order is canonical: isomorphic components
 * produce identical results.
 *
 * [maxRounds] bounds the number of rounds; a run that hits the bound returns
 * the last consistent partition with `converged = false` (a valid WL round,
 * but not equitable — callers must not derive per-member multiplicities
 * from it).
 */
internal class SccColorRefinement(
    private val classNames: Array<ClassName>,
    private val externalEdges: Array<out List<ChildShapeReference>>,
    private val internalEdges: Array<out List<Int>>,
    private val setChildren: Boolean,
    private val edgeComparator: Comparator<ChildShapeReference>,
    private val maxRounds: Int = Int.MAX_VALUE,
) {
    class Result(
        /** Members sorted by color rank, source order within a rank. */
        val orderedMemberIndices: IntArray,
        /** Dense color rank per member, 0 until distinct color count. */
        val colorRanks: IntArray,
        val converged: Boolean,
        val rounds: Int,
    )

    private val memberCount = classNames.size

    // All per-member state is allocated in refine(), after the early exits:
    // a uniform component (the common giant-SCC case) must not pay for it.

    // Class membership as doubly linked lists, one list per class id.
    private lateinit var classIdOf: IntArray
    private lateinit var classHead: IntArray
    private lateinit var classSize: IntArray
    private lateinit var nextMember: IntArray
    private lateinit var previousMember: IntArray

    // Refinement tree: node 0 is the virtual root; a class's current node is a leaf.
    private lateinit var nodeFirstChild: IntArray
    private lateinit var nodeLastChild: IntArray
    private lateinit var nodeNextSibling: IntArray
    private lateinit var nodeClass: IntArray
    private lateinit var classNode: IntArray
    private var nodeCount = 1

    // Forward adjacency as CSR, unboxed once.
    private lateinit var adjacencyOffsets: IntArray
    private lateinit var adjacencyTargets: IntArray

    // Reverse adjacency as CSR, for dirty propagation.
    private lateinit var inboundOffsets: IntArray
    private lateinit var inboundSources: IntArray

    /** Neighbor-color lists, one slot per adjacency entry; [listLengths] may be shorter under set semantics. */
    private lateinit var listColors: IntArray
    private lateinit var listLengths: IntArray

    private lateinit var dirtyMark: BooleanArray
    private val listComparator = IntComparator { first, second -> compareLists(first, second) }
    private val classThenListComparator = IntComparator { first, second ->
        val byClass = classIdOf[first].compareTo(classIdOf[second])
        if (byClass != 0) byClass else compareLists(first, second)
    }

    // Round state shared with splitClass.
    private lateinit var freeIds: IntArray
    private var freeCount = 0
    private var classCount = 0
    private lateinit var touched: IntArray
    private var touchedCount = 0
    private lateinit var partStarts: IntArray
    private lateinit var partEnds: IntArray
    private lateinit var partSizes: IntArray

    fun refine(): Result {
        val order = IntArray(memberCount) { it }
        if (memberCount <= 1 || hasUniformRegularColor()) {
            return Result(order, IntArray(memberCount), converged = true, rounds = 0)
        }
        allocateState()
        buildAdjacency()
        classCount = buildBasePartition(order)

        // Ids not taken by round-0 classes, popped in ascending order.
        freeIds = IntArray(memberCount - classCount)
        for (id in memberCount - 1 downTo classCount) freeIds[freeCount++] = id

        var dirtyMembers = IntArray(memberCount) { it }
        var dirtyCount = memberCount
        Arrays.fill(dirtyMark, true)
        var nextDirty = IntArray(memberCount)

        var rounds = 0
        var converged = false
        while (true) {
            if (classCount == memberCount || dirtyCount == 0) {
                converged = true
                break
            }
            if (rounds >= maxRounds) break
            rounds++

            // Phase 1: fresh lists for dirty members, then group them by class
            // (ascending id, which keeps id allocation structural).
            for (index in 0 until dirtyCount) recomputeList(dirtyMembers[index])
            IntArrays.quickSort(dirtyMembers, 0, dirtyCount, classThenListComparator)

            // Phase 2: split each class holding dirty members.
            touchedCount = 0
            var groupStart = 0
            while (groupStart < dirtyCount) {
                val classId = classIdOf[dirtyMembers[groupStart]]
                var groupEnd = groupStart + 1
                while (groupEnd < dirtyCount && classIdOf[dirtyMembers[groupEnd]] == classId) groupEnd++
                splitClass(classId, dirtyMembers, groupStart, groupEnd)
                groupStart = groupEnd
            }

            // Next round's dirty members: in-neighbors of every moved member.
            for (index in 0 until dirtyCount) dirtyMark[dirtyMembers[index]] = false
            var nextCount = 0
            for (touchedIndex in 0 until touchedCount) {
                val member = touched[touchedIndex]
                for (edge in inboundOffsets[member] until inboundOffsets[member + 1]) {
                    val source = inboundSources[edge]
                    if (!dirtyMark[source]) {
                        dirtyMark[source] = true
                        nextDirty[nextCount++] = source
                    }
                }
            }
            val swap = dirtyMembers
            dirtyMembers = nextDirty
            nextDirty = swap
            dirtyCount = nextCount
        }

        val colorRanks = IntArray(memberCount)
        val colorCount = assignRanks(colorRanks)
        restoreStableMemberOrder(order, colorRanks, colorCount)
        return Result(order, colorRanks, converged, rounds)
    }

    /** Equal degrees cannot split a uniform initial color, including under set semantics. */
    private fun hasUniformRegularColor(): Boolean {
        val degree = internalEdges[0].size
        for (memberIndex in 1 until memberCount) {
            if (compareBase(0, memberIndex) != 0 ||
                internalEdges[memberIndex].size != degree
            ) {
                return false
            }
        }
        return true
    }

    private fun allocateState() {
        classIdOf = IntArray(memberCount)
        classHead = IntArray(memberCount) { -1 }
        classSize = IntArray(memberCount)
        nextMember = IntArray(memberCount) { -1 }
        previousMember = IntArray(memberCount) { -1 }
        val nodeCapacity = 2 * memberCount + 1
        nodeFirstChild = IntArray(nodeCapacity) { -1 }
        nodeLastChild = IntArray(nodeCapacity) { -1 }
        nodeNextSibling = IntArray(nodeCapacity) { -1 }
        nodeClass = IntArray(nodeCapacity) { -1 }
        classNode = IntArray(memberCount)
        adjacencyOffsets = IntArray(memberCount + 1)
        inboundOffsets = IntArray(memberCount + 1)
        listLengths = IntArray(memberCount)
        dirtyMark = BooleanArray(memberCount)
        touched = IntArray(memberCount)
        partStarts = IntArray(memberCount + 1)
        partEnds = IntArray(memberCount + 1)
        partSizes = IntArray(memberCount + 1)
    }

    private fun buildAdjacency() {
        var edgeCount = 0L
        for (edges in internalEdges) edgeCount += edges.size.toLong()
        check(edgeCount <= Int.MAX_VALUE) { "Too many internal SCC edges: $edgeCount" }
        adjacencyTargets = IntArray(edgeCount.toInt())
        listColors = IntArray(edgeCount.toInt())
        inboundSources = IntArray(edgeCount.toInt())

        var fill = 0
        for (member in 0 until memberCount) {
            adjacencyOffsets[member] = fill
            val edges = internalEdges[member]
            for (edgeIndex in edges.indices) {
                val target = edges[edgeIndex]
                adjacencyTargets[fill++] = target
                inboundOffsets[target + 1]++
            }
        }
        adjacencyOffsets[memberCount] = fill
        for (member in 0 until memberCount) inboundOffsets[member + 1] += inboundOffsets[member]
        val inboundFill = inboundOffsets.copyOf(memberCount)
        for (member in 0 until memberCount) {
            for (edge in adjacencyOffsets[member] until adjacencyOffsets[member + 1]) {
                inboundSources[inboundFill[adjacencyTargets[edge]]++] = member
            }
        }
    }

    /** Sorts [scratch] by base color and fills classes 0 until count, each a root child. */
    private fun buildBasePartition(scratch: IntArray): Int {
        IntArrays.quickSort(scratch, IntComparator { first, second -> compareBase(first, second) })
        var classCount = 0
        for (position in 0 until memberCount) {
            val member = scratch[position]
            if (position == 0 || compareBase(scratch[position - 1], member) != 0) {
                val classId = classCount++
                classNode[classId] = appendChild(parent = 0, classId)
            }
            link(member, classCount - 1)
        }
        return classCount
    }

    /**
     * Splits one class given its dirty members (sorted by list) at
     * [groupStart] until [groupEnd]. Members not in the group all share one
     * list; they form the "unchanged" part.
     */
    private fun splitClass(classId: Int, dirty: IntArray, groupStart: Int, groupEnd: Int) {
        val dirtySize = groupEnd - groupStart
        val unchangedSize = classSize[classId] - dirtySize
        val unchangedMember = if (unchangedSize > 0) findUnchangedMember(classId) else -1

        // Parts in list order: runs of equal dirty lists, with the unchanged
        // part merged into the run of equal list or inserted before the first
        // greater run. A start of -1 marks the standalone unchanged part.
        var partCount = 0
        var unchangedPlaced = unchangedSize == 0
        var runStart = groupStart
        while (runStart < groupEnd) {
            var runEnd = runStart + 1
            while (runEnd < groupEnd && compareLists(dirty[runStart], dirty[runEnd]) == 0) runEnd++
            val comparison = if (unchangedPlaced) 1 else compareLists(unchangedMember, dirty[runStart])
            if (comparison < 0) {
                partStarts[partCount] = -1
                partSizes[partCount++] = unchangedSize
                unchangedPlaced = true
            }
            partStarts[partCount] = runStart
            partEnds[partCount] = runEnd
            partSizes[partCount++] = (runEnd - runStart) + if (comparison == 0) unchangedSize else 0
            if (comparison == 0) unchangedPlaced = true
            runStart = runEnd
        }
        if (!unchangedPlaced) {
            partStarts[partCount] = -1
            partSizes[partCount++] = unchangedSize
        }
        if (partCount == 1) return

        var kept = 0
        for (part in 1 until partCount) if (partSizes[part] > partSizes[kept]) kept = part

        // The class's node becomes interior with one child per part, in list order.
        val parentNode = classNode[classId]
        nodeClass[parentNode] = -1
        for (part in 0 until partCount) {
            val partClass = if (part == kept) classId else freeIds[--freeCount].also { classCount++ }
            classNode[partClass] = appendChild(parentNode, partClass)
            if (part == kept) continue

            val start = partStarts[part]
            if (start >= 0) {
                for (index in start until partEnds[part]) move(dirty[index], partClass)
            }
            val holdsUnchanged = unchangedSize > 0 &&
                (start < 0 || compareLists(unchangedMember, dirty[start]) == 0)
            if (holdsUnchanged) {
                // Every member of classId that is not dirty belongs to this part.
                var member = classHead[classId]
                while (member >= 0) {
                    val following = nextMember[member]
                    if (!dirtyMark[member]) move(member, partClass)
                    member = following
                }
            }
        }
    }

    private fun move(member: Int, classId: Int) {
        unlink(member)
        link(member, classId)
        touched[touchedCount++] = member
    }

    private fun findUnchangedMember(classId: Int): Int {
        var member = classHead[classId]
        while (member >= 0 && dirtyMark[member]) member = nextMember[member]
        check(member >= 0) { "class $classId has no unchanged member" }
        return member
    }

    private fun link(member: Int, classId: Int) {
        val head = classHead[classId]
        nextMember[member] = head
        previousMember[member] = -1
        if (head >= 0) previousMember[head] = member
        classHead[classId] = member
        classIdOf[member] = classId
        classSize[classId]++
    }

    private fun unlink(member: Int) {
        val classId = classIdOf[member]
        val previous = previousMember[member]
        val following = nextMember[member]
        if (previous >= 0) nextMember[previous] = following else classHead[classId] = following
        if (following >= 0) previousMember[following] = previous
        classSize[classId]--
    }

    private fun appendChild(parent: Int, classId: Int): Int {
        val node = nodeCount++
        nodeClass[node] = classId
        val last = nodeLastChild[parent]
        if (last < 0) nodeFirstChild[parent] = node else nodeNextSibling[last] = node
        nodeLastChild[parent] = node
        return node
    }

    private fun recomputeList(member: Int) {
        val start = adjacencyOffsets[member]
        val end = adjacencyOffsets[member + 1]
        for (edge in start until end) listColors[edge] = classIdOf[adjacencyTargets[edge]]
        sortSmall(listColors, start, end)
        var length = end - start
        if (setChildren && length > 1) {
            var unique = start + 1
            for (read in start + 1 until end) {
                val color = listColors[read]
                if (color != listColors[unique - 1]) listColors[unique++] = color
            }
            length = unique - start
        }
        listLengths[member] = length
    }

    private fun compareLists(first: Int, second: Int): Int {
        val firstLength = listLengths[first]
        val secondLength = listLengths[second]
        if (firstLength != secondLength) return firstLength.compareTo(secondLength)
        val firstStart = adjacencyOffsets[first]
        val secondStart = adjacencyOffsets[second]
        for (offset in 0 until firstLength) {
            val comparison = listColors[firstStart + offset].compareTo(listColors[secondStart + offset])
            if (comparison != 0) return comparison
        }
        return 0
    }

    /** Depth-first over the refinement tree: leaves in child order are the color ranks. */
    private fun assignRanks(colorRanks: IntArray): Int {
        val stack = IntArray(nodeCount)
        var depth = 0
        stack[depth++] = 0
        var rank = 0
        while (depth > 0) {
            val node = stack[--depth]
            val classId = nodeClass[node]
            if (classId >= 0) {
                var member = classHead[classId]
                while (member >= 0) {
                    colorRanks[member] = rank
                    member = nextMember[member]
                }
                rank++
                continue
            }
            // Push children in reverse so the first child is visited first.
            var childCount = 0
            var child = nodeFirstChild[node]
            while (child >= 0) {
                childCount++
                child = nodeNextSibling[child]
            }
            var slot = depth + childCount - 1
            child = nodeFirstChild[node]
            while (child >= 0) {
                stack[slot--] = child
                child = nodeNextSibling[child]
            }
            depth += childCount
        }
        return rank
    }

    /** Counting sort by rank keeps source order within each color. */
    private fun restoreStableMemberOrder(order: IntArray, colorRanks: IntArray, colorCount: Int) {
        val positions = IntArray(colorCount + 1)
        for (rank in colorRanks) positions[rank + 1]++
        for (rank in 1 until colorCount) positions[rank] += positions[rank - 1]
        for (member in 0 until memberCount) order[positions[colorRanks[member]]++] = member
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

    private companion object {
        /** Neighbor lists are tiny; insertion sort beats the general sorter's setup. */
        fun sortSmall(values: IntArray, from: Int, to: Int) {
            if (to - from > 32) {
                Arrays.sort(values, from, to)
                return
            }
            for (index in from + 1 until to) {
                val value = values[index]
                var insert = index - 1
                while (insert >= from && values[insert] > value) {
                    values[insert + 1] = values[insert]
                    insert--
                }
                values[insert + 1] = value
            }
        }
    }
}
