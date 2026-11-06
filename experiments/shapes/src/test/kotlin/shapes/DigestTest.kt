package shapes

import kotlin.test.Test
import kotlin.test.assertEquals

/** Expected values produced by the reference `xxhsum -H2` CLI (XXH3-128, seed 0). */
class DigestTest {

    private fun hashOf(s: String): String = digestOf { putBytes(s.toByteArray()) }.hex()

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
        assertEquals("f534f51e82a81d29989765d0ea7a5ecd", digestOf { putBytes(data) }.hex())
    }

    @Test
    fun canonicalEncodingReferenceVector() {
        val digest = digestOf {
            putByte(1)
            putStringUTF8("hello world")
            putInt(2)
            putDigest(Digest128(0x0123456789ABCDEF, 0xFEDCBA9876543210UL.toLong()))
            putInt(7)
        }
        assertEquals("b35287d13e7d424f4c136f4114f241dc", digest.hex())
    }
}
