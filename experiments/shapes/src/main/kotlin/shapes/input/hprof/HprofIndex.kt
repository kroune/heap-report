package shapes.input.hprof

import it.unimi.dsi.fastutil.BigArrays
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap
import it.unimi.dsi.fastutil.longs.LongArrayList
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.UUID
import kotlin.io.path.exists
import kotlin.io.path.inputStream
import kotlin.io.path.outputStream
import kotlin.math.min

internal data class HprofClassDefinition(
    val sourceObjectId: Int,
    val address: Long,
    val classLoaderAddress: Long,
    val name: String,
    val superIndex: Int,
    val instanceSize: Int,
    val fields: List<HprofFieldDefinition>,
)

internal class HprofIndex(
    val hprof: Path,
    val identifierSize: Int,
    val referenceSize: Int,
    val objectCount: Int,
    val gcRootCount: Int,
    val classes: List<HprofClassDefinition>,
    val addresses: MappedLongArray,
    val recordOffsets: MappedLongArray,
    val classIndexes: MappedIntArray,
    val addressObjectIds: MappedIntArray?,
    val gcRoots: MappedLongArray,
) : AutoCloseable {
    val liveObjectsPath = cacheDirectory(hprof).resolve("live-objects.bin")
    val lockPath = hprof.resolveSibling("${hprof.fileName}.shapes-hprof.lock")
    val classIndexByAddress = Long2IntOpenHashMap(classes.size).apply {
        defaultReturnValue(-1)
        for (index in classes.indices) put(classes[index].address, index)
    }
    val javaLangClassIndex = classes.indexOfFirst { it.name == "java.lang.Class" }

    override fun close() {
        addresses.close()
        recordOffsets.close()
        classIndexes.close()
        addressObjectIds?.close()
        gcRoots.close()
    }

    companion object {
        private const val FORMAT_VERSION = 3
        private const val METADATA = "metadata.bin"

        fun open(hprof: Path, progress: (String) -> Unit = {}): HprofIndex {
            val directory = cacheDirectory(hprof)
            read(directory, hprof)?.let { return it }

            val lockPath = hprof.resolveSibling("${hprof.fileName}.shapes-hprof.lock")
            FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { channel ->
                val lock = channel.lock()
                try {
                    read(directory, hprof)?.let { return it }
                    progress("HPROF index: no valid cache at $directory; building")
                    val temporary = hprof.resolveSibling(
                        ".${hprof.fileName}.shapes-index-${UUID.randomUUID()}"
                    )
                    try {
                        build(hprof, temporary, progress)
                        if (directory.exists()) directory.toFile().deleteRecursively()
                        try {
                            Files.move(temporary, directory, StandardCopyOption.ATOMIC_MOVE)
                        } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                            Files.move(temporary, directory)
                        }
                    } catch (failure: Throwable) {
                        temporary.toFile().deleteRecursively()
                        throw failure
                    }
                    return checkNotNull(read(directory, hprof)) { "Cannot reopen the new HPROF index" }
                } finally {
                    lock.release()
                }
            }
        }

        private fun cacheDirectory(hprof: Path) =
            hprof.resolveSibling("${hprof.fileName}.shapes-hprof-v$FORMAT_VERSION")

        private fun read(directory: Path, hprof: Path): HprofIndex? {
            val metadata = directory.resolve(METADATA)
            if (!metadata.exists()) return null
            return try {
                DataInputStream(metadata.inputStream().buffered()).use { input ->
                    require(input.readInt() == FORMAT_VERSION)
                    require(input.readLong() == Files.size(hprof))
                    require(input.readLong() == Files.getLastModifiedTime(hprof).toMillis())
                    val identifierSize = input.readInt()
                    val referenceSize = input.readInt()
                    val objectCount = input.readInt()
                    val gcRootCount = input.readInt()
                    val hasAddressObjectIds = input.readBoolean()
                    val classCount = input.readInt()
                    val classes = ArrayList<HprofClassDefinition>(classCount)
                    repeat(classCount) {
                        val sourceObjectId = input.readInt()
                        val address = input.readLong()
                        val classLoaderAddress = input.readLong()
                        val name = input.readUTF()
                        val superIndex = input.readInt()
                        val instanceSize = input.readInt()
                        val fieldCount = input.readUnsignedShort()
                        val fields = ArrayList<HprofFieldDefinition>(fieldCount)
                        repeat(fieldCount) {
                            fields += HprofFieldDefinition(0L, input.readUTF(), input.readUnsignedByte())
                        }
                        classes += HprofClassDefinition(
                            sourceObjectId,
                            address,
                            classLoaderAddress,
                            name,
                            superIndex,
                            instanceSize,
                            fields,
                        )
                    }
                    HprofIndex(
                        hprof,
                        identifierSize,
                        referenceSize,
                        objectCount,
                        gcRootCount,
                        classes,
                        MappedLongArray(directory.resolve("addresses.bin"), objectCount),
                        MappedLongArray(directory.resolve("record-offsets.bin"), objectCount),
                        MappedIntArray(directory.resolve("class-indexes.bin"), objectCount),
                        if (hasAddressObjectIds) {
                            MappedIntArray(directory.resolve("address-object-ids.bin"), objectCount)
                        } else {
                            null
                        },
                        MappedLongArray(directory.resolve("gc-roots.bin"), gcRootCount),
                    )
                }
            } catch (_: Exception) {
                null
            }
        }

        private fun build(hprof: Path, directory: Path, progress: (String) -> Unit) {
            Files.createDirectories(directory)
            val scanner = HprofScanner(hprof)
            val counter = CountingListener()
            var startedAt = System.nanoTime()
            scanner.scan(counter)
            progress(
                "HPROF scan 1/2: ${counter.objectCount} objects in ${elapsedSeconds(startedAt)} s"
            )

            val addresses = PrimitiveIndexWriter(directory.resolve("addresses.bin"))
            val offsets = PrimitiveIndexWriter(directory.resolve("record-offsets.bin"))
            val classIndexes = PrimitiveIndexWriter(directory.resolve("class-indexes.bin"))
            val builder = IndexBuildingListener(counter.objectCount, addresses, offsets, classIndexes)
            startedAt = System.nanoTime()
            try {
                scanner.scan(builder)
            } catch (failure: Throwable) {
                addresses.close()
                offsets.close()
                classIndexes.close()
                throw failure
            }
            addresses.close(counter.objectCount, Long.SIZE_BYTES)
            offsets.close(counter.objectCount, Long.SIZE_BYTES)
            classIndexes.close(counter.objectCount, Int.SIZE_BYTES)
            val classes = builder.finish(directory, progress)
            progress("HPROF scan 2/2: object index built in ${elapsedSeconds(startedAt)} s")

            val hasAddressObjectIds = if (builder.addressesAreSorted) {
                false
            } else {
                progress("HPROF index: object addresses are not ordered; sorting address lookup")
                sortAddressLookup(directory, counter.objectCount, builder.lastAddressInversion)
                true
            }
            writeMetadata(
                directory.resolve(METADATA),
                hprof,
                builder.identifierSize,
                builder.referenceSize,
                counter.objectCount,
                builder.gcRoots.size,
                hasAddressObjectIds,
                classes,
            )
        }

        private fun writeMetadata(
            path: Path,
            hprof: Path,
            identifierSize: Int,
            referenceSize: Int,
            objectCount: Int,
            gcRootCount: Int,
            hasAddressObjectIds: Boolean,
            classes: List<HprofClassDefinition>,
        ) {
            DataOutputStream(path.outputStream().buffered()).use { output ->
                output.writeInt(FORMAT_VERSION)
                output.writeLong(Files.size(hprof))
                output.writeLong(Files.getLastModifiedTime(hprof).toMillis())
                output.writeInt(identifierSize)
                output.writeInt(referenceSize)
                output.writeInt(objectCount)
                output.writeInt(gcRootCount)
                output.writeBoolean(hasAddressObjectIds)
                output.writeInt(classes.size)
                for (heapClass in classes) {
                    output.writeInt(heapClass.sourceObjectId)
                    output.writeLong(heapClass.address)
                    output.writeLong(heapClass.classLoaderAddress)
                    output.writeUTF(heapClass.name)
                    output.writeInt(heapClass.superIndex)
                    output.writeInt(heapClass.instanceSize)
                    output.writeShort(heapClass.fields.size)
                    for (field in heapClass.fields) {
                        output.writeUTF(field.name)
                        output.writeByte(field.type)
                    }
                }
            }
        }

        private fun elapsedSeconds(startedAt: Long) =
            "%.1f".format((System.nanoTime() - startedAt) / 1_000_000_000.0)
    }
}

