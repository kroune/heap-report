package shapes.hprof

import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/**
 * Memory-mapped HPROF binary. All multi-byte values are big-endian; reads are
 * positional and thread-safe, so segment walks can run concurrently.
 */
class HprofFile private constructor(
    private val arena: Arena,
    private val data: Array<ByteBuffer>,
    val size: Long,
    val identifierSize: Int,
    /** Offset of the first top-level record. */
    val firstRecordOffset: Long,
) : AutoCloseable {
    fun u1(offset: Long): Int =
        data[(offset ushr REGION_SHIFT).toInt()].get((offset and REGION_MASK).toInt()).toInt() and 0xFF

    fun u2(offset: Long): Int =
        data[(offset ushr REGION_SHIFT).toInt()].getShort((offset and REGION_MASK).toInt()).toInt() and 0xFFFF

    fun u4(offset: Long): Long =
        data[(offset ushr REGION_SHIFT).toInt()].getInt((offset and REGION_MASK).toInt()).toLong() and 0xFFFFFFFFL

    fun u8(offset: Long): Long =
        data[(offset ushr REGION_SHIFT).toInt()].getLong((offset and REGION_MASK).toInt())

    fun id(offset: Long): Long =
        if (identifierSize == 8) u8(offset) else u4(offset)

    fun utf8(offset: Long, length: Int): String {
        val bytes = ByteArray(length)
        var read = 0
        while (read < length) {
            val sourceOffset = offset + read
            val region = data[(sourceOffset ushr REGION_SHIFT).toInt()].duplicate()
            val position = (sourceOffset and REGION_MASK).toInt()
            val count = minOf(length - read, region.capacity() - position)
            region.position(position)
            region.get(bytes, read, count)
            read += count
        }
        return String(bytes, Charsets.UTF_8)
    }

    override fun close() = arena.close()

    companion object {
        private val U4 = ValueLayout.JAVA_INT_UNALIGNED.withOrder(ByteOrder.BIG_ENDIAN)

        private const val HEADER_PREFIX = "JAVA PROFILE "
        private const val REGION_SHIFT = 29
        private const val REGION_CAPACITY = 1L shl REGION_SHIFT
        private const val REGION_MASK = REGION_CAPACITY - 1

        fun open(path: Path): HprofFile {
            val arena = Arena.ofShared()
            try {
                val segment = FileChannel.open(path, StandardOpenOption.READ).use { channel ->
                    channel.map(FileChannel.MapMode.READ_ONLY, 0, channel.size(), arena)
                }

                var versionEnd = 0L
                while (segment.get(ValueLayout.JAVA_BYTE, versionEnd) != 0.toByte()) {
                    versionEnd++
                    require(versionEnd < 64) { "unterminated HPROF version string" }
                }
                val version = String(
                    ByteArray(versionEnd.toInt()).also {
                        MemorySegment.copy(segment, 0, MemorySegment.ofArray(it), 0, versionEnd)
                    },
                    Charsets.US_ASCII,
                )
                require(version.startsWith(HEADER_PREFIX)) { "not an HPROF file: $version" }

                val identifierSize = segment.get(U4, versionEnd + 1).toLong().toInt()
                require(identifierSize == 4 || identifierSize == 8) {
                    "unsupported identifier size $identifierSize"
                }
                // version + NUL + u4 identifier size + u8 timestamp
                val firstRecord = versionEnd + 1 + 4 + 8
                val regions = Array(((segment.byteSize() + REGION_MASK) ushr REGION_SHIFT).toInt()) { index ->
                    val start = index.toLong() shl REGION_SHIFT
                    segment.asSlice(
                        start,
                        minOf(REGION_CAPACITY + Long.SIZE_BYTES, segment.byteSize() - start),
                    )
                        .asByteBuffer()
                        .order(ByteOrder.BIG_ENDIAN)
                }
                return HprofFile(arena, regions, segment.byteSize(), identifierSize, firstRecord)
            } catch (failure: Throwable) {
                arena.close()
                throw failure
            }
        }
    }
}

/** Top-level HPROF record tags used by extraction. */
object HprofTag {
    const val UTF8 = 0x01
    const val LOAD_CLASS = 0x02
    const val HEAP_DUMP = 0x0C
    const val HEAP_DUMP_SEGMENT = 0x1C
    const val HEAP_DUMP_END = 0x2C
}

/** Heap-dump sub-record tags. */
object HprofSubTag {
    const val ROOT_UNKNOWN = 0xFF
    const val ROOT_JNI_GLOBAL = 0x01
    const val ROOT_JNI_LOCAL = 0x02
    const val ROOT_JAVA_FRAME = 0x03
    const val ROOT_NATIVE_STACK = 0x04
    const val ROOT_STICKY_CLASS = 0x05
    const val ROOT_THREAD_BLOCK = 0x06
    const val ROOT_MONITOR_USED = 0x07
    const val ROOT_THREAD_OBJECT = 0x08
    const val CLASS_DUMP = 0x20
    const val INSTANCE_DUMP = 0x21
    const val OBJECT_ARRAY_DUMP = 0x22
    const val PRIMITIVE_ARRAY_DUMP = 0x23
}

/** HPROF basic type codes with their serialized sizes. */
object HprofType {
    const val OBJECT = 2
    const val BOOLEAN = 4
    const val CHAR = 5
    const val FLOAT = 6
    const val DOUBLE = 7
    const val BYTE = 8
    const val SHORT = 9
    const val INT = 10
    const val LONG = 11

    @Suppress("NOTHING_TO_INLINE")
    inline fun byteSize(type: Int, identifierSize: Int): Int = when (type) {
        OBJECT -> identifierSize
        BOOLEAN, BYTE -> 1
        CHAR, SHORT -> 2
        FLOAT, INT -> 4
        DOUBLE, LONG -> 8
        else -> throw IllegalArgumentException("unknown HPROF basic type $type")
    }
}
