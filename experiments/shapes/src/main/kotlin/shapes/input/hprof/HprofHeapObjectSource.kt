package shapes.input.hprof

import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap
import it.unimi.dsi.fastutil.ints.IntArrayList
import org.eclipse.mat.SnapshotException
import org.eclipse.mat.util.VoidProgressListener
import shapes.graph.ClassName
import shapes.input.heap.HeapClassDescription
import shapes.input.heap.HeapObjectSource
import shapes.input.heap.NamedReference
import shapes.mat.MatSnapshotLoader
import java.io.EOFException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.BitSet
import java.util.UUID

/**
 * HPROF-backed heap source. MAT is not opened unless retained-set selection is requested.
 * The persistent index contains no object payload or reference lists.
 */
class HprofHeapObjectSource private constructor(
    private val hprof: Path,
    private val index: HprofIndex,
    private val progress: (String) -> Unit,
) : HeapObjectSource {
    private val reader = HprofRandomReader(hprof, index.identifierSize)
    private val allFields = arrayOfNulls<List<HprofFieldDefinition>>(index.classes.size)
    private var liveObjects: BitSet? = null

    override val objectCount: Int
        get() = index.objectCount

    override fun classId(objectId: Int): Int = classAt(objectId).sourceObjectId

    override fun className(objectId: Int): ClassName = ClassName(classAt(objectId).name)

    override fun shallowSize(objectId: Int): ULong {
        reader.seek(index.recordOffsets[objectId])
        return when (val tag = reader.readUnsignedByte()) {
            INSTANCE_DUMP -> classAt(objectId).instanceSize.toULong()
            CLASS_DUMP -> {
                val javaLangClass = index.classes.getOrNull(index.javaLangClassIndex)
                (javaLangClass?.instanceSize ?: 0).toULong()
            }
            OBJECT_ARRAY_DUMP -> {
                reader.skipIdentifiers(1)
                reader.skip(4)
                val length = reader.readLength()
                alignArraySize(length.toLong() * index.referenceSize, primitive = false)
            }
            PRIMITIVE_ARRAY_DUMP -> {
                reader.skipIdentifiers(1)
                reader.skip(4)
                val length = reader.readLength()
                val elementType = reader.readUnsignedByte()
                alignArraySize(
                    length.toLong() * hprofTypeSize(elementType, index.identifierSize),
                    primitive = true,
                )
            }
            else -> throw IllegalArgumentException("Object $objectId has unexpected HPROF tag $tag")
        }
    }

    override fun indexedReferences(objectId: Int): IntArray = readReferences(objectId, false).ids

    override fun objectAddress(objectId: Int): Long {
        if (index.addressObjectIds == null) return index.addresses[objectId]
        reader.seek(index.recordOffsets[objectId])
        reader.readUnsignedByte()
        return reader.readIdentifier()
    }

    override fun resolveObjectAddress(objectAddress: Long): Int {
        if (objectAddress == 0L || objectCount == 0) return -1
        var low = 0
        var high = objectCount - 1

        // Heap object addresses are usually dense enough for interpolation to need only a few
        // probes. The binary-search fallback keeps worst-case behavior bounded for every dump.
        repeat(8) {
            if (low > high) return -1
            val lowAddress = index.addresses[low]
            val highAddress = index.addresses[high]
            if (java.lang.Long.compareUnsigned(objectAddress, lowAddress) < 0 ||
                java.lang.Long.compareUnsigned(objectAddress, highAddress) > 0
            ) {
                return -1
            }
            if (objectAddress == lowAddress) return sourceIdAtAddressPosition(low)
            if (objectAddress == highAddress) return sourceIdAtAddressPosition(high)
            if (lowAddress == highAddress || high - low <= 1) return -1
            val numerator = objectAddress - lowAddress
            val denominator = highAddress - lowAddress
            val fraction = unsignedDouble(numerator) / unsignedDouble(denominator)
            val middle = (low + 1 + (fraction * (high - low - 1).toDouble()).toLong().toInt())
                .coerceIn(low + 1, high - 1)
            val middleAddress = index.addresses[middle]
            when {
                middleAddress == objectAddress -> return sourceIdAtAddressPosition(middle)
                java.lang.Long.compareUnsigned(middleAddress, objectAddress) < 0 -> low = middle
                else -> high = middle
            }
        }

        while (low <= high) {
            val middle = (low + high) ushr 1
            val middleAddress = index.addresses[middle]
            when {
                middleAddress == objectAddress -> return sourceIdAtAddressPosition(middle)
                java.lang.Long.compareUnsigned(middleAddress, objectAddress) < 0 -> low = middle + 1
                else -> high = middle - 1
            }
        }
        return -1
    }

    override fun namedReferences(objectId: Int): List<NamedReference> =
        readReferences(objectId, true).named

    override fun isClassObject(objectId: Int): Boolean =
        classIndexAt(objectId) == index.javaLangClassIndex

    override fun isObjectLive(objectId: Int): Boolean = checkNotNull(liveObjects).get(objectId)

    override fun classesNamed(
        name: ClassName,
        includeSubclasses: Boolean,
    ): List<HeapClassDescription> = classesNamed(name, includeSubclasses, onlyLive = true)

    override fun classesNamedIncludingUnreachable(
        name: ClassName,
        includeSubclasses: Boolean,
    ): List<HeapClassDescription> = classesNamed(name, includeSubclasses, onlyLive = false)

    private fun classesNamed(
        name: ClassName,
        includeSubclasses: Boolean,
        onlyLive: Boolean,
    ): List<HeapClassDescription> {
        val matchingIndexes = index.classes.indices.filter { candidate ->
            if (includeSubclasses) isSubtype(candidate, name.name) else index.classes[candidate].name == name.name
        }
        if (matchingIndexes.isEmpty()) return emptyList()
        val matching = BooleanArray(index.classes.size)
        val instances = arrayOfNulls<IntArrayList>(index.classes.size)
        for (classIndex in matchingIndexes) {
            matching[classIndex] = true
            instances[classIndex] = IntArrayList()
        }

        for (objectId in 0 until objectCount) {
            if (onlyLive && !isObjectLive(objectId)) continue
            val classIndex = classIndexAt(objectId)
            if (matching[classIndex]) instances[classIndex]!!.add(objectId)
        }
        return matchingIndexes.map { classIndex ->
            val heapClass = index.classes[classIndex]
            HeapClassDescription(
                heapClass.sourceObjectId,
                ClassName(heapClass.name),
                instances[classIndex]!!.toIntArray(),
            )
        }
    }

    override fun retainedSet(rootIds: IntArray): IntArray {
        progress("MAT retained set: opening indexes at the late retention boundary")
        System.setProperty("org.eclipse.mat.parser.internal.snapshot.ObjectMarkerFactory.useOldMarker", "false")
        val snapshot = MatSnapshotLoader.open(hprof, readObjectFields = false)
        try {
            var absentRootCount = 0
            val matRoots = IntArray(rootIds.size)
            var matRootCount = 0
            for (rootId in rootIds) {
                try {
                    val matId = snapshot.mapAddressToId(objectAddress(rootId))
                    matRoots[matRootCount] = matId
                    matRootCount++
                } catch (_: SnapshotException) {
                    absentRootCount++
                }
            }
            if (absentRootCount > 0) {
                progress("MAT retained set: $absentRootCount roots are absent from MAT's cleaned index")
            }
            val retained = snapshot.getRetainedSet(matRoots.copyOf(matRootCount), VoidProgressListener())
            var outputCount = 0
            val result = IntArray(retained.size)
            for (matId in retained) {
                val sourceId = resolveObjectAddress(snapshot.mapIdToAddress(matId))
                if (sourceId >= 0) result[outputCount++] = sourceId
            }
            return result.copyOf(outputCount)
        } finally {
            snapshot.dispose()
        }
    }

    override fun close() {
        reader.close()
        index.close()
    }

    private fun initializeLiveness() {
        ensureLiveObjects()
    }

    private fun ensureLiveObjects() {
        if (Files.exists(index.liveObjectsPath)) {
            liveObjects = BitSet.valueOf(Files.readAllBytes(index.liveObjectsPath))
            return
        }
        FileChannel.open(index.lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { channel ->
            val lock = channel.lock()
            try {
                if (Files.exists(index.liveObjectsPath)) {
                    liveObjects = BitSet.valueOf(Files.readAllBytes(index.liveObjectsPath))
                    return
                }
                progress("HPROF liveness: marking objects reachable from GC roots")
                val startedAt = System.nanoTime()
                val live = BitSet(objectCount)
                val pending = PagedIntStack()
                fun mark(objectId: Int) {
                    if (objectId >= 0 && !live.get(objectId)) {
                        live.set(objectId)
                        pending.push(objectId)
                    }
                }

                for (rootIndex in 0 until index.gcRootCount) {
                    mark(resolveObjectAddress(index.gcRoots[rootIndex]))
                }
                val classesByLoader = Int2ObjectOpenHashMap<IntArrayList>()
                // MAT skips these synthetic edges altogether when a bootstrap loader is present.
                val hasBootstrapLoader = index.classes.any { it.classLoaderAddress == 0L }
                if (!hasBootstrapLoader) {
                    for (heapClass in index.classes) {
                        if (heapClass.sourceObjectId >= 0 && heapClass.classLoaderAddress != 0L) {
                            val loaderId = resolveObjectAddress(heapClass.classLoaderAddress)
                            if (loaderId >= 0) {
                                classesByLoader.getOrPut(loaderId) { IntArrayList() }
                                    .add(heapClass.sourceObjectId)
                            }
                        }
                    }
                }

                var visited = 0
                var nextProgress = 10_000_000
                while (!pending.isEmpty()) {
                    val sourceId = pending.pop()
                    for (targetId in readReferences(sourceId, includeNames = false, forLiveness = true).ids) {
                        mark(targetId)
                    }
                    classesByLoader.get(sourceId)?.let { loadedClasses ->
                        for (index in 0 until loadedClasses.size) mark(loadedClasses.getInt(index))
                    }
                    visited++
                    if (visited >= nextProgress) {
                        progress("  liveness: $visited objects, pending ${pending.size}")
                        nextProgress += 10_000_000
                    }
                }
                pending.release()
                val elapsed = (System.nanoTime() - startedAt) / 1_000_000_000.0
                progress(
                    "HPROF liveness: ${live.cardinality()} live, ${objectCount - live.cardinality()} " +
                        "GC-unreachable in ${"%.1f".format(elapsed)} s"
                )

                val temporary = index.liveObjectsPath.resolveSibling("live-objects-${UUID.randomUUID()}.bin")
                Files.write(temporary, live.toByteArray())
                try {
                    Files.move(temporary, index.liveObjectsPath, StandardCopyOption.ATOMIC_MOVE)
                } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                    Files.move(temporary, index.liveObjectsPath)
                }
                liveObjects = live
            } finally {
                lock.release()
            }
        }
    }

    private fun classAt(objectId: Int): HprofClassDefinition {
        val classIndex = classIndexAt(objectId)
        require(classIndex >= 0) { "Object $objectId has no resolved HPROF class" }
        return index.classes[classIndex]
    }

    private fun classIndexAt(objectId: Int): Int = index.classIndexes[objectId] - 1

    private fun sourceIdAtAddressPosition(position: Int): Int = index.addressObjectIds?.get(position) ?: position

    private fun alignArraySize(payloadSize: Long, primitive: Boolean): ULong {
        var header = index.identifierSize.toLong() + index.referenceSize + Int.SIZE_BYTES
        if (primitive) {
            header = (header + index.referenceSize - 1) / index.referenceSize * index.referenceSize
        }
        return (((header + payloadSize) + 7) and -8L).toULong()
    }

    private fun isSubtype(classIndex: Int, targetName: String): Boolean {
        var current = classIndex
        while (current >= 0) {
            val heapClass = index.classes[current]
            if (heapClass.name == targetName) return true
            current = heapClass.superIndex
        }
        return false
    }

    private fun fieldsOf(classIndex: Int): List<HprofFieldDefinition> {
        allFields[classIndex]?.let { return it }
        val heapClass = index.classes[classIndex]
        val inherited = if (heapClass.superIndex >= 0) fieldsOf(heapClass.superIndex) else emptyList()
        val result = heapClass.fields + inherited
        allFields[classIndex] = result
        return result
    }

    private fun readReferences(
        objectId: Int,
        includeNames: Boolean,
        forLiveness: Boolean = false,
    ): ReferenceView {
        reader.seek(index.recordOffsets[objectId])
        val tag = reader.readUnsignedByte()
        if (tag == PRIMITIVE_ARRAY_DUMP && !forLiveness) return EmptyReferences
        val expectedReferences = if (tag == INSTANCE_DUMP) {
            fieldsOf(classIndexAt(objectId)).count { it.type == HprofType.OBJECT }
        } else {
            4
        }
        val ids = IntArrayList(expectedReferences + if (forLiveness) 1 else 0)
        val named = if (includeNames) ArrayList<NamedReference>(expectedReferences) else null
        // MAT's GC marker follows class pseudo-references; structural extraction deliberately does not.
        if (forLiveness) ids.add(classAt(objectId).sourceObjectId)
        when (tag) {
            INSTANCE_DUMP -> {
                reader.skipIdentifiers(1)
                reader.skip(4)
                reader.skipIdentifiers(1)
                val byteLength = reader.readUnsignedInt()
                val dataEnd = reader.position() + byteLength
                for (field in fieldsOf(classIndexAt(objectId))) {
                    if (field.type == HprofType.OBJECT) {
                        val address = reader.readIdentifier()
                        if (address != 0L) {
                            val targetId = resolveObjectAddress(address)
                            ids.add(targetId)
                            named?.add(NamedReference(field.name, targetId, address))
                        }
                    } else {
                        reader.skip(hprofTypeSize(field.type, index.identifierSize).toLong())
                    }
                }
                require(reader.position() == dataEnd) {
                    "HPROF fields of $objectId (${className(objectId).name}) consume " +
                        "${reader.position() - (dataEnd - byteLength)} of $byteLength bytes"
                }
            }
            OBJECT_ARRAY_DUMP -> {
                reader.skipIdentifiers(1)
                reader.skip(4)
                val length = reader.readLength()
                reader.skipIdentifiers(1)
                repeat(length) { elementIndex ->
                    val address = reader.readIdentifier()
                    if (address != 0L) {
                        val targetId = resolveObjectAddress(address)
                        ids.add(targetId)
                        named?.add(NamedReference("[$elementIndex]", targetId, address))
                    }
                }
            }
            CLASS_DUMP -> {
                fun addClassReference(address: Long, name: String) {
                    if (address == 0L) return
                    val targetId = resolveObjectAddress(address)
                    ids.add(targetId)
                    named?.add(NamedReference(name, targetId, address))
                }
                reader.skipIdentifiers(1)
                reader.skip(4)
                addClassReference(reader.readIdentifier(), "<super>")
                addClassReference(reader.readIdentifier(), "<classloader>")
                reader.skipIdentifiers(4)
                reader.skip(4)
                val constantCount = reader.readUnsignedShort()
                repeat(constantCount) {
                    reader.skip(2)
                    val type = reader.readUnsignedByte()
                    if (type == HprofType.OBJECT) {
                        addClassReference(reader.readIdentifier(), "<constant-pool>")
                    } else {
                        reader.skip(hprofTypeSize(type, index.identifierSize).toLong())
                    }
                }
                val staticCount = reader.readUnsignedShort()
                repeat(staticCount) {
                    reader.skipIdentifiers(1)
                    val type = reader.readUnsignedByte()
                    if (type == HprofType.OBJECT) {
                        addClassReference(reader.readIdentifier(), "<static>")
                    } else {
                        reader.skip(hprofTypeSize(type, index.identifierSize).toLong())
                    }
                }
            }
            PRIMITIVE_ARRAY_DUMP -> Unit
            else -> throw IllegalArgumentException("Object $objectId has unexpected HPROF tag $tag")
        }
        val elements = ids.elements()
        val targetIds = if (ids.size == elements.size) elements else elements.copyOf(ids.size)
        return ReferenceView(targetIds, named.orEmpty())
    }

    private data class ReferenceView(val ids: IntArray, val named: List<NamedReference>)
    private val EmptyReferences = ReferenceView(IntArray(0), emptyList())

    companion object {
        private const val INSTANCE_DUMP = 0x21
        private const val OBJECT_ARRAY_DUMP = 0x22
        private const val PRIMITIVE_ARRAY_DUMP = 0x23
        private const val CLASS_DUMP = 0x20

        fun open(hprof: Path, progress: (String) -> Unit = {}): HprofHeapObjectSource {
            val source = HprofHeapObjectSource(hprof, HprofIndex.open(hprof, progress), progress)
            try {
                source.initializeLiveness()
                return source
            } catch (failure: Throwable) {
                source.close()
                throw failure
            }
        }
    }
}