private class CountingListener : HprofScanListener {
    var objectCount = 0
        private set

    override fun onClassDump(value: HprofClassDump) {
        objectCount++
    }

    override fun onInstanceDump(recordOffset: Long, address: Long, classAddress: Long) {
        objectCount++
    }

    override fun onObjectArrayDump(recordOffset: Long, address: Long, classAddress: Long, length: Int) {
        objectCount++
    }

    override fun onPrimitiveArrayDump(recordOffset: Long, address: Long, length: Int, elementType: Int) {
        objectCount++
    }
}

private class MutableClass {
    var sourceObjectId = -1
    var address = 0L
    var classLoaderAddress = 0L
    var nameIdentifier = 0L
    var name = ""
    var superAddress = 0L
    var instanceSize = 0
    var fields: List<HprofFieldDefinition> = emptyList()
}

private class IndexBuildingListener(
    private val expectedObjectCount: Int,
    private val addresses: PrimitiveIndexWriter,
    private val offsets: PrimitiveIndexWriter,
    private val classIndexes: PrimitiveIndexWriter,
) : HprofScanListener {
    private val utf8 = Long2ObjectOpenHashMap<String>()
    private val classNameIdentifiers = Long2LongOpenHashMap()
    private val mutableClasses = ArrayList<MutableClass>()
    private val classIndexesByAddress = Long2IntOpenHashMap().apply { defaultReturnValue(-1) }
    private var nextObjectId = 0
    private var previousAddress = 0L
    private val primitiveArrayClassAddresses = LongArray(12)
    private var previousArrayStart = 0L
    private var previousArrayUncompressedEnd = 0L
    private var foundCompressedReferences = false

    val gcRoots = LongArrayList()
    var identifierSize = 0
        private set
    var referenceSize = 0
        private set
    var addressesAreSorted = true
        private set
    var lastAddressInversion = 0
        private set

    override fun onHeader(identifierSize: Int) {
        this.identifierSize = identifierSize
        this.referenceSize = identifierSize
    }

    override fun onUtf8(identifier: Long, value: String) {
        utf8.put(identifier, value)
    }

    override fun onLoadClass(classAddress: Long, classNameIdentifier: Long) {
        classNameIdentifiers.put(classAddress, classNameIdentifier)
        mutableClasses[classAt(classAddress)].nameIdentifier = classNameIdentifier
    }

    override fun onGCRoot(address: Long) {
        if (address != 0L) gcRoots.add(address)
    }

    override fun onClassDump(value: HprofClassDump) {
        val classIndex = classAt(value.address)
        val heapClass = mutableClasses[classIndex]
        heapClass.sourceObjectId = nextObjectId
        heapClass.classLoaderAddress = value.classLoaderAddress
        heapClass.superAddress = value.superAddress
        heapClass.instanceSize = value.instanceSize
        heapClass.fields = value.fields
        writeObject(value.recordOffset, value.address, 0)
    }

    override fun onInstanceDump(recordOffset: Long, address: Long, classAddress: Long) {
        writeObject(recordOffset, address, classAt(classAddress) + 1)
    }

    override fun onObjectArrayDump(recordOffset: Long, address: Long, classAddress: Long, length: Int) {
        if (!foundCompressedReferences && identifierSize == 8 &&
            address > previousArrayStart && address < previousArrayUncompressedEnd
        ) {
            referenceSize = 4
            foundCompressedReferences = true
        }
        previousArrayStart = address
        previousArrayUncompressedEnd = address + 16L + length.toLong() * 8L
        writeObject(recordOffset, address, classAt(classAddress) + 1)
    }

    override fun onPrimitiveArrayDump(recordOffset: Long, address: Long, length: Int, elementType: Int) {
        writeObject(recordOffset, address, primitiveArrayClassAt(elementType) + 1)
    }

    fun finish(directory: Path, progress: (String) -> Unit): List<HprofClassDefinition> {
        check(nextObjectId == expectedObjectCount)
        progress("HPROF layout: $identifierSize-byte identifiers, $referenceSize-byte references")
        for (heapClass in mutableClasses) {
            if (heapClass.nameIdentifier == 0L) {
                heapClass.nameIdentifier = classNameIdentifiers.get(heapClass.address)
            }
            val technicalName = utf8.get(heapClass.nameIdentifier)
                ?: throw IllegalArgumentException("Missing HPROF class name for 0x${heapClass.address.toString(16)}")
            heapClass.name = normalizeClassName(technicalName)
            heapClass.fields = heapClass.fields.map { field ->
                field.copy(name = utf8.get(field.nameIdentifier) ?: "<unresolved:${field.nameIdentifier}>")
            }
        }

        val javaLangClassSourceIds = mutableClasses
            .filter { it.name == "java.lang.Class" }
            .map { it.sourceObjectId }
        val javaLangClassIndex = mutableClasses.indexOfFirst { it.name == "java.lang.Class" }
        if (javaLangClassIndex >= 0) {
            FileChannel.open(directory.resolve("class-indexes.bin"), StandardOpenOption.WRITE).use { channel ->
                val patch = ByteBuffer.allocate(Int.SIZE_BYTES)
                for (heapClass in mutableClasses) {
                    if (heapClass.sourceObjectId < 0) continue
                    patch.clear()
                    patch.putInt(javaLangClassIndex + 1).flip()
                    channel.write(patch, heapClass.sourceObjectId.toLong() * Int.SIZE_BYTES)
                }
            }
        } else {
            progress("HPROF index: WARNING java.lang.Class is absent; class objects cannot be identified")
        }
        check(javaLangClassSourceIds.size <= 1) { "Multiple java.lang.Class definitions" }

        PrimitiveIndexWriter(directory.resolve("gc-roots.bin")).use { writer ->
            val iterator = gcRoots.iterator()
            while (iterator.hasNext()) writer.writeLong(iterator.nextLong())
            writer.close(gcRoots.size, Long.SIZE_BYTES)
        }
        val classes = mutableClasses.map { heapClass ->
            HprofClassDefinition(
                heapClass.sourceObjectId,
                heapClass.address,
                heapClass.classLoaderAddress,
                heapClass.name,
                if (heapClass.superAddress == 0L) -1 else classIndexesByAddress.get(heapClass.superAddress),
                heapClass.instanceSize,
                heapClass.fields,
            )
        }
        val instanceSizes = matInstanceSizes(classes, identifierSize, referenceSize)
        return classes.mapIndexed { index, heapClass ->
            heapClass.copy(instanceSize = instanceSizes[index])
        }
    }

    private fun writeObject(recordOffset: Long, address: Long, classIndexPlusOne: Int) {
        check(address != 0L) { "Null HPROF object address at $recordOffset" }
        if (nextObjectId > 0 && java.lang.Long.compareUnsigned(previousAddress, address) >= 0) {
            addressesAreSorted = false
            lastAddressInversion = nextObjectId
        }
        previousAddress = address
        addresses.writeLong(address)
        offsets.writeLong(recordOffset)
        classIndexes.writeInt(classIndexPlusOne)
        nextObjectId++
    }

    private fun classAt(address: Long): Int {
        val existing = classIndexesByAddress.get(address)
        if (existing >= 0) return existing
        val index = mutableClasses.size
        mutableClasses += MutableClass().apply { this.address = address }
        classIndexesByAddress.put(address, index)
        return index
    }

    private fun primitiveArrayClassAt(elementType: Int): Int {
        val cached = primitiveArrayClassAddresses[elementType]
        if (cached != 0L) return classAt(cached)
        val descriptor = when (elementType) {
            HprofType.BOOLEAN -> "[Z"
            HprofType.CHAR -> "[C"
            HprofType.FLOAT -> "[F"
            HprofType.DOUBLE -> "[D"
            HprofType.BYTE -> "[B"
            HprofType.SHORT -> "[S"
            HprofType.INT -> "[I"
            HprofType.LONG -> "[J"
            else -> throw IllegalArgumentException("Not a primitive array element type: $elementType")
        }
        for (entry in classNameIdentifiers.long2LongEntrySet()) {
            if (utf8.get(entry.longValue) == descriptor) {
                primitiveArrayClassAddresses[elementType] = entry.longKey
                return classAt(entry.longKey)
            }
        }
        throw IllegalArgumentException("Missing HPROF class definition for $descriptor")
    }
}

