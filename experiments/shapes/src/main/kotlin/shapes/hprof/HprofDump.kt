package shapes.hprof

import it.unimi.dsi.fastutil.ints.IntArrayList
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap
import it.unimi.dsi.fastutil.longs.LongArrayList
import shapes.Log
import shapes.phase
import java.nio.file.Path

/**
 * The indexed dump: everything the file says about identity, nothing about
 * references between objects yet. Classes with their layouts and retained
 * targets, dense object ids as address ranks, GC roots and thread locals, the
 * heap segments grouped into parallel work chunks, and the open file. Built
 * from the file alone; no extraction option influences it.
 *
 * Encoded targets: a value below [classTargetBase] is a dense object id, a
 * value at or above it is `classTargetBase + store class index`.
 */
class HprofDump internal constructor(
    val file: HprofFile,
    val classes: HprofClassStore,
    val chunks: List<HeapChunk>,
    val counts: HeapCounts,
    /** Address-to-dense-id table; released by [close] once references are extracted. */
    val objects: ObjectAddressTable,
    /** GC-root targets, duplicates included, dangling addresses dropped. */
    val roots: IntArray,
    /** Per store class: targets retained by its statics, constants and loader. */
    val classStatics: Array<IntArray>,
    /** Stack locals and blocked monitors per thread object address. */
    internal val threadLocalsByAddress: Long2ObjectOpenHashMap<LongArrayList>,
) : AutoCloseable {
    val objectCount: Int
        get() = objects.objectCount

    val classTargetBase: Int
        get() = objectCount

    override fun close() {
        objects.close()
        file.close()
    }
}

/** Stage 1 of extraction: open and index a dump. */
fun indexHprof(path: Path, log: Log): HprofDump = log.phase("index") { HprofIndexer(log)(path) }

internal class HprofIndexer(private val log: Log) {
    operator fun invoke(path: Path): HprofDump {
        val file = HprofFile.open(path)
        try {
            return index(file)
        } catch (failure: Throwable) {
            file.close()
            throw failure
        }
    }

    private fun index(file: HprofFile): HprofDump {
        val directory = log.phase(
            "directory",
            { "${it.stringCount()} strings, ${it.heapSegments.size} heap segments" },
        ) { HprofDirectory.scan(file) }
        val heap = log.phase(
            "heap index",
            { "${it.counts.objects} objects, ${it.classStore.size} classes, ${it.chunks.size} chunks" },
        ) { HprofHeapIndex.build(file, directory) }
        val objects = log.phase("address table", { "${it.objectCount} objects" }) {
            ObjectAddressTable.build(heap.addressBuffers)
        }
        heap.addressArena.close()
        require(objects.objectCount.toLong() == heap.counts.objects) {
            "address table ${objects.objectCount} != object count ${heap.counts.objects}"
        }

        val classTargetBase = objects.objectCount
        return HprofDump(
            file = file,
            classes = heap.classStore,
            chunks = heap.chunks,
            counts = heap.counts,
            objects = objects,
            roots = resolveRoots(heap, objects, classTargetBase),
            classStatics = resolveClassStatics(heap.classStore, objects, classTargetBase),
            threadLocalsByAddress = heap.threadLocalsByAddress,
        )
    }

    private fun resolveRoots(
        heap: HprofHeapIndex,
        objects: ObjectAddressTable,
        classTargetBase: Int,
    ): IntArray {
        val resolved = IntArrayList(heap.rootAddresses.size)
        var dangling = 0
        for (address in heap.rootAddresses) {
            if (address == 0L) continue
            val dense = objects.denseIdOf(address)
            if (dense >= 0) {
                resolved.add(dense)
                continue
            }
            val classIndex = heap.classStore.indexOf(address)
            if (classIndex >= 0) resolved.add(classTargetBase + classIndex) else dangling++
        }
        if (dangling > 0) log("roots: $dangling dangling root addresses ignored")
        return resolved.toIntArray()
    }

    private fun resolveClassStatics(
        classStore: HprofClassStore,
        objects: ObjectAddressTable,
        classTargetBase: Int,
    ): Array<IntArray> = Array(classStore.size) { classIndex ->
        val retained = classStore[classIndex].retainedAddresses
        if (retained.isEmpty()) return@Array EMPTY_INTS
        val resolved = IntArrayList(retained.size)
        for (address in retained) {
            val dense = objects.denseIdOf(address)
            if (dense >= 0) {
                resolved.add(dense)
            } else {
                val targetClass = classStore.indexOf(address)
                if (targetClass >= 0) resolved.add(classTargetBase + targetClass)
            }
        }
        resolved.toIntArray()
    }

    private companion object {
        val EMPTY_INTS = IntArray(0)
    }
}
