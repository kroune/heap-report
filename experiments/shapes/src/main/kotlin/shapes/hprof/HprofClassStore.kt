package shapes.hprof

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap

/** One class's metadata from `CLASS_DUMP` + `LOAD_CLASS`. */
class HprofClass(
    val address: Long,
    val name: String,
    val superAddress: Long,
    /** Declared instance field names, this class only, declaration order. */
    val fieldNames: Array<String>,
    /** HPROF basic type codes parallel to [fieldNames]. */
    val fieldTypes: ByteArray,
    /**
     * Raw addresses this class object keeps alive: class loader, signers,
     * protection domain, static reference values and constant-pool references.
     * Used only by liveness marking; zeros excluded.
     */
    val retainedAddresses: LongArray,
) {
    /** Resolved by [HprofClassStore.resolveLayouts]. */
    var superIndex: Int = -1
        internal set

    /**
     * Byte offsets of reference-typed fields within the serialized instance
     * data, over the full chain: this class's declared fields first, then the
     * super chain, matching `INSTANCE_DUMP` serialization order.
     */
    var referenceFieldOffsets: IntArray = EMPTY_INTS
        internal set

    /** Field names parallel to [referenceFieldOffsets]. */
    var referenceFieldNames: Array<String> = EMPTY_NAMES
        internal set

    /** Serialized instance-data byte count over the full chain. */
    var instanceDataBytes: Int = 0
        internal set

    /** Modeled shallow heap size of one instance. */
    var shallowSize: Long = 0
        internal set

    val isArray: Boolean
        get() = name.endsWith("[]")

    private companion object {
        val EMPTY_INTS = IntArray(0)
        val EMPTY_NAMES = emptyArray<String>()
    }
}

/** All classes of a dump with resolved reference-field layouts. */
class HprofClassStore private constructor(
    private val file: HprofFile,
    val classes: ArrayList<HprofClass>,
    private val indexByAddress: Long2IntOpenHashMap,
) {
    fun indexOf(classAddress: Long): Int = indexByAddress.get(classAddress)

    operator fun get(classIndex: Int): HprofClass = classes[classIndex]

    val size: Int
        get() = classes.size

    fun requireByName(name: String): List<Int> =
        classes.indices.filter { classes[it].name == name }

    /** All classes whose chain contains any of [rootIndices]. */
    fun subtypesOf(rootIndices: Collection<Int>): BooleanArray {
        val matches = BooleanArray(classes.size)
        for (classIndex in classes.indices) {
            var cursor = classIndex
            while (cursor >= 0) {
                if (cursor in rootIndices) {
                    matches[classIndex] = true
                    break
                }
                cursor = classes[cursor].superIndex
            }
        }
        return matches
    }

    /** Reference (oop) size in the modeled memory layout; 4 under compressed oops. */
    var oopSize: Int = 8
        private set

    /** Modeled object header: 8-byte mark word + one klass oop. */
    val headerSize: Int
        get() = 8 + oopSize

    /** Modeled array header: object header + 4-byte length, 4-aligned. */
    val arrayHeaderSize: Int
        get() = headerSize + 4

    companion object {
        internal fun create(
            file: HprofFile,
            classes: ArrayList<HprofClass>,
            indexByAddress: Long2IntOpenHashMap,
            compressedOops: Boolean,
        ): HprofClassStore {
            val store = HprofClassStore(file, classes, indexByAddress)
            store.oopSize = if (compressedOops) 4 else 8
            store.resolveLayouts(file.identifierSize)
            return store
        }

        /** Converts `[Ljava/lang/String;` style JVM names to MAT-style source names. */
        fun convertJvmClassName(jvmName: String): String {
            var dimensions = 0
            while (dimensions < jvmName.length && jvmName[dimensions] == '[') dimensions++
            if (dimensions == 0) return jvmName.replace('/', '.')

            val element = when (jvmName[dimensions]) {
                'L' -> jvmName.substring(dimensions + 1, jvmName.length - 1).replace('/', '.')
                'B' -> "byte"
                'C' -> "char"
                'D' -> "double"
                'F' -> "float"
                'I' -> "int"
                'J' -> "long"
                'S' -> "short"
                'Z' -> "boolean"
                else -> throw IllegalArgumentException("bad array class name: $jvmName")
            }
            return element + "[]".repeat(dimensions)
        }
    }

    private fun resolveLayouts(identifierSize: Int) {
        for (hprofClass in classes) {
            hprofClass.superIndex =
                if (hprofClass.superAddress == 0L) -1 else indexByAddress.get(hprofClass.superAddress)
        }

        val offsets = ArrayList<Int>(16)
        val names = ArrayList<String>(16)
        for (hprofClass in classes) {
            offsets.clear()
            names.clear()
            var dataOffset = 0
            var memoryBytes = 0L
            var cursor: HprofClass? = hprofClass
            while (cursor != null) {
                for (fieldIndex in cursor.fieldNames.indices) {
                    val type = cursor.fieldTypes[fieldIndex].toInt()
                    if (type == HprofType.OBJECT) {
                        offsets.add(dataOffset)
                        names.add(cursor.fieldNames[fieldIndex])
                        memoryBytes += oopSize
                    } else {
                        memoryBytes += HprofType.byteSize(type, identifierSize)
                    }
                    dataOffset += HprofType.byteSize(type, identifierSize)
                }
                cursor = if (cursor.superIndex >= 0) classes[cursor.superIndex] else null
            }
            hprofClass.referenceFieldOffsets = offsets.toIntArray()
            hprofClass.referenceFieldNames = names.toTypedArray()
            hprofClass.instanceDataBytes = dataOffset
            hprofClass.shallowSize = alignToHeapGrain(headerSize + memoryBytes)
        }
    }
}

