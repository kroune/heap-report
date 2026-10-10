package shapes

import java.io.ByteArrayOutputStream

/**
 * Cross-dump identity: 128-bit digest over a canonical binary encoding
 * (SHAPES.md "Hashing discipline"). Two fixed-seed XXHash64 passes.
 * The digest is used for sorting/identity of *already merged* shapes only —
 * cons-map equality is the full structural tuple, never the digest.
 */
data class Digest128(val hi: Long, val lo: Long) : Comparable<Digest128> {
    override fun compareTo(other: Digest128): Int {
        val c = java.lang.Long.compareUnsigned(hi, other.hi)
        return if (c != 0) c else java.lang.Long.compareUnsigned(lo, other.lo)
    }

    fun hex(): String = "%016x%016x".format(hi, lo)
    override fun toString(): String = hex()
}

private val SEED_HI: Long = 0x243F6A8885A308D3UL.toLong() // pi digits, fixed
private val SEED_LO: Long = 0x9E3779B97F4A7C15UL.toLong() // golden ratio, fixed

/** Canonical binary encoding writer. Multi-byte ints are big-endian (fixed choice). */
class Canon {
    private val out = ByteArrayOutputStream()

    fun tag(v: Int) {
        out.write(v)
    }

    fun i32(v: Int) {
        out.write((v ushr 24) and 0xFF); out.write((v ushr 16) and 0xFF)
        out.write((v ushr 8) and 0xFF); out.write(v and 0xFF)
    }

    fun i64(v: Long) {
        i32((v ushr 32).toInt()); i32(v.toInt())
    }

    fun str(s: String) {
        val b = s.toByteArray(Charsets.UTF_8)
        i32(b.size)
        out.write(b)
    }

    fun digest(d: Digest128) {
        i64(d.hi); i64(d.lo)
    }

    fun bytes(): ByteArray = out.toByteArray()
}

fun digestOf(build: Canon.() -> Unit): Digest128 {
    val c = Canon()
    c.build()
    val b = c.bytes()
    return Digest128(XxHash64.hash(b, SEED_HI), XxHash64.hash(b, SEED_LO))
}
