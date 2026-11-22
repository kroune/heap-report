package shapes.hprof

import java.lang.foreign.Arena
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Callable
import java.util.concurrent.Executors

private fun nativeBuffer(arena: Arena, bytes: Long): ByteBuffer =
    arena.allocate(bytes).asByteBuffer().order(ByteOrder.nativeOrder())

/**
 * Address-ordered object identity: dense object ids are the ranks of object
 * addresses. Lookup is a span-table rank structure: 512-byte address spans index
 * into a u16-delta array, keeping each binary search to a handful of entries.
 */
class ObjectAddressTable private constructor(
    private val arena: Arena,
    val objectCount: Int,
    private val baseAddress: Long,
    /** First dense id of each span; one extra terminal entry. */
    private val spanFirstId: IntArray,
    /** Address deltas within the span, ordered by address; u16 each. */
    private val deltas: Array<ByteBuffer>,
) : AutoCloseable {
    fun denseIdOf(address: Long): Int {
        val span = ((address - baseAddress) ushr SPAN_SHIFT).toInt()
        if (span < 0 || span >= spanFirstId.size - 1) return -1
        val delta = (address - baseAddress - (span.toLong() shl SPAN_SHIFT)).toInt()
        var low = spanFirstId[span]
        var high = spanFirstId[span + 1] - 1
        while (low <= high) {
            val middle = (low + high) ushr 1
            val middleDelta = deltas[middle ushr DELTA_BLOCK_SHIFT]
                .getShort((middle and DELTA_BLOCK_MASK) shl 1).toInt() and 0xFFFF
            when {
                middleDelta < delta -> low = middle + 1
                middleDelta > delta -> high = middle - 1
                else -> return middle
            }
        }
        return -1
    }

    override fun close() = arena.close()

    companion object {
        const val SPAN_SHIFT = 9
        private const val DELTA_BLOCK_SHIFT = 28
        private const val DELTA_BLOCK_MASK = (1 shl DELTA_BLOCK_SHIFT) - 1
        private const val DELTA_BLOCK_CAPACITY = 1L shl DELTA_BLOCK_SHIFT

        private fun allocateDeltas(arena: Arena, objectCount: Int): Array<ByteBuffer> {
            val blockCount = ((objectCount.toLong() + DELTA_BLOCK_CAPACITY - 1) ushr DELTA_BLOCK_SHIFT).toInt()
            return Array(blockCount) { blockIndex ->
                val remaining = objectCount - (blockIndex.toLong() shl DELTA_BLOCK_SHIFT)
                arena.allocate(minOf(remaining, DELTA_BLOCK_CAPACITY) shl 1)
                    .asByteBuffer().order(ByteOrder.nativeOrder())
            }
        }

        private fun getDelta(deltas: Array<ByteBuffer>, index: Int): Short =
            deltas[index ushr DELTA_BLOCK_SHIFT].getShort((index and DELTA_BLOCK_MASK) shl 1)

        private fun setDelta(deltas: Array<ByteBuffer>, index: Int, value: Short) {
            deltas[index ushr DELTA_BLOCK_SHIFT].putShort((index and DELTA_BLOCK_MASK) shl 1, value)
        }

        /**
         * Builds the table from per-chunk address buffers via parallel
         * counting sort over spans. [addressBuffers] are freed by the caller.
         */
        fun build(
            addressBuffers: List<AddressBuffer>,
            knownMinAddress: Long = Long.MAX_VALUE,
            knownMaxAddress: Long = Long.MIN_VALUE,
            presorted: Boolean = false,
        ): ObjectAddressTable {
            var minAddress = knownMinAddress
            var maxAddress = knownMaxAddress
            var totalCount = 0L
            val discoverBounds = minAddress == Long.MAX_VALUE || maxAddress == Long.MIN_VALUE
            for (buffer in addressBuffers) {
                totalCount += buffer.size
                if (discoverBounds) {
                    buffer.forEach { address ->
                        if (address < minAddress) minAddress = address
                        if (address > maxAddress) maxAddress = address
                    }
                }
            }
            require(totalCount > 0 && totalCount <= Int.MAX_VALUE) {
                "unsupported object count $totalCount"
            }
            val objectCount = totalCount.toInt()
            val spanCount = (((maxAddress - minAddress) ushr SPAN_SHIFT) + 1).toInt()
            require(spanCount > 0 && spanCount < (1 shl 28)) {
                "address span too wide: $minAddress..$maxAddress"
            }

            if (presorted) {
                require(!discoverBounds) { "presorted construction requires known address bounds" }
                return buildPresorted(addressBuffers, objectCount, minAddress, spanCount)
            }

            val spanCounts = IntArray(spanCount + 1)
            for (buffer in addressBuffers) {
                buffer.forEach { address ->
                    spanCounts[((address - minAddress) ushr SPAN_SHIFT).toInt() + 1]++
                }
            }
            for (span in 1..spanCount) spanCounts[span] += spanCounts[span - 1]
            val spanFirstId = spanCounts // prefix-summed in place; [0]=0, terminal at [spanCount]

            val arena = Arena.ofShared()
            val deltas = allocateDeltas(arena, objectCount)

            // Partition spans across workers by object count; each worker scans
            // all buffers and places only its own spans, so no cursor races.
            val workerCount = Runtime.getRuntime().availableProcessors()
            val partitionBounds = IntArray(workerCount + 1)
            partitionBounds[workerCount] = spanCount
            var nextBoundary = 1
            for (span in 0 until spanCount) {
                while (nextBoundary < workerCount &&
                    spanFirstId[span] >= (totalCount * nextBoundary / workerCount)
                ) {
                    partitionBounds[nextBoundary++] = span
                }
            }
            while (nextBoundary < workerCount) partitionBounds[nextBoundary++] = spanCount

            val pool = Executors.newFixedThreadPool(workerCount)
            try {
                pool.invokeAll(
                    (0 until workerCount).map { worker ->
                        Callable {
                            val firstSpan = partitionBounds[worker]
                            val endSpan = partitionBounds[worker + 1]
                            if (firstSpan >= endSpan) return@Callable Unit
                            val cursors = IntArray(endSpan - firstSpan)
                            for (buffer in addressBuffers) {
                                buffer.forEach { address ->
                                    val span = ((address - minAddress) ushr SPAN_SHIFT).toInt()
                                    if (span in firstSpan until endSpan) {
                                        val position = spanFirstId[span] + cursors[span - firstSpan]++
                                        setDelta(
                                            deltas,
                                            position,
                                            (address - minAddress - (span.toLong() shl SPAN_SHIFT))
                                                .toShort(),
                                        )
                                    }
                                }
                            }
                            // insertion-sort each span
                            for (span in firstSpan until endSpan) {
                                sortSpan(deltas, spanFirstId[span], spanFirstId[span + 1])
                            }
                        }
                    }
                ).forEach { it.get() }
            } finally {
                pool.shutdown()
            }
            return ObjectAddressTable(arena, objectCount, minAddress, spanFirstId, deltas)
        }

        private fun buildPresorted(
            addressBuffers: List<AddressBuffer>,
            objectCount: Int,
            minAddress: Long,
            spanCount: Int,
        ): ObjectAddressTable {
            val arena = Arena.ofShared()
            val deltas = allocateDeltas(arena, objectCount)
            val spanFirstId = IntArray(spanCount + 1) { -1 }
            val firstIds = IntArray(addressBuffers.size)
            var total = 0
            for (bufferIndex in addressBuffers.indices) {
                firstIds[bufferIndex] = total
                total += addressBuffers[bufferIndex].size.toInt()
            }

            val pool = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors())
            try {
                pool.invokeAll(
                    addressBuffers.mapIndexed { bufferIndex, buffer ->
                        Callable {
                            var denseId = firstIds[bufferIndex]
                            var previousSpan = -1
                            buffer.forEach { address ->
                                val span = ((address - minAddress) ushr SPAN_SHIFT).toInt()
                                check(span >= previousSpan) { "presorted address buffer is not ascending" }
                                if (span != previousSpan) {
                                    if (denseId != firstIds[bufferIndex]) spanFirstId[span] = denseId
                                    previousSpan = span
                                }
                                setDelta(
                                    deltas,
                                    denseId,
                                    (address - minAddress - (span.toLong() shl SPAN_SHIFT)).toShort(),
                                )
                                denseId++
                            }
                        }
                    },
                ).forEach { it.get() }
            } finally {
                pool.shutdown()
            }

            // Adjacent buffers can share their boundary span; the earliest
            // buffer owns its first object regardless of worker completion order.
            for (bufferIndex in addressBuffers.indices) {
                val buffer = addressBuffers[bufferIndex]
                if (buffer.size == 0L) continue
                val span = ((buffer.firstAddress() - minAddress) ushr SPAN_SHIFT).toInt()
                val firstId = firstIds[bufferIndex]
                val current = spanFirstId[span]
                if (current < 0 || firstId < current) spanFirstId[span] = firstId
            }

            spanFirstId[spanCount] = objectCount
            var nextFirstId = objectCount
            for (span in spanCount - 1 downTo 0) {
                val firstId = spanFirstId[span]
                if (firstId >= 0) nextFirstId = firstId else spanFirstId[span] = nextFirstId
            }
            return ObjectAddressTable(arena, objectCount, minAddress, spanFirstId, deltas)
        }

        private fun sortSpan(deltas: Array<ByteBuffer>, start: Int, end: Int) {
            for (index in start + 1 until end) {
                val value = getDelta(deltas, index)
                val unsigned = value.toInt() and 0xFFFF
                var cursor = index - 1
                while (cursor >= start && (getDelta(deltas, cursor).toInt() and 0xFFFF) > unsigned) {
                    setDelta(deltas, cursor + 1, getDelta(deltas, cursor))
                    cursor--
                }
                setDelta(deltas, cursor + 1, value)
            }
        }
    }
}

