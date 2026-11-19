package shapes.hprof

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.file.Files
import java.nio.file.Path

/**
 * Minimal HPROF 1.0.2 writer for extractor tests: 8-byte identifiers, one
 * heap-dump segment, explicit control over classes, objects and roots.
 */
class HprofTestDump {
    private class ClassSpec(
        val classId: Long,
        val name: String,
        val superClassId: Long,
        val fields: List<Pair<String, Int>>, // name to HprofType code
        val staticRefs: List<Long>,
        val loaderId: Long,
    )

    private class InstanceSpec(val objectId: Long, val classId: Long, val fieldValues: List<Long>)
    private class ObjectArraySpec(val objectId: Long, val classId: Long, val elements: LongArray)
    private class PrimitiveArraySpec(val objectId: Long, val count: Int, val elementType: Int)

    private val classes = ArrayList<ClassSpec>()
    private val instances = ArrayList<InstanceSpec>()
    private val objectArrays = ArrayList<ObjectArraySpec>()
    private val primitiveArrays = ArrayList<PrimitiveArraySpec>()
    private val roots = ArrayList<Long>()
    private val threadObjects = ArrayList<Pair<Long, Int>>() // address to serial
    private val threadLocals = ArrayList<Pair<Long, Int>>() // address to thread serial

    /** Field types default to OBJECT; use `name to HprofType.X` for primitives. */
    fun clazz(
        classId: Long,
        name: String,
        superClassId: Long = 0,
        fields: List<Pair<String, Int>> = emptyList(),
        staticRefs: List<Long> = emptyList(),
        loaderId: Long = 0,
    ) {
        classes.add(ClassSpec(classId, name, superClassId, fields, staticRefs, loaderId))
    }

    fun instance(objectId: Long, classId: Long, vararg fieldValues: Long) {
        instances.add(InstanceSpec(objectId, classId, fieldValues.toList()))
    }

    fun objectArray(objectId: Long, classId: Long, vararg elements: Long) {
        objectArrays.add(ObjectArraySpec(objectId, classId, elements))
    }

    fun primitiveArray(objectId: Long, count: Int, elementType: Int = HprofType.BYTE) {
        primitiveArrays.add(PrimitiveArraySpec(objectId, count, elementType))
    }

    fun root(objectId: Long) {
        roots.add(objectId)
    }

    fun threadRoot(threadObjectId: Long, serial: Int) {
        threadObjects.add(threadObjectId to serial)
    }

    fun javaFrameLocal(objectId: Long, threadSerial: Int) {
        threadLocals.add(objectId to threadSerial)
    }

