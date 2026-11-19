package shapes.hprof

import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap

/** One `HEAP_DUMP` / `HEAP_DUMP_SEGMENT` sub-record region. */
class HeapSegment(val start: Long, val end: Long) {
    val length: Long
        get() = end - start
}

/**
 * Index of top-level records: UTF-8 strings, loaded classes and heap-dump
 * segment extents. Built in one cheap pass that skips record bodies.
 */
class HprofDirectory private constructor(
    private val file: HprofFile,
    /** String id to packed (offset << 24 | length). */
    private val stringLocations: Long2LongOpenHashMap,
    /** Class object address to name-string id. */
    val classNameStringIds: Long2LongOpenHashMap,
    val heapSegments: List<HeapSegment>,
) {
    fun string(stringId: Long): String {
        val packed = stringLocations.getOrDefault(stringId, -1L)
        require(packed != -1L) { "unknown HPROF string id $stringId" }
        return file.utf8(packed ushr LENGTH_BITS, (packed and LENGTH_MASK).toInt())
    }

    fun stringCount(): Int = stringLocations.size

    companion object {
        private const val LENGTH_BITS = 24
        private const val LENGTH_MASK = (1L shl LENGTH_BITS) - 1

        fun scan(file: HprofFile): HprofDirectory {
            val strings = Long2LongOpenHashMap(1 shl 20)
            strings.defaultReturnValue(-1L)
            val classNames = Long2LongOpenHashMap(1 shl 16)
            classNames.defaultReturnValue(-1L)
            val segments = ArrayList<HeapSegment>()
            val identifierSize = file.identifierSize

            var offset = file.firstRecordOffset
            while (offset < file.size) {
                val tag = file.u1(offset)
                // u1 tag + u4 time + u4 length
                val bodyStart = offset + 9
                var bodyLength = file.u4(offset + 5)

                when (tag) {
                    HprofTag.UTF8 -> {
                        val stringId = file.id(bodyStart)
                        val textLength = bodyLength - identifierSize
                        require(textLength < (1L shl LENGTH_BITS)) {
                            "unsupported UTF8 record of $textLength bytes"
                        }
                        strings.put(
                            stringId,
                            ((bodyStart + identifierSize) shl LENGTH_BITS) or textLength,
                        )
                    }
                    HprofTag.LOAD_CLASS -> {
                        val classObjectId = file.id(bodyStart + 4)
                        val nameStringId = file.id(bodyStart + 4 + identifierSize + 4)
                        classNames.put(classObjectId, nameStringId)
                    }
                    HprofTag.HEAP_DUMP, HprofTag.HEAP_DUMP_SEGMENT -> {
                        // The u4 length wraps for segments over 4 GiB (JDK-8144732);
                        // recover by walking the sub-records to the true end.
                        if (bodyStart + bodyLength > file.size) {
                            bodyLength = file.size - bodyStart
                        }
                        segments.add(HeapSegment(bodyStart, bodyStart + bodyLength))
                    }
                    else -> {} // skip: stack traces, frames, control settings, ...
                }
                offset = bodyStart + bodyLength
            }
            require(segments.isNotEmpty()) { "no heap dump segments found" }
            return HprofDirectory(file, strings, classNames, segments)
        }
    }
}
