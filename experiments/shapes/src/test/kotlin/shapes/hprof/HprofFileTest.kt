package shapes.hprof

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import kotlin.test.Test
import kotlin.test.assertEquals

class HprofFileTest {
    @Test
    fun `primitive values and strings can cross mapped regions`() {
        val path = Files.createTempFile("shapes-regions", ".hprof")
        val boundary = 1L shl 29
        val payload = ByteArray(14) { (it + 1).toByte() }
        try {
            FileChannel.open(path, StandardOpenOption.WRITE).use { channel ->
                channel.write(ByteBuffer.wrap("JAVA PROFILE 1.0.2\u0000".toByteArray(Charsets.US_ASCII)))
                val header = ByteBuffer.allocate(12).order(ByteOrder.BIG_ENDIAN)
                    .putInt(8).putLong(0L).flip()
                channel.write(header)
                channel.position(boundary - 4)
                channel.write(ByteBuffer.wrap(payload))
            }
            HprofFile.open(path).use { file ->
                assertEquals(4, file.u1(boundary - 1))
                assertEquals(0x0405, file.u2(boundary - 1))
                assertEquals(0x03040506L, file.u4(boundary - 2))
                assertEquals(0x0102030405060708L, file.u8(boundary - 4))
                assertEquals(String(payload, Charsets.UTF_8), file.utf8(boundary - 4, payload.size))
            }
        } finally {
            Files.deleteIfExists(path)
        }
    }
}
