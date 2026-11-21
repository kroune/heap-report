package shapes.analysis.internal

import shapes.graph.AnalysisGraph
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout

/**
 * Object-to-component mapping; the sign-encoded working representation is
 * private. Off-heap (GC-managed arena) because it costs 4 B per object.
 */
internal class ComponentAssignment(
    @PublishedApi internal val encodedComponentIds: MemorySegment,
) {
    fun componentOf(objectId: Int): Int = -get(objectId) - 2

    internal fun get(objectId: Int): Int =
        encodedComponentIds.get(ValueLayout.JAVA_INT_UNALIGNED, objectId.toLong() shl 2)

    internal fun set(objectId: Int, value: Int) {
        encodedComponentIds.set(ValueLayout.JAVA_INT_UNALIGNED, objectId.toLong() shl 2, value)
    }
}

/**
 * Compact component membership: singleton members inline, multi-member lists
 * in CSR rows, and components over [scanThreshold] members scanned from the
 * assignment instead of being stored (the giant SCC would dominate memory).
 */
internal class ComponentMembers(
    val count: Int,
    @PublishedApi internal val sizes: IntArray,
    @PublishedApi internal val firstMembers: IntArray,
    @PublishedApi internal val multiMemberOffsets: IntArray,
    @PublishedApi internal val multiMemberIds: IntArray,
    @PublishedApi internal val assignment: ComponentAssignment,
    @PublishedApi internal val scopeSize: Int,
    @PublishedApi internal val scanThreshold: Int,
) {
    fun size(componentId: Int): Int = sizes[componentId]

    fun singletonMember(componentId: Int): Int = firstMembers[componentId]

    inline fun forEachMember(componentId: Int, action: (objectId: Int) -> Unit) {
        val memberCount = sizes[componentId]
        if (memberCount == 1) {
            action(firstMembers[componentId])
            return
        }
        if (memberCount > scanThreshold) {
            for (objectId in 0 until scopeSize) {
                if (assignment.componentOf(objectId) == componentId) action(objectId)
            }
            return
        }
        val row = -firstMembers[componentId] - 1
        for (index in multiMemberOffsets[row] until multiMemberOffsets[row + 1]) {
            action(multiMemberIds[index])
        }
    }
}

internal class StronglyConnectedComponents(
    val assignment: ComponentAssignment,
    val members: ComponentMembers,
)

/**
 * Iterative Tarjan over every node of the graph. Components are numbered in
 * completion order, which is reverse topological: every component's
 * references point at lower-numbered components.
 */
internal class ComponentFinder(private val graph: AnalysisGraph) {
    operator fun invoke(): StronglyConnectedComponents {
        val dfsIndexAndComponent = ComponentAssignment(
            Arena.ofAuto().allocate(graph.size.toLong() shl 2),
        )
        for (objectId in 0 until graph.size) dfsIndexAndComponent.set(objectId, UNVISITED)
        val onStackBits = LongArray((graph.size ushr 6) + 1)

        var tarjanStack = IntArray(INITIAL_STACK_CAPACITY)
        var tarjanStackSize = 0
        var nodeStack = IntArray(INITIAL_STACK_CAPACITY)
        var lowLinkStack = IntArray(INITIAL_STACK_CAPACITY)
        var nextReferenceStack = IntArray(INITIAL_STACK_CAPACITY)
        var dfsStackSize = 0
        var nextDfsIndex = 0
        var componentCount = 0

        for (startObjectId in 0 until graph.size) {
            if (dfsIndexAndComponent.get(startObjectId) != UNVISITED) continue

            dfsIndexAndComponent.set(startObjectId, nextDfsIndex++)
            tarjanStack = ensureCapacity(tarjanStack, tarjanStackSize + 1)
            tarjanStack[tarjanStackSize++] = startObjectId
            flipOnStack(onStackBits, startObjectId)

            nodeStack = ensureCapacity(nodeStack, dfsStackSize + 1)
            lowLinkStack = ensureCapacity(lowLinkStack, dfsStackSize + 1)
            nextReferenceStack = ensureCapacity(nextReferenceStack, dfsStackSize + 1)
            nodeStack[dfsStackSize] = startObjectId
            lowLinkStack[dfsStackSize] = dfsIndexAndComponent.get(startObjectId)
            nextReferenceStack[dfsStackSize] = 0
            dfsStackSize++

            while (dfsStackSize > 0) {
                val frameIndex = dfsStackSize - 1
                val objectId = nodeStack[frameIndex]
                val referenceIndex = nextReferenceStack[frameIndex]

                if (referenceIndex < graph.referenceCount(objectId)) {
                    nextReferenceStack[frameIndex] = referenceIndex + 1
                    val targetId = graph.referenceAt(objectId, referenceIndex)
                    if (targetId < 0) continue

                    if (dfsIndexAndComponent.get(targetId) == UNVISITED) {
                        dfsIndexAndComponent.set(targetId, nextDfsIndex++)
                        tarjanStack = ensureCapacity(tarjanStack, tarjanStackSize + 1)
                        tarjanStack[tarjanStackSize++] = targetId
                        flipOnStack(onStackBits, targetId)

                        nodeStack = ensureCapacity(nodeStack, dfsStackSize + 1)
                        lowLinkStack = ensureCapacity(lowLinkStack, dfsStackSize + 1)
                        nextReferenceStack = ensureCapacity(nextReferenceStack, dfsStackSize + 1)
                        nodeStack[dfsStackSize] = targetId
                        lowLinkStack[dfsStackSize] = dfsIndexAndComponent.get(targetId)
                        nextReferenceStack[dfsStackSize] = 0
                        dfsStackSize++
                    } else if (isOnStack(onStackBits, targetId)) {
                        val targetDfsIndex = dfsIndexAndComponent.get(targetId)
                        if (targetDfsIndex < lowLinkStack[frameIndex]) {
                            lowLinkStack[frameIndex] = targetDfsIndex
                        }
                    }
                    continue
                }

                val lowLink = lowLinkStack[frameIndex]
                dfsStackSize--
                if (dfsStackSize > 0 && lowLink < lowLinkStack[dfsStackSize - 1]) {
                    lowLinkStack[dfsStackSize - 1] = lowLink
                }
                if (lowLink == dfsIndexAndComponent.get(objectId)) {
                    while (true) {
                        val memberId = tarjanStack[--tarjanStackSize]
                        flipOnStack(onStackBits, memberId)
                        dfsIndexAndComponent.set(memberId, -(componentCount + 2))
                        if (memberId == objectId) break
                    }
                    componentCount++
                }
            }
        }

        val members = groupMembers(dfsIndexAndComponent, componentCount)
        return StronglyConnectedComponents(dfsIndexAndComponent, members)
    }

