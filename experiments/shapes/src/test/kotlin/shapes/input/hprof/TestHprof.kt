package shapes.input.hprof

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.file.Path

internal fun writeTestHprof(
    path: Path,
    reverseInstances: Boolean = false,
    compressedReferenceDetection: Boolean = false,
    keepAllLive: Boolean = true,
) {
    val output = DataOutputStream(path.toFile().outputStream().buffered())
    output.use { hprof ->
        hprof.writeBytes("JAVA PROFILE 1.0.2")
        hprof.writeByte(0)
        hprof.writeInt(8)
        hprof.writeLong(0)

        val strings = linkedMapOf(
            100L to "java.lang.Class",
            101L to "example.Base",
            102L to "example.Node",
            103L to "[Lexample.Node;",
            104L to "[B",
            105L to "baseRef",
            106L to "next",
            107L to "value",
        )
        for ((identifier, value) in strings) {
            hprof.topLevelRecord(0x01) {
                writeLong(identifier)
                writeBytes(value)
            }
        }
        val classes = longArrayOf(1, 2, 3, 4, 5)
        val names = longArrayOf(100, 101, 102, 103, 104)
        for (index in classes.indices) {
            hprof.topLevelRecord(0x02) {
                writeInt(index + 1)
                writeLong(classes[index])
                writeInt(0)
                writeLong(names[index])
            }
        }
        hprof.topLevelRecord(0x0c) {
            if (keepAllLive) {
                for (address in 1L..5L) {
                    writeByte(0x05)
                    writeLong(address)
                }
                for (address in 10L..13L) {
                    writeByte(0xff)
                    writeLong(address)
                }
                if (compressedReferenceDetection) {
                    writeByte(0xff)
                    writeLong(14)
                }
            } else {
                writeByte(0x05)
                writeLong(1)
                writeByte(0x05)
                writeLong(3)
                writeByte(0xff)
                writeLong(10)
            }
            classDump(1, 0, 32)
            classDump(2, 0, 16, 105L to HprofType.OBJECT)
            classDump(3, 2, 24, 106L to HprofType.OBJECT, 107L to HprofType.INT)
            classDump(4, 0, 8)
            classDump(5, 0, 8)
            if (reverseInstances) {
                instanceDump(11, 3) {
                    writeLong(10)
                    writeInt(11)
                    writeLong(10)
                }
                instanceDump(10, 3) {
                    writeLong(11)
                    writeInt(10)
                    writeLong(11)
                }
            } else {
                instanceDump(10, 3) {
                    writeLong(11)
                    writeInt(10)
                    writeLong(11)
                }
                instanceDump(11, 3) {
                    writeLong(10)
                    writeInt(11)
                    writeLong(10)
                }
            }
            writeByte(0x22)
            writeLong(12)
            writeInt(0)
            writeInt(2)
            writeLong(4)
            writeLong(10)
            writeLong(11)
            writeByte(0x23)
            writeLong(13)
            writeInt(0)
            writeInt(3)
            writeByte(HprofType.BYTE)
            write(byteArrayOf(1, 2, 3))
            if (compressedReferenceDetection) {
                // This starts inside the preceding object array's uncompressed address range,
                // which is how MAT detects compressed references with 8-byte HPROF identifiers.
                writeByte(0x22)
                writeLong(14)
                writeInt(0)
                writeInt(0)
                writeLong(4)
            }
        }
    }
}

private fun DataOutputStream.topLevelRecord(tag: Int, body: DataOutputStream.() -> Unit) {
    val bytes = ByteArrayOutputStream()
    DataOutputStream(bytes).use { it.body() }
    writeByte(tag)
    writeInt(0)
    writeInt(bytes.size())
    write(bytes.toByteArray())
}

private fun DataOutputStream.classDump(
    address: Long,
    superAddress: Long,
    instanceSize: Int,
    vararg fields: Pair<Long, Int>,
) {
    writeByte(0x20)
    writeLong(address)
    writeInt(0)
    writeLong(superAddress)
    repeat(5) { writeLong(0) }
    writeInt(instanceSize)
    writeShort(0)
    writeShort(0)
    writeShort(fields.size)
    for ((name, type) in fields) {
        writeLong(name)
        writeByte(type)
    }
}

private fun DataOutputStream.instanceDump(address: Long, classAddress: Long, body: DataOutputStream.() -> Unit) {
    val bytes = ByteArrayOutputStream()
    DataOutputStream(bytes).use { it.body() }
    writeByte(0x21)
    writeLong(address)
    writeInt(0)
    writeLong(classAddress)
    writeInt(bytes.size())
    write(bytes.toByteArray())
}