private class PagedIntStack {
    private val pages = ArrayList<IntArray>()
    private var page = -1
    private var offset = PAGE_SIZE

    var size = 0
        private set

    fun push(value: Int) {
        if (offset == PAGE_SIZE) {
            page++
            offset = 0
            if (page == pages.size) pages += IntArray(PAGE_SIZE)
        }
        pages[page][offset++] = value
        size++
    }

    fun pop(): Int {
        if (offset == 0) {
            page--
            offset = PAGE_SIZE
        }
        size--
        return pages[page][--offset]
    }

    fun isEmpty(): Boolean = size == 0

    fun release() {
        pages.clear()
        page = -1
        offset = PAGE_SIZE
        size = 0
    }

    private companion object {
        const val PAGE_SIZE = 1 shl 20
    }
}

private fun unsignedDouble(value: Long): Double =
    (value and Long.MAX_VALUE).toDouble() + if (value < 0) 9.223372036854776E18 else 0.0

/** Small reusable sliding window; sequential materialization does not issue a read per primitive. */
private class HprofRandomReader(path: Path, private val identifierSize: Int) : AutoCloseable {
    private val channel = java.nio.channels.FileChannel.open(path, java.nio.file.StandardOpenOption.READ)
    private val buffer = ByteBuffer.allocate(1 shl 20)
    private var bufferStart = 0L
    private var bufferEnd = 0L
    private var cursor = 0L
    private var sequentialReadSize = 16 * 1024

