package shapes.input.hprof

import java.nio.ByteBuffer
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Path
import java.nio.file.StandardOpenOption

private const val SEGMENT_SHIFT = 28
private const val SEGMENT_SIZE = 1L shl SEGMENT_SHIFT
private const val SEGMENT_MASK = SEGMENT_SIZE - 1

/** Segmented because a single MappedByteBuffer cannot exceed 2 GiB. */
internal class MappedLongArray(path: Path, val size: Int) : AutoCloseable {
    private val channel = FileChannel.open(path, StandardOpenOption.READ)
    private val segments = mapSegments(channel, size.toLong() * Long.SIZE_BYTES)

    operator fun get(index: Int): Long {
        val byteOffset = index.toLong() * Long.SIZE_BYTES
        return segments[(byteOffset ushr SEGMENT_SHIFT).toInt()]
            .getLong((byteOffset and SEGMENT_MASK).toInt())
    }

    override fun close() = channel.close()
}

internal class MappedIntArray(path: Path, val size: Int) : AutoCloseable {
    private val channel = FileChannel.open(path, StandardOpenOption.READ)
    private val segments = mapSegments(channel, size.toLong() * Int.SIZE_BYTES)

    operator fun get(index: Int): Int {
        val byteOffset = index.toLong() * Int.SIZE_BYTES
        return segments[(byteOffset ushr SEGMENT_SHIFT).toInt()]
            .getInt((byteOffset and SEGMENT_MASK).toInt())
    }

    override fun close() = channel.close()
}

private fun mapSegments(channel: FileChannel, byteLength: Long): Array<MappedByteBuffer> {
    if (byteLength == 0L) return emptyArray()
    val segmentCount = ((byteLength - 1) ushr SEGMENT_SHIFT).toInt() + 1
    return Array(segmentCount) { segmentIndex ->
        val start = segmentIndex.toLong() shl SEGMENT_SHIFT
        channel.map(
            FileChannel.MapMode.READ_ONLY,
            start,
            minOf(SEGMENT_SIZE, byteLength - start),
        )
    }
}

internal class PrimitiveIndexWriter(path: Path) : AutoCloseable {
    private val channel = FileChannel.open(
        path,
        StandardOpenOption.CREATE_NEW,
        StandardOpenOption.WRITE,
    )
    private val buffer = ByteBuffer.allocate(1 shl 20)
    private var elementCount = 0

    fun writeLong(value: Long) {
        ensureCapacity(Long.SIZE_BYTES)
        buffer.putLong(value)
        elementCount++
    }

    fun writeInt(value: Int) {
        ensureCapacity(Int.SIZE_BYTES)
        buffer.putInt(value)
        elementCount++
    }

    fun close(expectedElementCount: Int, bytesPerElement: Int) {
        check(elementCount == expectedElementCount) {
            "Index contains $elementCount elements, expected $expectedElementCount"
        }
        flushBuffer()
        channel.truncate(expectedElementCount.toLong() * bytesPerElement)
        channel.force(true)
        channel.close()
    }

    override fun close() {
        if (channel.isOpen) channel.close()
    }

    private fun ensureCapacity(byteCount: Int) {
        if (buffer.remaining() < byteCount) flushBuffer()
    }

    private fun flushBuffer() {
        buffer.flip()
        while (buffer.hasRemaining()) channel.write(buffer)
        buffer.clear()
    }
}
