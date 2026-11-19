package shapes.hprof

import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout
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
    val data: MemorySegment,
    val identifierSize: Int,
    /** Offset of the first top-level record. */
    val firstRecordOffset: Long,
) : AutoCloseable {
    val size: Long
        get() = data.byteSize()

    fun u1(offset: Long): Int = data.get(ValueLayout.JAVA_BYTE, offset).toInt() and 0xFF

    fun u2(offset: Long): Int = data.get(U2, offset).toInt() and 0xFFFF

    fun u4(offset: Long): Long = data.get(U4, offset).toLong() and 0xFFFFFFFFL

    fun u8(offset: Long): Long = data.get(U8, offset)

    fun id(offset: Long): Long =
        if (identifierSize == 8) u8(offset) else u4(offset)

    fun utf8(offset: Long, length: Int): String {
        val bytes = ByteArray(length)
        MemorySegment.copy(data, offset, MemorySegment.ofArray(bytes), 0, length.toLong())
        return String(bytes, Charsets.UTF_8)
    }

    override fun close() = arena.close()

    companion object {
        private val U2 = ValueLayout.JAVA_SHORT_UNALIGNED.withOrder(ByteOrder.BIG_ENDIAN)
        private val U4 = ValueLayout.JAVA_INT_UNALIGNED.withOrder(ByteOrder.BIG_ENDIAN)
        private val U8 = ValueLayout.JAVA_LONG_UNALIGNED.withOrder(ByteOrder.BIG_ENDIAN)

        private const val HEADER_PREFIX = "JAVA PROFILE "

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
                return HprofFile(arena, segment, identifierSize, firstRecord)
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

    fun byteSize(type: Int, identifierSize: Int): Int = when (type) {
        OBJECT -> identifierSize
        BOOLEAN, BYTE -> 1
        CHAR, SHORT -> 2
        FLOAT, INT -> 4
        DOUBLE, LONG -> 8
        else -> throw IllegalArgumentException("unknown HPROF basic type $type")
    }
}
