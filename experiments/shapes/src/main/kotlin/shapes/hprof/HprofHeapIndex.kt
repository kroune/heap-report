package shapes.hprof

import it.unimi.dsi.fastutil.ints.Int2LongOpenHashMap
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap
import it.unimi.dsi.fastutil.longs.LongArrayList
import java.lang.foreign.Arena
import java.util.concurrent.Callable
import java.util.concurrent.Executors

/**
 * A sub-record-aligned work unit for parallel walks: one or more contiguous
 * ranges (HPROF segments are ~1 MiB each, so chunks span many of them).
 */
class HeapChunk(val rangeStarts: LongArray, val rangeEnds: LongArray) {
    inline fun forEachRange(action: (start: Long, end: Long) -> Unit) {
        for (rangeIndex in rangeStarts.indices) {
            action(rangeStarts[rangeIndex], rangeEnds[rangeIndex])
        }
    }
}

class HeapCounts {
    var instances = 0L
    var objectArrays = 0L
    var primitiveArrays = 0L
    var classDumps = 0L

    val objects: Long
        get() = instances + objectArrays + primitiveArrays

    fun add(other: HeapCounts) {
        instances += other.instances
        objectArrays += other.objectArrays
        primitiveArrays += other.primitiveArrays
        classDumps += other.classDumps
    }
}

/**
 * First full walk of the heap-dump segments, parallel per segment: parses all
 * CLASS_DUMP records, counts objects and records chunk checkpoints for the
 * later balanced parallel walks.
 */