internal fun alignToHeapGrain(bytes: Long): Long = (bytes + 7) and 7L.inv()

/** Offset of the instance-field descriptor section within a CLASS_DUMP body. */
internal fun classDumpFieldSection(file: HprofFile, bodyStart: Long): Long {
    val identifierSize = file.identifierSize
    // class id + stack serial + super/loader/signers/domain/reserved×2 + instance size
    var offset = bodyStart + identifierSize + 4 + identifierSize.toLong() * 6 + 4

    val constantCount = file.u2(offset)
    offset += 2
    repeat(constantCount) {
        val type = file.u1(offset + 2)
        offset += 2 + 1 + HprofType.byteSize(type, identifierSize)
    }

    val staticCount = file.u2(offset)
    offset += 2
    repeat(staticCount) {
        val type = file.u1(offset + identifierSize)
        offset += identifierSize + 1 + HprofType.byteSize(type, identifierSize)
    }
    return offset
}

/** Advances past one heap sub-record without interpreting it. */
internal fun skipSubRecord(file: HprofFile, tag: Int, bodyStart: Long): Long {
    val identifierSize = file.identifierSize
    return when (tag) {
        HprofSubTag.CLASS_DUMP -> {
            var offset = classDumpFieldSection(file, bodyStart)
            val fieldCount = file.u2(offset)
            offset += 2
            offset + fieldCount.toLong() * (identifierSize + 1)
        }
        HprofSubTag.ROOT_UNKNOWN,
        HprofSubTag.ROOT_STICKY_CLASS,
        HprofSubTag.ROOT_MONITOR_USED -> bodyStart + identifierSize
        HprofSubTag.ROOT_JNI_GLOBAL -> bodyStart + 2L * identifierSize
        HprofSubTag.ROOT_JNI_LOCAL,
        HprofSubTag.ROOT_JAVA_FRAME,
        HprofSubTag.ROOT_THREAD_OBJECT -> bodyStart + identifierSize + 8
        HprofSubTag.ROOT_NATIVE_STACK,
        HprofSubTag.ROOT_THREAD_BLOCK -> bodyStart + identifierSize + 4
        HprofSubTag.INSTANCE_DUMP -> {
            val dataBytes = file.u4(bodyStart + identifierSize + 4 + identifierSize)
            bodyStart + identifierSize + 4 + identifierSize + 4 + dataBytes
        }
        HprofSubTag.OBJECT_ARRAY_DUMP -> {
            val count = file.u4(bodyStart + identifierSize + 4)
            bodyStart + identifierSize + 4 + 4 + identifierSize + count * identifierSize
        }
        HprofSubTag.PRIMITIVE_ARRAY_DUMP -> {
            val count = file.u4(bodyStart + identifierSize + 4)
            val elementType = file.u1(bodyStart + identifierSize + 4 + 4)
            bodyStart + identifierSize + 4 + 4 + 1 +
                count * HprofType.byteSize(elementType, identifierSize)
        }
        else -> throw IllegalStateException(
            "unknown heap sub-record tag 0x${tag.toString(16)} at $bodyStart"
        )
    }
}
