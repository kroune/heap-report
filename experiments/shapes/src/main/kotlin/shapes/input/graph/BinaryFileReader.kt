package shapes.input.graph

import java.io.EOFException
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/** Sequential big-endian primitive reader over a binary file. */
class BinaryFileReader(path: Path) : AutoCloseable {
    private val channel = FileChannel.open(path, StandardOpenOption.READ)
    private val fileSize = channel.size()
    private var mappedRegionStart = 0L
    private var buffer = mapRegion(0)

    private fun mapRegion(position: Long): MappedByteBuffer = channel.map(
        FileChannel.MapMode.READ_ONLY,
        position,
        minOf(fileSize - position, Int.MAX_VALUE.toLong()),
    )

    private fun position(): Long = mappedRegionStart + buffer.position()

    private fun ensureAvailable(byteCount: Int) {
        val currentPosition = position()
        if (byteCount > fileSize - currentPosition) throw EOFException()
        if (byteCount > buffer.remaining()) {
            mappedRegionStart = currentPosition
            buffer = mapRegion(currentPosition)
        }
    }

    fun readInt(): Int {
        ensureAvailable(Int.SIZE_BYTES)
        return buffer.int
    }

    fun readLong(): Long {
        ensureAvailable(Long.SIZE_BYTES)
        return buffer.long
    }

    fun readBytes(byteCount: Int): ByteArray {
        ensureAvailable(byteCount)
        return ByteArray(byteCount).also(buffer::get)
    }

    fun skip(byteCount: Long) {
        val currentPosition = position()
        if (byteCount > fileSize - currentPosition) throw EOFException()
        if (byteCount <= buffer.remaining()) {
            buffer.position(buffer.position() + byteCount.toInt())
        } else {
            mappedRegionStart = currentPosition + byteCount
            buffer = mapRegion(mappedRegionStart)
        }
    }

    override fun close() = channel.close()
}
