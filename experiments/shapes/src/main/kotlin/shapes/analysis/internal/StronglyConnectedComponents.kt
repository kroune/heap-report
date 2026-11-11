package shapes.analysis.internal

import shapes.graph.ObjectGraph

/** Object-to-component mapping; the sign-encoded working representation is private. */
internal class ComponentAssignment(private val encodedComponentIds: IntArray) {
    fun componentOf(objectId: Int): Int = -encodedComponentIds[objectId] - 2
}

/** Compact component membership with singleton members stored inline. */
internal class ComponentMembers(
    val count: Int,
    @PublishedApi internal val sizes: IntArray,
    @PublishedApi internal val firstMembers: IntArray,
    @PublishedApi internal val multiMemberOffsets: IntArray,
    @PublishedApi internal val multiMemberIds: IntArray,
) {
    fun size(componentId: Int): Int = sizes[componentId]

    fun singletonMember(componentId: Int): Int = firstMembers[componentId]

    inline fun forEachMember(componentId: Int, action: (objectId: Int) -> Unit) {
        if (sizes[componentId] == 1) {
            action(firstMembers[componentId])
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

/** Iterative Tarjan over the selected analysis objects. */
internal class StronglyConnectedComponentsFinder(private val graph: ObjectGraph) {
    fun find(scope: AnalysisObjectSet): StronglyConnectedComponents {
        val dfsIndexAndComponent = IntArray(graph.size) { UNVISITED }
        val onStackBits = LongArray((graph.size ushr 6) + 1)

        var tarjanStack = IntArray(INITIAL_STACK_CAPACITY)
        var tarjanStackSize = 0
        var nodeStack = IntArray(INITIAL_STACK_CAPACITY)
        var lowLinkStack = IntArray(INITIAL_STACK_CAPACITY)
        var nextReferenceStack = IntArray(INITIAL_STACK_CAPACITY)
        var dfsStackSize = 0
        var nextDfsIndex = 0
        var componentCount = 0

        for (scopeIndex in 0 until scope.objectCount) {
            val startObjectId = scope.objectIdAt(scopeIndex)
            if (dfsIndexAndComponent[startObjectId] != UNVISITED) continue

            dfsIndexAndComponent[startObjectId] = nextDfsIndex++
            tarjanStack = ensureCapacity(tarjanStack, tarjanStackSize + 1)
            tarjanStack[tarjanStackSize++] = startObjectId
            flipOnStack(onStackBits, startObjectId)

            nodeStack = ensureCapacity(nodeStack, dfsStackSize + 1)
            lowLinkStack = ensureCapacity(lowLinkStack, dfsStackSize + 1)
            nextReferenceStack = ensureCapacity(nextReferenceStack, dfsStackSize + 1)
            nodeStack[dfsStackSize] = startObjectId
            lowLinkStack[dfsStackSize] = dfsIndexAndComponent[startObjectId]
            nextReferenceStack[dfsStackSize] = 0
            dfsStackSize++

            while (dfsStackSize > 0) {
                val frameIndex = dfsStackSize - 1
                val objectId = nodeStack[frameIndex]
                val referenceIndex = nextReferenceStack[frameIndex]

                if (referenceIndex < graph.referenceCount(objectId)) {
                    nextReferenceStack[frameIndex] = referenceIndex + 1
                    val targetId = graph.referenceAt(objectId, referenceIndex)
                    if (!scope.membership.get(targetId)) continue

                    if (dfsIndexAndComponent[targetId] == UNVISITED) {
                        dfsIndexAndComponent[targetId] = nextDfsIndex++
                        tarjanStack = ensureCapacity(tarjanStack, tarjanStackSize + 1)
                        tarjanStack[tarjanStackSize++] = targetId
                        flipOnStack(onStackBits, targetId)

                        nodeStack = ensureCapacity(nodeStack, dfsStackSize + 1)
                        lowLinkStack = ensureCapacity(lowLinkStack, dfsStackSize + 1)
                        nextReferenceStack = ensureCapacity(nextReferenceStack, dfsStackSize + 1)
                        nodeStack[dfsStackSize] = targetId
                        lowLinkStack[dfsStackSize] = dfsIndexAndComponent[targetId]
                        nextReferenceStack[dfsStackSize] = 0
                        dfsStackSize++
                    } else if (isOnStack(onStackBits, targetId)) {
                        val targetDfsIndex = dfsIndexAndComponent[targetId]
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
                if (lowLink == dfsIndexAndComponent[objectId]) {
                    while (true) {
                        val memberId = tarjanStack[--tarjanStackSize]
                        flipOnStack(onStackBits, memberId)
                        dfsIndexAndComponent[memberId] = -(componentCount + 2)
                        if (memberId == objectId) break
                    }
                    componentCount++
                }
            }
        }

        val assignment = ComponentAssignment(dfsIndexAndComponent)
        val members = groupMembers(scope, assignment, componentCount)
        return StronglyConnectedComponents(assignment, members)
    }

    private fun groupMembers(
        scope: AnalysisObjectSet,
        assignment: ComponentAssignment,
        componentCount: Int,
    ): ComponentMembers {
        val componentSizes = IntArray(componentCount)
        for (scopeIndex in 0 until scope.objectCount) {
            val objectId = scope.objectIdAt(scopeIndex)
            componentSizes[assignment.componentOf(objectId)]++
        }

        var multiMemberComponentCount = 0
        for (componentId in 0 until componentCount) {
            if (componentSizes[componentId] > 1) multiMemberComponentCount++
        }
        val multiMemberOffsets = IntArray(multiMemberComponentCount + 1)
        val firstMembers = IntArray(componentCount) { -1 }
        var row = 0
        for (componentId in 0 until componentCount) {
            if (componentSizes[componentId] > 1) {
                firstMembers[componentId] = -(row + 1)
                multiMemberOffsets[row + 1] =
                    multiMemberOffsets[row] + componentSizes[componentId]
                row++
            }
        }

        val multiMemberIds = IntArray(multiMemberOffsets[multiMemberComponentCount])
        val fillCursors = multiMemberOffsets.copyOf()
        for (scopeIndex in 0 until scope.objectCount) {
            val objectId = scope.objectIdAt(scopeIndex)
            val componentId = assignment.componentOf(objectId)
            if (componentSizes[componentId] == 1) {
                firstMembers[componentId] = objectId
            } else {
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
    }
}
