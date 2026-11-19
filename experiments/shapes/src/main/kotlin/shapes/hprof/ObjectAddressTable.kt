package shapes.hprof

import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout
import java.util.concurrent.Callable
import java.util.concurrent.Executors

/**
 * Address-ordered object identity: dense object ids are the ranks of object
 * addresses. Lookup is a span-table rank structure: 4 KiB address spans index
 * into a u16-delta array, so the whole table costs ~2.1 B per object.
 */
class ObjectAddressTable private constructor(
    private val arena: Arena,
    val objectCount: Int,
    private val baseAddress: Long,
    /** First dense id of each span; one extra terminal entry. */
    private val spanFirstId: IntArray,
    /** Address deltas within the span, ordered by address; u16 each. */
    private val deltas: MemorySegment,
) : AutoCloseable {
    fun denseIdOf(address: Long): Int {
        val span = ((address - baseAddress) ushr SPAN_SHIFT).toInt()
        if (span < 0 || span >= spanFirstId.size - 1) return -1
        val delta = (address - baseAddress - (span.toLong() shl SPAN_SHIFT)).toInt()
        var low = spanFirstId[span]
        var high = spanFirstId[span + 1] - 1
        while (low <= high) {
            val middle = (low + high) ushr 1
            val middleDelta = deltas.get(U16, middle.toLong() shl 1).toInt() and 0xFFFF
            when {
                middleDelta < delta -> low = middle + 1
                middleDelta > delta -> high = middle - 1
                else -> return middle
            }
        }
        return -1
    }

    fun addressOf(denseId: Int): Long {
        var low = 0
        var high = spanFirstId.size - 2
        while (low < high) {
            val middle = (low + high + 1) ushr 1
            if (spanFirstId[middle] <= denseId) low = middle else high = middle - 1
        }
        val delta = deltas.get(U16, denseId.toLong() shl 1).toInt() and 0xFFFF
        return baseAddress + (low.toLong() shl SPAN_SHIFT) + delta
    }

    override fun close() = arena.close()

    companion object {
        const val SPAN_SHIFT = 12
        private val U16 = ValueLayout.JAVA_SHORT_UNALIGNED

        /**
         * Builds the table from per-chunk address buffers via parallel
         * counting sort over spans. [addressBuffers] are freed by the caller.
         */
        fun build(addressBuffers: List<AddressBuffer>): ObjectAddressTable {
            var minAddress = Long.MAX_VALUE
            var maxAddress = Long.MIN_VALUE
            var totalCount = 0L
            for (buffer in addressBuffers) {
                totalCount += buffer.size
                buffer.forEach { address ->
                    if (address < minAddress) minAddress = address
                    if (address > maxAddress) maxAddress = address
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

            val spanCounts = IntArray(spanCount + 1)
            for (buffer in addressBuffers) {
                buffer.forEach { address ->
                    spanCounts[((address - minAddress) ushr SPAN_SHIFT).toInt() + 1]++
                }
            }
            for (span in 1..spanCount) spanCounts[span] += spanCounts[span - 1]
            val spanFirstId = spanCounts // prefix-summed in place; [0]=0, terminal at [spanCount]

            val arena = Arena.ofShared()
            val deltas = arena.allocate(objectCount.toLong() shl 1)

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
                                        deltas.set(
                                            U16,
                                            position.toLong() shl 1,
                                            (address - minAddress - (span.toLong() shl SPAN_SHIFT))
                                                .toShort(),
                                        )
                                    }
                                }
                            }
                            // insertion-sort each span (tiny: ~60 objects)
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

        private fun sortSpan(deltas: MemorySegment, start: Int, end: Int) {
            for (index in start + 1 until end) {
                val value = deltas.get(U16, index.toLong() shl 1)
                val unsigned = value.toInt() and 0xFFFF
                var cursor = index - 1
                while (cursor >= start &&
                    (deltas.get(U16, cursor.toLong() shl 1).toInt() and 0xFFFF) > unsigned
                ) {
                    deltas.set(U16, (cursor + 1).toLong() shl 1, deltas.get(U16, cursor.toLong() shl 1))
                    cursor--
                }
                deltas.set(U16, (cursor + 1).toLong() shl 1, value)
            }
        }
    }
}

/** Growable off-heap append-only long buffer; blocks double from 32 KiB to 16 MiB. */
class AddressBuffer(private val arena: Arena) {
    private val blocks = ArrayList<MemorySegment>()
    private var blockCapacity = 0L
    private var blockFill = 0L
    var size = 0L
        private set

    fun add(value: Long) {
        if (blockFill == blockCapacity) {
            blockCapacity = if (blockCapacity == 0L) 4L shl 10 else minOf(blockCapacity * 2, 2L shl 20)
            blocks.add(arena.allocate(blockCapacity shl 3))
            blockFill = 0
        }
        blocks.last().set(ValueLayout.JAVA_LONG_UNALIGNED, blockFill shl 3, value)
        blockFill++
        size++
    }

    inline fun forEach(crossinline action: (Long) -> Unit) {
        forEachBlock { block, count ->
            for (index in 0 until count) {
                action(block.get(ValueLayout.JAVA_LONG_UNALIGNED, index shl 3))
            }
        }
    }

    fun forEachBlock(action: (block: MemorySegment, count: Long) -> Unit) {
        for (blockIndex in blocks.indices) {
            val block = blocks[blockIndex]
            val count = if (blockIndex == blocks.size - 1) blockFill else block.byteSize() ushr 3
            action(block, count)
        }
    }
}