    private fun groupMembers(
        assignment: ComponentAssignment,
        componentCount: Int,
    ): ComponentMembers {
        val componentSizes = IntArray(componentCount)
        for (objectId in 0 until graph.size) {
            componentSizes[assignment.componentOf(objectId)]++
        }

        var multiMemberComponentCount = 0
        for (componentId in 0 until componentCount) {
            if (componentSizes[componentId] in 2..SCAN_THRESHOLD) multiMemberComponentCount++
        }
        val multiMemberOffsets = IntArray(multiMemberComponentCount + 1)
        val firstMembers = IntArray(componentCount) { -1 }
        var row = 0
        for (componentId in 0 until componentCount) {
            if (componentSizes[componentId] in 2..SCAN_THRESHOLD) {
                firstMembers[componentId] = -(row + 1)
                multiMemberOffsets[row + 1] =
                    multiMemberOffsets[row] + componentSizes[componentId]
                row++
            }
        }

        val multiMemberIds = IntArray(multiMemberOffsets[multiMemberComponentCount])
        val fillCursors = multiMemberOffsets.copyOf()
        for (objectId in 0 until graph.size) {
            val componentId = assignment.componentOf(objectId)
            if (componentSizes[componentId] == 1) {
                firstMembers[componentId] = objectId
            } else if (componentSizes[componentId] <= SCAN_THRESHOLD) {
                val memberRow = -firstMembers[componentId] - 1
                multiMemberIds[fillCursors[memberRow]++] = objectId
            }
        }
        return ComponentMembers(
            componentCount,
            componentSizes,
            firstMembers,
            multiMemberOffsets,
            multiMemberIds,
            assignment,
            graph.size,
            SCAN_THRESHOLD,
        )
    }

    private fun ensureCapacity(values: IntArray, requiredSize: Int): IntArray {
        if (requiredSize <= values.size) return values
        return values.copyOf(maxOf(requiredSize, values.size + (values.size ushr 1)))
    }

    private fun isOnStack(bits: LongArray, objectId: Int): Boolean =
        (bits[objectId ushr 6] ushr (objectId and 63)) and 1L != 0L

    private fun flipOnStack(bits: LongArray, objectId: Int) {
        bits[objectId ushr 6] = bits[objectId ushr 6] xor (1L shl (objectId and 63))
    }

    private companion object {
        const val UNVISITED = -1
        const val INITIAL_STACK_CAPACITY = 1 shl 16

        /** Components above this size are scanned, not stored. */
        const val SCAN_THRESHOLD = 1 shl 22
    }
}
