package shapes.input

import java.io.EOFException
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Path
import java.nio.file.StandardOpenOption

class FastReader(path: Path) : AutoCloseable {
    private val channel = FileChannel.open(path, StandardOpenOption.READ)
    private val size = channel.size()
    private var base = 0L
    private var buf = map(0)

    private fun map(position: Long): MappedByteBuffer =
        channel.map(FileChannel.MapMode.READ_ONLY, position, minOf(size - position, Int.MAX_VALUE.toLong()))

    private fun position() = base + buf.position()

    private fun need(n: Int) {
        val position = position()
        if (n > size - position) throw EOFException()
        if (n > buf.remaining()) {
            base = position
            buf = map(position)
        }
    }

    fun int(): Int {
        need(4)
        return buf.int
    }

    fun long(): Long {
        need(8)
        return buf.long
    }

    fun bytes(n: Int): ByteArray {
        need(n)
        return ByteArray(n).also(buf::get)
    }

    fun skip(n: Long) {
        val position = position()
        if (n > size - position) throw EOFException()
        if (n <= buf.remaining()) {
            buf.position(buf.position() + n.toInt())
        } else {
            base = position + n
            buf = map(base)
        }
    }

    override fun close() = channel.close()
}
