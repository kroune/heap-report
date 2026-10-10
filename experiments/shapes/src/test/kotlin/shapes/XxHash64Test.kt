package shapes

import kotlin.test.Test
import kotlin.test.assertEquals

/** Expected values produced by the reference `xxhsum` CLI (seed 0). */
class XxHash64Test {

    private fun hashOf(s: String): String = "%016x".format(XxHash64.hash(s.toByteArray()))

    @Test
    fun referenceVectors() {
        assertEquals("ef46db3751d8e999", hashOf(""))
        assertEquals("d24ec4f1a98c6e5b", hashOf("a"))
        assertEquals("44bc2cf5ad770999", hashOf("abc"))
        assertEquals("45ab6734b21e6968", hashOf("hello world"))
        // > 32 bytes: exercises the 4-lane stripe path
        assertEquals("e35216444a3c253b", hashOf("0123456789abcdef0123456789abcdef0123456789abcdef"))
    }

    @Test
    fun referenceVectorLong() {
        val data = ByteArray(1000) { ((it * 31 + 7) % 256).toByte() }
        assertEquals("99594f4828043d35", "%016x".format(XxHash64.hash(data)))
    }

    @Test
    fun seedChangesHash() {
        val data = "hello world".toByteArray()
        kotlin.test.assertNotEquals(XxHash64.hash(data, 0), XxHash64.hash(data, 1))
    }
}
