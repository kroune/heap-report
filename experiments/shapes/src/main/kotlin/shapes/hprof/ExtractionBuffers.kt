package shapes.hprof

import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/**
 * Growable off-heap append-only int buffer. Blocks double from 16 KiB up to
 * 16 MiB, so hundreds of mostly-empty per-chunk buffers stay cheap: arena
 * allocation zeroes pages, which makes over-sized fixed blocks real memory.
 */
class IntBlockBuffer(private val arena: Arena) {
    private val blocks = ArrayList<MemorySegment>()
    private var blockCapacity = 0L
    private var blockFill = 0L
    var size = 0L
        private set

    fun add(value: Int) {
        if (blockFill == blockCapacity) {
            blockCapacity = nextBlockCapacity(blockCapacity)
            blocks.add(arena.allocate(blockCapacity shl 2))
            blockFill = 0
        }
        blocks.last().set(ValueLayout.JAVA_INT_UNALIGNED, blockFill shl 2, value)
        blockFill++
        size++
    }

    operator fun get(index: Long): Int {
        var remaining = index
        for (block in blocks) {
            val capacity = block.byteSize() ushr 2
            if (remaining < capacity) {
                return block.get(ValueLayout.JAVA_INT_UNALIGNED, remaining shl 2)
            }
            remaining -= capacity
        }
        throw IndexOutOfBoundsException("$index of $size")
    }

    /** Sequential access across blocks without per-element block search. */
    inline fun forEach(crossinline action: (Int) -> Unit) {
        forEachBlock { block, count ->
            for (index in 0 until count) {
                action(block.get(ValueLayout.JAVA_INT_UNALIGNED, index shl 2))
            }
        }
    }

    fun forEachBlock(action: (block: MemorySegment, count: Long) -> Unit) {
        for (blockIndex in blocks.indices) {
            val block = blocks[blockIndex]
            val count = if (blockIndex == blocks.size - 1) blockFill else block.byteSize() ushr 2
            action(block, count)
        }
    }

    companion object {
        fun nextBlockCapacity(current: Long): Long =
            if (current == 0L) 4L shl 10 else minOf(current * 2, 4L shl 20)
    }
}

/**
 * Sequential int spill written to a temp file through a buffered channel;
 * read back memory-mapped. Values are little-endian.
 */
class IntSpillWriter(val path: Path) : AutoCloseable {
    private val channel = FileChannel.open(
        path,
        StandardOpenOption.CREATE,
        StandardOpenOption.TRUNCATE_EXISTING,
        StandardOpenOption.WRITE,
    )
    private val buffer = ByteBuffer.allocateDirect(256 shl 10).order(ByteOrder.LITTLE_ENDIAN)
    var intCount = 0L
        private set

    fun write(value: Int) {
        if (buffer.remaining() < 4) flush()
        buffer.putInt(value)
        intCount++
    }

    private fun flush() {
        buffer.flip()
        while (buffer.hasRemaining()) channel.write(buffer)
        buffer.clear()
    }

    override fun close() {
        flush()
        channel.close()
    }
}

/** Memory-mapped reader for one spill file. */
class IntSpillReader(path: Path, arena: Arena) {
    private val segment: MemorySegment
    val intCount: Long

    init {
        FileChannel.open(path, StandardOpenOption.READ).use { channel ->
            segment = channel.map(FileChannel.MapMode.READ_ONLY, 0, channel.size(), arena)
            intCount = channel.size() / 4
        }
    }

    operator fun get(index: Long): Int =
        segment.get(ValueLayout.JAVA_INT_UNALIGNED, index shl 2)
}

/**
 * Off-heap scratch backed by an unlinked temp file: pages are reclaimable
 * under memory pressure (written back and evicted) instead of competing as
 * anonymous memory, so large graphs degrade gracefully instead of OOMing.
 */
fun mapScratchSegment(arena: Arena, bytes: Long): MemorySegment {
    val path = Files.createTempFile("shapes-graph", ".bin")
    try {
        FileChannel.open(path, StandardOpenOption.READ, StandardOpenOption.WRITE).use { channel ->
            channel.truncate(0)
            channel.position(maxOf(bytes - 1, 0))
            if (bytes > 0) channel.write(ByteBuffer.wrap(byteArrayOf(0)))
            return channel.map(FileChannel.MapMode.READ_WRITE, 0, bytes, arena)
        }
    } finally {
        Files.deleteIfExists(path) // unlink; mapping keeps the backing alive
    }
}

/** Temp directory holding all spill files of one extraction. */
class SpillDirectory : AutoCloseable {
    val root: Path = Files.createTempDirectory("shapes-spill")

    fun file(name: String): Path = root.resolve(name)

    override fun close() {
        Files.newDirectoryStream(root).use { entries ->
            entries.forEach { Files.deleteIfExists(it) }
        }
        Files.deleteIfExists(root)
    }
}