private fun normalizeClassName(technicalName: String): String {
    if (!technicalName.startsWith("[")) return technicalName.replace('/', '.')
    var dimensions = 0
    while (dimensions < technicalName.length && technicalName[dimensions] == '[') dimensions++
    val component = when (val marker = technicalName[dimensions]) {
        'Z' -> "boolean"
        'B' -> "byte"
        'C' -> "char"
        'S' -> "short"
        'I' -> "int"
        'J' -> "long"
        'F' -> "float"
        'D' -> "double"
        'L' -> technicalName.substring(dimensions + 1, technicalName.length - 1).replace('/', '.')
        else -> throw IllegalArgumentException("Unknown HPROF array component $marker in $technicalName")
    }
    return component + "[]".repeat(dimensions)
}

/** MAT recomputes instance sizes from hierarchy fields instead of trusting the raw HPROF size. */
private fun matInstanceSizes(
    classes: List<HprofClassDefinition>,
    identifierSize: Int,
    referenceSize: Int,
): IntArray {
    val result = IntArray(classes.size) { -1 }

    fun align(value: Int, alignment: Int): Int = (value + alignment - 1) / alignment * alignment

    fun recursiveSize(classIndex: Int): Int {
        val heapClass = classes[classIndex]
        if (heapClass.superIndex < 0) return identifierSize + referenceSize
        val ownSize = heapClass.fields.sumOf { field ->
            if (field.type == HprofType.OBJECT) referenceSize else hprofTypeSize(field.type, identifierSize)
        }
        return align(ownSize + recursiveSize(heapClass.superIndex), referenceSize)
    }

    for (classIndex in classes.indices) {
        result[classIndex] = if (classes[classIndex].name.endsWith("[]")) {
            referenceSize
        } else {
            align(recursiveSize(classIndex), 8)
        }
    }
    return result
}

