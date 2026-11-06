package shapes

import com.dynatrace.hash4j.hashing.HashStream128
import com.dynatrace.hash4j.hashing.Hashing
import java.lang.Long.compareUnsigned

/**
 * Cross-dump identity: XXH3-128 digest over a canonical binary encoding
 * streamed through hash4j (SHAPES.md "Hashing discipline"). Uses the fixed zero seed.
 * The digest is used for sorting/identity of *already merged* shapes only —
 * cons-map equality is the full structural tuple, never the digest.
 */
data class Digest128(val hi: Long, val lo: Long) : Comparable<Digest128> {
    override fun compareTo(other: Digest128): Int {
        val c = compareUnsigned(hi, other.hi)
        return if (c != 0) c else compareUnsigned(lo, other.lo)
    }

    fun hex(): String = "%016x%016x".format(hi, lo)
    override fun toString(): String = hex()
}

private val XXH3_128 = Hashing.xxh3_128()
private val DIGEST_STREAM = ThreadLocal.withInitial(XXH3_128::hashStream)

internal fun HashStream128.putDigest(d: Digest128) {
    putLong(d.hi)
    putLong(d.lo)
}

fun digestOf(build: HashStream128.() -> Unit): Digest128 {
    val stream = DIGEST_STREAM.get().reset()
    stream.build()
    val digest = stream.get()
    return Digest128(digest.mostSignificantBits, digest.leastSignificantBits)
}