    fun write(): Path {
        val stringIds = HashMap<String, Long>()
        var nextStringId = 1_000_000L
        fun stringId(text: String): Long = stringIds.getOrPut(text) { nextStringId++ }

        val heap = ByteArrayOutputStream()
        val heapOut = DataOutputStream(heap)

        for (root in roots) {
            heapOut.writeByte(HprofSubTag.ROOT_UNKNOWN)
            heapOut.writeLong(root)
        }
        for ((threadId, serial) in threadObjects) {
            heapOut.writeByte(HprofSubTag.ROOT_THREAD_OBJECT)
            heapOut.writeLong(threadId)
            heapOut.writeInt(serial)
            heapOut.writeInt(0)
        }
        for ((objectId, serial) in threadLocals) {
            heapOut.writeByte(HprofSubTag.ROOT_JAVA_FRAME)
            heapOut.writeLong(objectId)
            heapOut.writeInt(serial)
            heapOut.writeInt(0)
        }

        for (spec in classes) {
            heapOut.writeByte(HprofSubTag.CLASS_DUMP)
            heapOut.writeLong(spec.classId)
            heapOut.writeInt(0) // stack trace
            heapOut.writeLong(spec.superClassId)
            heapOut.writeLong(spec.loaderId)
            heapOut.writeLong(0) // signers
            heapOut.writeLong(0) // protection domain
            heapOut.writeLong(0)
            heapOut.writeLong(0)
            heapOut.writeInt(0) // instance size (unused by the reader)
            heapOut.writeShort(0) // constant pool
            heapOut.writeShort(spec.staticRefs.size)
            spec.staticRefs.forEachIndexed { index, target ->
                heapOut.writeLong(stringId("static$index"))
                heapOut.writeByte(HprofType.OBJECT)
                heapOut.writeLong(target)
            }
            heapOut.writeShort(spec.fields.size)
            for ((fieldName, fieldType) in spec.fields) {
                heapOut.writeLong(stringId(fieldName))
                heapOut.writeByte(fieldType)
            }
        }

        for (spec in instances) {
            val fieldTypes = classFieldTypes(spec.classId)
            heapOut.writeByte(HprofSubTag.INSTANCE_DUMP)
            heapOut.writeLong(spec.objectId)
            heapOut.writeInt(0)
            heapOut.writeLong(spec.classId)
            val body = ByteArrayOutputStream()
            val bodyOut = DataOutputStream(body)
            spec.fieldValues.forEachIndexed { index, value ->
                when (HprofType.byteSize(fieldTypes[index], 8)) {
                    8 -> bodyOut.writeLong(value)
                    4 -> bodyOut.writeInt(value.toInt())
                    2 -> bodyOut.writeShort(value.toInt())
                    else -> bodyOut.writeByte(value.toInt())
                }
            }
            heapOut.writeInt(body.size())
            heapOut.write(body.toByteArray())
        }

        for (spec in objectArrays) {
            heapOut.writeByte(HprofSubTag.OBJECT_ARRAY_DUMP)
            heapOut.writeLong(spec.objectId)
            heapOut.writeInt(0)
            heapOut.writeInt(spec.elements.size)
            heapOut.writeLong(spec.classId)
            for (element in spec.elements) heapOut.writeLong(element)
        }

        for (spec in primitiveArrays) {
            heapOut.writeByte(HprofSubTag.PRIMITIVE_ARRAY_DUMP)
            heapOut.writeLong(spec.objectId)
            heapOut.writeInt(0)
            heapOut.writeInt(spec.count)
            heapOut.writeByte(spec.elementType)
            repeat(spec.count * HprofType.byteSize(spec.elementType, 8)) { heapOut.writeByte(0) }
        }

        for (spec in classes) stringId(spec.name) // intern before UTF8 records

        val file = ByteArrayOutputStream()
        val out = DataOutputStream(file)
        out.writeBytes("JAVA PROFILE 1.0.2")
        out.writeByte(0)
        out.writeInt(8)
        out.writeLong(0)

        for ((text, id) in stringIds.entries.sortedBy { it.value }) {
            val bytes = text.toByteArray(Charsets.UTF_8)
            out.writeByte(HprofTag.UTF8)
            out.writeInt(0)
            out.writeInt(8 + bytes.size)
            out.writeLong(id)
            out.write(bytes)
        }
        classes.forEachIndexed { index, spec ->
            val nameId = stringId(spec.name)
            out.writeByte(HprofTag.LOAD_CLASS)
            out.writeInt(0)
            out.writeInt(4 + 8 + 4 + 8)
            out.writeInt(index + 1)
            out.writeLong(spec.classId)
            out.writeInt(0)
            out.writeLong(nameId)
        }
        out.writeByte(HprofTag.HEAP_DUMP_SEGMENT)
        out.writeInt(0)
        out.writeInt(heap.size())
        out.write(heap.toByteArray())
        out.writeByte(HprofTag.HEAP_DUMP_END)
        out.writeInt(0)
        out.writeInt(0)

        val path = Files.createTempFile("shapes-test", ".hprof")
        Files.write(path, file.toByteArray())
        return path
    }

    private fun classFieldTypes(classId: Long): List<Int> {
        var cursor: ClassSpec? = classes.first { it.classId == classId }
        val types = ArrayList<Int>()
        while (cursor != null) {
            types.addAll(cursor.fields.map { it.second })
            val superId = cursor.superClassId
            cursor = if (superId == 0L) null else classes.first { it.classId == superId }
        }
        return types
    }
}