    fun position(): Long = cursor

    fun seek(position: Long) {
        cursor = position
        if (position < bufferStart || position > bufferEnd) fill(position)
        else buffer.position((position - bufferStart).toInt())
    }

    fun readUnsignedByte(): Int {
        ensure(1)
        cursor++
        return buffer.get().toInt() and 0xff
    }

    fun readUnsignedShort(): Int {
        ensure(2)
        cursor += 2
        return buffer.short.toInt() and 0xffff
    }

    fun readUnsignedInt(): Long {
        ensure(4)
        cursor += 4
        return buffer.int.toLong() and 0xffff_ffffL
    }

    fun readIdentifier(): Long {
        ensure(identifierSize)
        cursor += identifierSize
        return if (identifierSize == 4) buffer.int.toLong() and 0xffff_ffffL else buffer.long
    }

    fun readLength(): Int {
        val length = readUnsignedInt()
        require(length <= Int.MAX_VALUE) { "HPROF array is too large: $length" }
        return length.toInt()
    }

    fun skipIdentifiers(count: Int) = skip(identifierSize.toLong() * count)

    fun skip(byteCount: Long) {
        require(byteCount >= 0)
        seek(cursor + byteCount)
    }

    override fun close() = channel.close()

    private fun ensure(byteCount: Int) {
        if (cursor < bufferStart || cursor + byteCount > bufferEnd) fill(cursor)
        if (buffer.remaining() < byteCount) throw EOFException()
    }

    private fun fill(position: Long) {
        if (position == bufferEnd) {
            sequentialReadSize = minOf(buffer.capacity(), sequentialReadSize * 2)
        } else {
            sequentialReadSize = 16 * 1024
        }
        buffer.clear()
        buffer.limit(sequentialReadSize)
        val bytesRead = channel.read(buffer, position)
        if (bytesRead < 0) throw EOFException()
        buffer.flip()
        bufferStart = position
        bufferEnd = position + bytesRead
        buffer.position(0)
    }
}