class HprofHeapIndex private constructor(
    val classStore: HprofClassStore,
    val chunks: List<HeapChunk>,
    val counts: HeapCounts,
    /** GC-root object addresses, duplicates included. */
    val rootAddresses: LongArray,
    /**
     * Stack locals and blocked monitors per thread object address. MAT models
     * them as outbound references of the thread, and shapes follow suit.
     */
    val threadLocalsByAddress: Long2ObjectOpenHashMap<LongArrayList>,
    /** Per-chunk object addresses (instances + arrays, class objects excluded). */
    val addressBuffers: List<AddressBuffer>,
    /** Owns [addressBuffers]; close after the address table is built. */
    val addressArena: Arena,
) {
    companion object {
        private const val CHUNK_TARGET_BYTES = 64L shl 20

        /** Groups consecutive ~1 MiB dump segments into balanced work units. */
        fun groupIntoChunks(segments: List<HeapSegment>): List<HeapChunk> {
            val chunks = ArrayList<HeapChunk>()
            val starts = LongArrayList()
            val ends = LongArrayList()
            var pendingBytes = 0L
            for (segment in segments) {
                starts.add(segment.start)
                ends.add(segment.end)
                pendingBytes += segment.length
                if (pendingBytes >= CHUNK_TARGET_BYTES) {
                    chunks.add(HeapChunk(starts.toLongArray(), ends.toLongArray()))
                    starts.clear()
                    ends.clear()
                    pendingBytes = 0
                }
            }
            if (starts.isNotEmpty()) chunks.add(HeapChunk(starts.toLongArray(), ends.toLongArray()))
            return chunks
        }

        fun build(file: HprofFile, directory: HprofDirectory): HprofHeapIndex {
            class ChunkResult(
                val classes: ArrayList<HprofClass>,
                val classAddresses: LongArrayList,
                val counts: HeapCounts,
                val rootAddresses: LongArrayList,
                /** Interleaved (threadSerial, threadAddress) pairs. */
                val threadObjects: LongArrayList,
                /** Interleaved (threadSerial, localAddress) pairs. */
                val threadLocals: LongArrayList,
                var maxAddress: Long = 0,
            )

            val chunks = groupIntoChunks(directory.heapSegments)
            val addressArena = Arena.ofShared()
            val addressBuffers = chunks.map { AddressBuffer(addressArena) }
            val results = mapChunksParallel(chunks) { chunkIndex, chunk ->
                val classes = ArrayList<HprofClass>()
                val classAddresses = LongArrayList()
                val counts = HeapCounts()
                val rootAddresses = LongArrayList()
                val threadObjects = LongArrayList()
                val threadLocals = LongArrayList()
                val addresses = addressBuffers[chunkIndex]
                var maxAddress = 0L
                chunk.forEachRange { start, end ->
                    var offset = start
                    while (offset < end) {
                        val tag = file.u1(offset)
                        offset++
                        when (tag) {
                            HprofSubTag.CLASS_DUMP -> {
                                counts.classDumps++
                                offset = parseClassDump(
                                    file,
                                    directory,
                                    offset,
                                    classes,
                                    classAddresses,
                                )
                            }
                            HprofSubTag.INSTANCE_DUMP,
                            HprofSubTag.OBJECT_ARRAY_DUMP,
                            HprofSubTag.PRIMITIVE_ARRAY_DUMP -> {
                                when (tag) {
                                    HprofSubTag.INSTANCE_DUMP -> counts.instances++
                                    HprofSubTag.OBJECT_ARRAY_DUMP -> counts.objectArrays++
                                    else -> counts.primitiveArrays++
                                }
                                val address = file.id(offset)
                                if (address > maxAddress) maxAddress = address
                                addresses.add(address)
                                offset = skipSubRecord(file, tag, offset)
                            }
                            HprofSubTag.ROOT_THREAD_OBJECT -> {
                                val threadAddress = file.id(offset)
                                rootAddresses.add(threadAddress)
                                threadObjects.add(file.u4(offset + file.identifierSize))
                                threadObjects.add(threadAddress)
                                offset = skipSubRecord(file, tag, offset)
                            }
                            HprofSubTag.ROOT_JNI_LOCAL,
                            HprofSubTag.ROOT_JAVA_FRAME,
                            HprofSubTag.ROOT_NATIVE_STACK,
                            HprofSubTag.ROOT_THREAD_BLOCK -> {
                                val localAddress = file.id(offset)
                                rootAddresses.add(localAddress)
                                threadLocals.add(file.u4(offset + file.identifierSize))
                                threadLocals.add(localAddress)
                                offset = skipSubRecord(file, tag, offset)
                            }
                            HprofSubTag.ROOT_UNKNOWN,
                            HprofSubTag.ROOT_JNI_GLOBAL,
                            HprofSubTag.ROOT_STICKY_CLASS,
                            HprofSubTag.ROOT_MONITOR_USED -> {
                                rootAddresses.add(file.id(offset))
                                offset = skipSubRecord(file, tag, offset)
                            }
                            else -> offset = skipSubRecord(file, tag, offset)
                        }
                    }
                    check(offset == end) { "sub-record walk overran range $start..$end" }
                }
                ChunkResult(
                    classes,
                    classAddresses,
                    counts,
                    rootAddresses,
                    threadObjects,
                    threadLocals,
                    maxAddress,
                )
            }

            val allClasses = ArrayList<HprofClass>(1 shl 17)
            val indexByAddress = Long2IntOpenHashMap(1 shl 18)
            indexByAddress.defaultReturnValue(-1)
            val counts = HeapCounts()
            val rootAddresses = LongArrayList()
            val threadBySerial = Int2LongOpenHashMap()
            val allThreadLocals = LongArrayList()
            var maxAddress = 0L
            for (result in results) {
                if (result.maxAddress > maxAddress) maxAddress = result.maxAddress
                var pairIndex = 0
                while (pairIndex < result.threadObjects.size) {
                    threadBySerial.put(
                        result.threadObjects.getLong(pairIndex).toInt(),
                        result.threadObjects.getLong(pairIndex + 1),
                    )
                    pairIndex += 2
                }
                allThreadLocals.addAll(result.threadLocals)
                for (classIndex in result.classes.indices) {
                    val address = result.classAddresses.getLong(classIndex)
                    if (indexByAddress.putIfAbsent(address, allClasses.size) == -1) {
                        allClasses.add(result.classes[classIndex])
                    }
                }
                counts.add(result.counts)
                rootAddresses.addAll(result.rootAddresses)
            }

            val threadLocalsByAddress = Long2ObjectOpenHashMap<LongArrayList>()
            var localIndex = 0
            while (localIndex < allThreadLocals.size) {
                val serial = allThreadLocals.getLong(localIndex).toInt()
                val localAddress = allThreadLocals.getLong(localIndex + 1)
                val threadAddress = threadBySerial.getOrDefault(serial, 0L)
                if (threadAddress != 0L) {
                    threadLocalsByAddress
                        .computeIfAbsent(threadAddress) { LongArrayList() }
                        .add(localAddress)
                }
                localIndex += 2
            }

            // Same heuristic as MAT: heaps addressed under 32 GiB use compressed oops.
            val compressedOops = maxAddress < (32L shl 30)
            val classStore = HprofClassStore.create(file, allClasses, indexByAddress, compressedOops)
            return HprofHeapIndex(
                classStore,
                chunks,
                counts,
                rootAddresses.toLongArray(),
                threadLocalsByAddress,
                addressBuffers,
                addressArena,
            )
        }

        private fun parseClassDump(
            file: HprofFile,
            directory: HprofDirectory,
            recordStart: Long,
            classes: ArrayList<HprofClass>,
            classAddresses: LongArrayList,
        ): Long {
            val identifierSize = file.identifierSize
            val classAddress = file.id(recordStart)
            val superAddress = file.id(recordStart + identifierSize + 4)
            val retained = LongArrayList()
            // Class loader only: MAT's class outbound has neither signers nor
            // protection domain, and liveness must match its marking.
            val loaderAddress = file.id(recordStart + identifierSize + 4 + identifierSize.toLong())
            if (loaderAddress != 0L) retained.add(loaderAddress)

            var offset = recordStart + identifierSize + 4 + identifierSize.toLong() * 6 + 4
            val constantCount = file.u2(offset)
            offset += 2
            repeat(constantCount) {
                val type = file.u1(offset + 2)
                if (type == HprofType.OBJECT) {
                    val value = file.id(offset + 3)
                    if (value != 0L) retained.add(value)
                }
                offset += 2 + 1 + HprofType.byteSize(type, identifierSize)
            }

            val staticCount = file.u2(offset)
            offset += 2
            repeat(staticCount) {
                val type = file.u1(offset + identifierSize)
                if (type == HprofType.OBJECT) {
                    val value = file.id(offset + identifierSize + 1)
                    if (value != 0L) retained.add(value)
                }
                offset += identifierSize + 1 + HprofType.byteSize(type, identifierSize)
            }

            val fieldCount = file.u2(offset)
            offset += 2
            val fieldNames = Array(fieldCount) { "" }
            val fieldTypes = ByteArray(fieldCount)
            for (fieldIndex in 0 until fieldCount) {
                fieldNames[fieldIndex] = directory.string(file.id(offset))
                fieldTypes[fieldIndex] = file.u1(offset + identifierSize).toByte()
                offset += identifierSize + 1
            }

            val nameStringId = directory.classNameStringIds.get(classAddress)
            val jvmName = if (nameStringId != -1L) directory.string(nameStringId) else "unknown-class"
            classes.add(
                HprofClass(
                    HprofClassStore.convertJvmClassName(jvmName),
                    superAddress,
                    fieldNames,
                    fieldTypes,
                    retained.toLongArray(),
                )
            )
            classAddresses.add(classAddress)
            return offset
        }
    }
}

/** Runs [action] for every chunk on all cores; actions must be independent. */
inline fun <reified T> mapChunksParallel(
    chunks: List<HeapChunk>,
    crossinline action: (chunkIndex: Int, chunk: HeapChunk) -> T,
): Array<T> {
    val pool = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors())
    try {
        val futures = chunks.mapIndexed { chunkIndex, chunk ->
            pool.submit(Callable { action(chunkIndex, chunk) })
        }
        return Array(futures.size) { futures[it].get() }
    } finally {
        pool.shutdown()
    }
}
