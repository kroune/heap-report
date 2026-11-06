package shapes

import java.io.EOFException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class FastReaderTest {
    @Test
    fun readsAndSkipsBigEndianValues() {
        val path = Files.createTempFile("fast-reader-", ".bin")
        try {
            Files.write(path, ByteBuffer.allocate(23)
                .putInt(0x01020304)
                .putLong(0x0102030405060708)
                .put(byteArrayOf(9, 8, 7))
                .putInt(42)
                .putInt(-1)
                .array())

            FastReader(path).use { reader ->
                assertEquals(0x01020304, reader.int())
                assertEquals(0x0102030405060708, reader.long())
                assertContentEquals(byteArrayOf(9, 8, 7), reader.bytes(3))
                reader.skip(4)
                assertEquals(-1, reader.int())
                assertFailsWith<EOFException> { reader.int() }
            }
        } finally {
            Files.deleteIfExists(path)
        }
    }

    @Test
    fun remapsFilesLargerThanOneBuffer() {
        val path = Files.createTempFile("fast-reader-large-", ".bin")
        val boundary = Int.MAX_VALUE.toLong()
        try {
            FileChannel.open(path, StandardOpenOption.WRITE).use { channel ->
                channel.position(boundary - 2)
                val data = ByteBuffer.allocate(16)
                    .putInt(0x01020304)
                    .putLong(0x0102030405060708)
                    .putInt(42)
                    .flip()
                while (data.hasRemaining()) channel.write(data)
            }

            FastReader(path).use { reader ->
                reader.skip(boundary - 2)
                assertEquals(0x01020304, reader.int())
                assertEquals(0x0102030405060708, reader.long())
                assertEquals(42, reader.int())
            }
            FastReader(path).use { reader ->
                reader.skip(boundary + 10)
                assertEquals(42, reader.int())
            }
        } finally {
            Files.deleteIfExists(path)
        }
    }
}
