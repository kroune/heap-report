package shapes.analysis

import kotlin.test.Test
import kotlin.test.assertEquals

/** Expected values produced by `xxhsum -H2` (XXH3-128, seed 0). */
class ShapeDigestTest {
    private fun hashOf(text: String): String = computeShapeDigest {
        writeBytes(text.toByteArray())
    }.hex()

    @Test
    fun referenceVectors() {
        assertEquals("99aa06d3014798d86001c324468d497f", hashOf(""))
        assertEquals("a96faf705af16834e6c632b61e964e1f", hashOf("a"))
        assertEquals("06b05ab6733a618578af5f94892f3950", hashOf("abc"))
        assertEquals("df8d09e93f874900a99b8775cc15b6c7", hashOf("hello world"))
        assertEquals(
            "13701ebdb4a2e56a1ad9f3d72b368853",
            hashOf("0123456789abcdef0123456789abcdef0123456789abcdef"),
        )
    }

    @Test
    fun referenceVectorLong() {
        val data = ByteArray(1000) { ((it * 31 + 7) % 256).toByte() }
        val digest = computeShapeDigest { writeBytes(data) }
        assertEquals("f534f51e82a81d29989765d0ea7a5ecd", digest.hex())
    }

    @Test
    fun canonicalEncodingReferenceVector() {
        val childDigest = ShapeDigest(
            0x0123456789ABCDEF,
            0xFEDCBA9876543210UL.toLong(),
        )
        val digest = computeShapeDigest {
            writeByte(1)
            writeUtf8("hello world")
            writeInt(2)
            writeDigest(childDigest)
            writeInt(7)
        }
        assertEquals("b35287d13e7d424f4c136f4114f241dc", digest.hex())
    }
}
