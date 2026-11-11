package shapes.input.graph

import java.io.EOFException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class BinaryFileReaderTest {
    @Test
    fun readsAndSkipsBigEndianValues() {
        val path = Files.createTempFile("binary-reader-", ".bin")
        try {
            Files.write(
                path,
                ByteBuffer.allocate(23)
                    .putInt(0x01020304)
                    .putLong(0x0102030405060708)
                    .put(byteArrayOf(9, 8, 7))
                    .putInt(42)
                    .putInt(-1)
                    .array(),
            )

            BinaryFileReader(path).use { reader ->
                assertEquals(0x01020304, reader.readInt())
                assertEquals(0x0102030405060708, reader.readLong())
                assertContentEquals(byteArrayOf(9, 8, 7), reader.readBytes(3))
                reader.skip(4)
                assertEquals(-1, reader.readInt())
                assertFailsWith<EOFException> { reader.readInt() }
            }
        } finally {
            Files.deleteIfExists(path)
        }
    }

    @Test
    fun remapsFilesLargerThanOneBuffer() {
        val path = Files.createTempFile("binary-reader-large-", ".bin")
        val regionBoundary = Int.MAX_VALUE.toLong()
        try {
            FileChannel.open(path, StandardOpenOption.WRITE).use { channel ->
                channel.position(regionBoundary - 2)
                val data = ByteBuffer.allocate(16)
                    .putInt(0x01020304)
                    .putLong(0x0102030405060708)
                    .putInt(42)
                    .flip()
                while (data.hasRemaining()) channel.write(data)
            }

            BinaryFileReader(path).use { reader ->
                reader.skip(regionBoundary - 2)
                assertEquals(0x01020304, reader.readInt())
                assertEquals(0x0102030405060708, reader.readLong())
                assertEquals(42, reader.readInt())
            }
            BinaryFileReader(path).use { reader ->
                reader.skip(regionBoundary + 10)
                assertEquals(42, reader.readInt())
            }
        } finally {
            Files.deleteIfExists(path)
        }
    }
}