/** Growable off-heap append-only long buffer; blocks double from 32 KiB to 16 MiB. */
class AddressBuffer(private val arena: Arena) {
    private val blocks = ArrayList<ByteBuffer>()
    private var blockCapacity = 0
    private var blockFill = 0
    var size = 0L
        private set

    fun firstAddress(): Long {
        require(size > 0)
        return blocks.first().getLong(0)
    }

    fun add(value: Long) {
        if (blockFill == blockCapacity) {
            blockCapacity = if (blockCapacity == 0) 4 shl 10 else minOf(blockCapacity * 2, 2 shl 20)
            blocks.add(nativeBuffer(arena, blockCapacity.toLong() shl 3))
            blockFill = 0
        }
        blocks.last().putLong(blockFill shl 3, value)
        blockFill++
        size++
    }

    inline fun forEach(crossinline action: (Long) -> Unit) {
        forEachBlock { block, count ->
            for (index in 0 until count) {
                action(block.getLong(index shl 3))
            }
        }
    }

    fun forEachBlock(action: (block: ByteBuffer, count: Int) -> Unit) {
        for (blockIndex in blocks.indices) {
            val block = blocks[blockIndex]
            val count = if (blockIndex == blocks.size - 1) blockFill else block.capacity() ushr 3
            action(block, count)
        }
    }
}