/** Rare fallback for producers that do not emit objects in address order. */
private fun sortAddressLookup(directory: Path, objectCount: Int, lastInversion: Int) {
    PrimitiveIndexWriter(directory.resolve("address-object-ids.bin")).use { writer ->
        for (objectId in 0 until objectCount) writer.writeInt(objectId)
        writer.close(objectCount, Int.SIZE_BYTES)
    }
    SortableAddresses(directory, objectCount).use { addresses ->
        // Everything after the last adjacent inversion is already an ordered run.
        BigArrays.quickSort(
            0L,
            lastInversion.toLong(),
            { first, second -> addresses.compare(first, second) },
            { first, second -> addresses.swap(first, second) },
        )
        mergeAddressRuns(addresses, directory, objectCount, lastInversion)
    }
    Files.move(
        directory.resolve("sorted-addresses.bin"),
        directory.resolve("addresses.bin"),
        StandardCopyOption.REPLACE_EXISTING,
    )
    Files.move(
        directory.resolve("sorted-address-object-ids.bin"),
        directory.resolve("address-object-ids.bin"),
        StandardCopyOption.REPLACE_EXISTING,
    )
}

private fun mergeAddressRuns(
    addresses: SortableAddresses,
    directory: Path,
    objectCount: Int,
    secondRunStart: Int,
) {
    val sortedAddresses = PrimitiveIndexWriter(directory.resolve("sorted-addresses.bin"))
    val sortedIds = PrimitiveIndexWriter(directory.resolve("sorted-address-object-ids.bin"))
    var first = 0
    var second = secondRunStart
    while (first < secondRunStart || second < objectCount) {
        val takeFirst = second >= objectCount ||
            (first < secondRunStart && addresses.compare(first.toLong(), second.toLong()) <= 0)
        val source = if (takeFirst) first++ else second++
        sortedAddresses.writeLong(addresses.addressAt(source.toLong()))
        sortedIds.writeInt(addresses.objectIdAt(source.toLong()))
    }
    sortedAddresses.close(objectCount, Long.SIZE_BYTES)
    sortedIds.close(objectCount, Int.SIZE_BYTES)
}

