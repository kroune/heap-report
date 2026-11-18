package shapes.input.hprof

import java.io.BufferedInputStream
import java.io.EOFException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path

internal object HprofType {
    const val OBJECT = 2
    const val BOOLEAN = 4
    const val CHAR = 5
    const val FLOAT = 6
    const val DOUBLE = 7
    const val BYTE = 8
    const val SHORT = 9
    const val INT = 10
    const val LONG = 11
}

internal fun hprofTypeSize(type: Int, identifierSize: Int): Int = when (type) {
    HprofType.OBJECT -> identifierSize
    HprofType.BOOLEAN, HprofType.BYTE -> 1
    HprofType.CHAR, HprofType.SHORT -> 2
    HprofType.FLOAT, HprofType.INT -> 4
    HprofType.DOUBLE, HprofType.LONG -> 8
    else -> throw IllegalArgumentException("Unknown HPROF value type $type")
}

internal data class HprofFieldDefinition(
    val nameIdentifier: Long,
    val name: String,
    val type: Int,
)

internal data class HprofClassDump(
    val recordOffset: Long,
    val address: Long,
    val superAddress: Long,
    val classLoaderAddress: Long,
    val instanceSize: Int,
    val fields: List<HprofFieldDefinition>,
)

internal interface HprofScanListener {
    fun onHeader(identifierSize: Int) {}
    fun onUtf8(identifier: Long, value: String) {}
    fun onLoadClass(classAddress: Long, classNameIdentifier: Long) {}
    fun onGCRoot(address: Long) {}
    fun onClassDump(value: HprofClassDump) {}
    fun onInstanceDump(recordOffset: Long, address: Long, classAddress: Long) {}
    fun onObjectArrayDump(recordOffset: Long, address: Long, classAddress: Long, length: Int) {}
    fun onPrimitiveArrayDump(recordOffset: Long, address: Long, length: Int, elementType: Int) {}
}

/** Sequential HPROF 1.0 scanner. Object payload bytes are skipped, not materialized. */
internal class HprofScanner(private val path: Path) {
    fun scan(listener: HprofScanListener) {
        Files.newInputStream(path).use { input ->
            val reader = HprofInput(BufferedInputStream(input, 8 shl 20))
            reader.readHeader()
            listener.onHeader(reader.identifierSize)
            while (true) {
                val tag = try {
                    reader.readUnsignedByte()
                } catch (_: EOFException) {
                    break
                }
                reader.readUnsignedInt() // timestamp
                val recordLength = reader.readUnsignedInt()
                require(recordLength <= Long.MAX_VALUE) { "HPROF record is too large: $recordLength" }
                val recordEnd = reader.position + recordLength
                when (tag) {
                    UTF8 -> {
                        val identifier = reader.readIdentifier()
                        val byteLength = recordLength - reader.identifierSize
                        require(byteLength >= 0 && byteLength <= Int.MAX_VALUE) {
                            "Invalid UTF8 record length $byteLength"
                        }
                        listener.onUtf8(identifier, String(reader.readBytes(byteLength.toInt()), Charsets.UTF_8))
                    }
                    LOAD_CLASS -> {
                        reader.readUnsignedInt() // class serial
                        val classAddress = reader.readIdentifier()
                        reader.readUnsignedInt() // stack trace serial
                        listener.onLoadClass(classAddress, reader.readIdentifier())
                    }
                    HEAP_DUMP, HEAP_DUMP_SEGMENT -> scanHeapDump(reader, recordEnd, listener)
                    else -> reader.skipTo(recordEnd)
                }
                reader.skipTo(recordEnd)
                require(reader.position == recordEnd) { "HPROF tag $tag overran its record" }
            }
        }
    }

    private fun scanHeapDump(reader: HprofInput, end: Long, listener: HprofScanListener) {
        while (reader.position < end) {
            val recordOffset = reader.position
            when (val tag = reader.readUnsignedByte()) {
                CLASS_DUMP -> {
                    val address = reader.readIdentifier()
                    reader.readUnsignedInt() // stack trace serial
                    val superAddress = reader.readIdentifier()
                    val classLoaderAddress = reader.readIdentifier()
                    reader.skipIdentifiers(4) // signers, protection domain, reserved x2
                    val instanceSize = reader.readUnsignedInt()
                    require(instanceSize <= Int.MAX_VALUE) { "Class instance is too large: $instanceSize" }

                    val constantCount = reader.readUnsignedShort()
                    repeat(constantCount) {
                        reader.readUnsignedShort()
                        reader.skipValue(reader.readUnsignedByte())
                    }
                    val staticCount = reader.readUnsignedShort()
                    repeat(staticCount) {
                        reader.readIdentifier()
                        reader.skipValue(reader.readUnsignedByte())
                    }
                    val fieldCount = reader.readUnsignedShort()
                    val fields = ArrayList<HprofFieldDefinition>(fieldCount)
                    repeat(fieldCount) {
                        fields += HprofFieldDefinition(reader.readIdentifier(), "", reader.readUnsignedByte())
                    }
                    listener.onClassDump(
                        HprofClassDump(
                            recordOffset,
                            address,
                            superAddress,
                            classLoaderAddress,
                            instanceSize.toInt(),
                            fields,
                        )
                    )
                }
                INSTANCE_DUMP -> {
                    val address = reader.readIdentifier()
                    reader.readUnsignedInt()
                    val classAddress = reader.readIdentifier()
                    val byteLength = reader.readUnsignedInt()
                    listener.onInstanceDump(recordOffset, address, classAddress)
                    reader.skip(byteLength)
                }
                OBJECT_ARRAY_DUMP -> {
                    val address = reader.readIdentifier()
                    reader.readUnsignedInt()
                    val length = reader.readLength()
                    val classAddress = reader.readIdentifier()
                    listener.onObjectArrayDump(recordOffset, address, classAddress, length)
                    reader.skip(length.toLong() * reader.identifierSize)
                }
                PRIMITIVE_ARRAY_DUMP -> {
                    val address = reader.readIdentifier()
                    reader.readUnsignedInt()
                    val length = reader.readLength()
                    val elementType = reader.readUnsignedByte()
                    listener.onPrimitiveArrayDump(recordOffset, address, length, elementType)
                    reader.skip(length.toLong() * hprofTypeSize(elementType, reader.identifierSize))
                }
                else -> scanRootRecord(reader, tag, listener)
            }
        }
        require(reader.position == end) { "Heap dump subrecord overran its segment" }
    }

