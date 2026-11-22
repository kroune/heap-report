package shapes.analysis.internal

import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap
import shapes.graph.AnalysisGraph
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardOpenOption

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

/** File-backed primitive array whose resident pages can be reclaimed under pressure. */
@PublishedApi
internal class ComponentIntArray(size: Int) {
    private val values = mapComponentInts(size)

    operator fun get(index: Int): Int =
        values.get(ValueLayout.JAVA_INT_UNALIGNED, index.toLong() shl 2)

    operator fun set(index: Int, value: Int) {
        values.set(ValueLayout.JAVA_INT_UNALIGNED, index.toLong() shl 2, value)
    }
}

@PublishedApi
internal const val SCANNED_COMPONENT = Int.MIN_VALUE

private fun mapComponentInts(size: Int): MemorySegment {
    require(size >= 0)
    if (size == 0) return MemorySegment.ofArray(IntArray(0))
    val bytes = size.toLong() shl 2
    val path = Files.createTempFile("shapes-components", ".bin")
    try {
        FileChannel.open(path, StandardOpenOption.READ, StandardOpenOption.WRITE).use { channel ->
            channel.truncate(bytes)
            return channel.map(FileChannel.MapMode.READ_WRITE, 0, bytes, Arena.ofAuto())
        }
    } finally {
        Files.deleteIfExists(path)
    }
}

/**
 * Compact component membership: singleton members inline, multi-member lists
 * in CSR rows, and components over the scan threshold scanned from the
 * assignment instead of being stored (the giant SCC would dominate memory).
 * Component descriptors share one unlinked file mapping rather than live heap arrays.
 */
internal class ComponentMembers(
    val count: Int,
    @PublishedApi internal val descriptors: ComponentIntArray,
    @PublishedApi internal val scannedSizes: Int2IntOpenHashMap,
    @PublishedApi internal val multiMemberOffsets: IntArray,
    @PublishedApi internal val multiMemberIds: IntArray,
    @PublishedApi internal val assignment: ComponentAssignment,
    @PublishedApi internal val scopeSize: Int,
) {
    fun size(componentId: Int): Int {
        val descriptor = descriptors[componentId]
        if (descriptor >= 0) return 1
        if (descriptor == SCANNED_COMPONENT) return scannedSizes[componentId]
        val row = -descriptor - 1
        return multiMemberOffsets[row + 1] - multiMemberOffsets[row]
    }

    fun singletonMember(componentId: Int): Int = descriptors[componentId]

    inline fun forEachMember(componentId: Int, action: (objectId: Int) -> Unit) {
        val descriptor = descriptors[componentId]
        if (descriptor >= 0) {
            action(descriptor)
            return
        }
        if (descriptor == SCANNED_COMPONENT) {
            for (objectId in 0 until scopeSize) {
                if (assignment.componentOf(objectId) == componentId) action(objectId)
            }
            return
        }
        val row = -descriptor - 1
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
        val componentSizes = ComponentIntArray(graph.size)

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

            nodeStack = ensureCapacity(nodeStack, dfsStackSize + 1)
            lowLinkStack = ensureCapacity(lowLinkStack, dfsStackSize + 1)
            nextReferenceStack = ensureCapacity(nextReferenceStack, dfsStackSize + 1)
            nodeStack[dfsStackSize] = startObjectId
            lowLinkStack[dfsStackSize] = nextDfsIndex - 1
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

                    val targetState = dfsIndexAndComponent.get(targetId)
                    if (targetState == UNVISITED) {
                        dfsIndexAndComponent.set(targetId, nextDfsIndex++)
                        tarjanStack = ensureCapacity(tarjanStack, tarjanStackSize + 1)
                        tarjanStack[tarjanStackSize++] = targetId

                        nodeStack = ensureCapacity(nodeStack, dfsStackSize + 1)
                        lowLinkStack = ensureCapacity(lowLinkStack, dfsStackSize + 1)
                        nextReferenceStack = ensureCapacity(nextReferenceStack, dfsStackSize + 1)
                        nodeStack[dfsStackSize] = targetId
                        lowLinkStack[dfsStackSize] = nextDfsIndex - 1
                        nextReferenceStack[dfsStackSize] = 0
                        dfsStackSize++
                    } else if (targetState >= 0 && targetState < lowLinkStack[frameIndex]) {
                        lowLinkStack[frameIndex] = targetState
                    }
                    continue
                }

                val lowLink = lowLinkStack[frameIndex]
                dfsStackSize--
                if (dfsStackSize > 0 && lowLink < lowLinkStack[dfsStackSize - 1]) {
                    lowLinkStack[dfsStackSize - 1] = lowLink
                }
                if (lowLink == dfsIndexAndComponent.get(objectId)) {
                    var componentSize = 0
                    while (true) {
                        val memberId = tarjanStack[--tarjanStackSize]
                        dfsIndexAndComponent.set(memberId, -(componentCount + 2))
                        componentSize++
                        if (memberId == objectId) break
                    }
                    componentSizes[componentCount] = componentSize
                    componentCount++
                }
            }
        }

        val members = groupMembers(dfsIndexAndComponent, componentCount, componentSizes)
        return StronglyConnectedComponents(dfsIndexAndComponent, members)
    }

    private fun groupMembers(
        assignment: ComponentAssignment,
        componentCount: Int,
        descriptors: ComponentIntArray,
    ): ComponentMembers {
        var multiMemberComponentCount = 0
        var scannedComponentCount = 0
        for (componentId in 0 until componentCount) {
            val size = descriptors[componentId]
            if (size in 2..SCAN_THRESHOLD) multiMemberComponentCount++
            if (size > SCAN_THRESHOLD) scannedComponentCount++
        }
        val multiMemberOffsets = IntArray(multiMemberComponentCount + 1)
        val scannedSizes = Int2IntOpenHashMap(scannedComponentCount)
        var row = 0
        for (componentId in 0 until componentCount) {
            val size = descriptors[componentId]
            when {
                size == 1 -> descriptors[componentId] = Int.MAX_VALUE
                size <= SCAN_THRESHOLD -> {
                    descriptors[componentId] = -(row + 1)
                    multiMemberOffsets[row + 1] = multiMemberOffsets[row] + size
                    row++
                }
                else -> {
                    descriptors[componentId] = SCANNED_COMPONENT
                    scannedSizes[componentId] = size
                }
            }
        }

        val multiMemberIds = IntArray(multiMemberOffsets[multiMemberComponentCount])
        val fillCursors = multiMemberOffsets.copyOf()
        for (objectId in 0 until graph.size) {
            val componentId = assignment.componentOf(objectId)
            val descriptor = descriptors[componentId]
            if (descriptor >= 0) {
                descriptors[componentId] = objectId
            } else if (descriptor != SCANNED_COMPONENT) {
                val memberRow = -descriptor - 1
                multiMemberIds[fillCursors[memberRow]++] = objectId
            }
        }
        return ComponentMembers(
            componentCount,
            descriptors,
            scannedSizes,
            multiMemberOffsets,
            multiMemberIds,
            assignment,
            graph.size,
        )
    }

    private fun ensureCapacity(values: IntArray, requiredSize: Int): IntArray {
        if (requiredSize <= values.size) return values
        return values.copyOf(maxOf(requiredSize, values.size + (values.size ushr 1)))
    }

    private companion object {
        const val UNVISITED = -1
        const val INITIAL_STACK_CAPACITY = 1 shl 16

        /** Components above this size are scanned, not stored. */
        const val SCAN_THRESHOLD = 1 shl 22
    }
}