private class SortableAddresses(directory: Path, private val objectCount: Int) : AutoCloseable {
    private val segmentSize = 1L shl 28
    private val addressChannel = FileChannel.open(
        directory.resolve("addresses.bin"), StandardOpenOption.READ, StandardOpenOption.WRITE
    )
    private val idChannel = FileChannel.open(
        directory.resolve("address-object-ids.bin"), StandardOpenOption.READ, StandardOpenOption.WRITE
    )
    private val segmentCount = ((objectCount.toLong() * Long.SIZE_BYTES - 1) / segmentSize).toInt() + 1
    private val addressSegments = Array(segmentCount) { index ->
        val start = index * segmentSize
        addressChannel.map(
            FileChannel.MapMode.READ_WRITE,
            start,
            min(segmentSize, objectCount.toLong() * Long.SIZE_BYTES - start),
        )
    }
    private val idSegmentCount = ((objectCount.toLong() * Int.SIZE_BYTES - 1) / segmentSize).toInt() + 1
    private val idSegments = Array(idSegmentCount) { index ->
        val start = index * segmentSize
        idChannel.map(
            FileChannel.MapMode.READ_WRITE,
            start,
            min(segmentSize, objectCount.toLong() * Int.SIZE_BYTES - start),
        )
    }

    fun compare(first: Long, second: Long): Int =
        java.lang.Long.compareUnsigned(getLong(first), getLong(second))