    private fun scanRootRecord(reader: HprofInput, tag: Int, listener: HprofScanListener) {
        when (tag) {
            ROOT_UNKNOWN, ROOT_STICKY_CLASS, ROOT_MONITOR_USED -> listener.onGCRoot(reader.readIdentifier())
            ROOT_JNI_GLOBAL, ROOT_JNI_LOCAL, ROOT_JAVA_FRAME, ROOT_NATIVE_STACK,
            ROOT_THREAD_BLOCK, ROOT_THREAD_OBJECT, ROOT_JNI_MONITOR,
            -> {
                listener.onGCRoot(reader.readIdentifier())
                reader.skip(8)
            }
            ROOT_INTERNED_STRING, ROOT_FINALIZING, ROOT_UNFINALIZED,
            ROOT_VM_INTERNAL, ROOT_CLASS_LOADER,
            -> listener.onGCRoot(reader.readIdentifier())
            ROOT_UNREACHABLE -> reader.skipIdentifiers(1)
            else -> throw IllegalArgumentException(
                "Unsupported HPROF heap subrecord 0x${tag.toString(16)} at ${reader.position - 1}"
            )
        }
    }

    private companion object {
        const val UTF8 = 0x01
        const val LOAD_CLASS = 0x02
        const val HEAP_DUMP = 0x0c
        const val HEAP_DUMP_SEGMENT = 0x1c

        const val ROOT_UNKNOWN = 0xff
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
        const val ROOT_INTERNED_STRING = 0x89
        const val ROOT_FINALIZING = 0x8a
        const val ROOT_UNFINALIZED = 0x8b
        const val ROOT_UNREACHABLE = 0x8c
        const val ROOT_JNI_MONITOR = 0x8d
        const val ROOT_VM_INTERNAL = 0x8e
        const val ROOT_CLASS_LOADER = 0x8f
    }
}

private class HprofInput(input: InputStream) {
    private val input = object : java.io.DataInputStream(input) {}
    private val headerBytes = ArrayList<Byte>()

    var position = 0L
        private set
    var identifierSize = 0
        private set

    fun readHeader() {
        var byte = readUnsignedByte()
        while (byte != 0) {
            headerBytes += byte.toByte()
            byte = readUnsignedByte()
        }
        val version = String(headerBytes.toByteArray(), Charsets.US_ASCII)
        require(version.startsWith("JAVA PROFILE 1.0")) { "Unsupported HPROF header: $version" }
        identifierSize = readUnsignedInt().toInt()
        require(identifierSize == 4 || identifierSize == 8) { "Unsupported HPROF id size $identifierSize" }
        readUnsignedInt()
        readUnsignedInt()
    }

    fun readUnsignedByte(): Int {
        val value = input.read()
        if (value < 0) throw EOFException()
        position++
        return value
    }

    fun readUnsignedShort(): Int {
        val high = readUnsignedByte()
        return (high shl 8) or readUnsignedByte()
    }

    fun readUnsignedInt(): Long {
        var value = 0L
        repeat(4) { value = (value shl 8) or readUnsignedByte().toLong() }
        return value
    }

    fun readIdentifier(): Long = if (identifierSize == 4) readUnsignedInt() else {
        var value = 0L
        repeat(8) { value = (value shl 8) or readUnsignedByte().toLong() }
        value
    }

    fun readBytes(length: Int): ByteArray {
        val bytes = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val count = input.read(bytes, offset, length - offset)
            if (count < 0) throw EOFException()
            offset += count
            position += count
        }
        return bytes
    }

    fun readLength(): Int {
        val length = readUnsignedInt()
        require(length <= Int.MAX_VALUE) { "HPROF array is too large: $length" }
        return length.toInt()
    }

    fun skipIdentifiers(count: Int) = skip(count.toLong() * identifierSize)

    fun skipValue(type: Int) = skip(hprofTypeSize(type, identifierSize).toLong())

    fun skipTo(target: Long) = skip(target - position)

    fun skip(byteCount: Long) {
        require(byteCount >= 0) { "Negative HPROF skip $byteCount" }
        var remaining = byteCount
        while (remaining > 0) {
            val skipped = input.skip(remaining)
            if (skipped > 0) {
                remaining -= skipped
                position += skipped
            } else {
                readUnsignedByte()
                remaining--
            }
        }
    }
}