    fun addressAt(index: Long): Long = getLong(index)

    fun objectIdAt(index: Long): Int = getInt(index)

    fun swap(first: Long, second: Long) {
        val firstAddress = getLong(first)
        val secondAddress = getLong(second)
        putLong(first, secondAddress)
        putLong(second, firstAddress)
        val firstId = getInt(first)
        putInt(first, getInt(second))
        putInt(second, firstId)
    }

    private fun getLong(index: Long): Long {
        val offset = index * Long.SIZE_BYTES
        return addressSegments[(offset / segmentSize).toInt()].getLong((offset % segmentSize).toInt())
    }

    private fun putLong(index: Long, value: Long) {
        val offset = index * Long.SIZE_BYTES
        addressSegments[(offset / segmentSize).toInt()].putLong((offset % segmentSize).toInt(), value)
    }

    private fun getInt(index: Long): Int {
        val offset = index * Int.SIZE_BYTES
        return idSegments[(offset / segmentSize).toInt()].getInt((offset % segmentSize).toInt())
    }

    private fun putInt(index: Long, value: Int) {
        val offset = index * Int.SIZE_BYTES
        idSegments[(offset / segmentSize).toInt()].putInt((offset % segmentSize).toInt(), value)
    }

    override fun close() {
        addressChannel.force(true)
        idChannel.force(true)
        addressChannel.close()
        idChannel.close()
    }
}
