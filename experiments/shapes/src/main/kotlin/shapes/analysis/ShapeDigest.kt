package shapes.analysis

import com.dynatrace.hash4j.hashing.HashStream128
import com.dynatrace.hash4j.hashing.Hashing
import java.lang.Long.compareUnsigned

/** Stable cross-dump identity computed from a canonical structural encoding. */
data class ShapeDigest(
    val mostSignificantBits: Long,
    val leastSignificantBits: Long,
) : Comparable<ShapeDigest> {
    override fun compareTo(other: ShapeDigest): Int {
        val highBitsComparison = compareUnsigned(mostSignificantBits, other.mostSignificantBits)
        if (highBitsComparison != 0) return highBitsComparison
        return compareUnsigned(leastSignificantBits, other.leastSignificantBits)
    }

    fun hex(): String = "%016x%016x".format(mostSignificantBits, leastSignificantBits)

    override fun toString(): String = hex()
}

private val xxh3Hash = Hashing.xxh3_128()
private val digestStream = ThreadLocal.withInitial(xxh3Hash::hashStream)

internal class CanonicalShapeDigestBuilder(
    @PublishedApi internal val stream: HashStream128,
) {
    fun writeByte(value: Int) = stream.putByte(value.toByte())

    fun writeInt(value: Int) = stream.putInt(value)

    fun writeLong(value: Long) = stream.putLong(value)

    fun writeBytes(value: ByteArray) = stream.putBytes(value)

    fun writeUtf8(value: String) = stream.putStringUTF8(value)

    fun writeDigest(value: ShapeDigest) {
        stream.putLong(value.mostSignificantBits)
        stream.putLong(value.leastSignificantBits)
    }
}

internal fun computeShapeDigest(
    writeCanonicalForm: CanonicalShapeDigestBuilder.() -> Unit,
): ShapeDigest {
    val stream = digestStream.get().reset()
    CanonicalShapeDigestBuilder(stream).writeCanonicalForm()
    val digest = stream.get()
    return ShapeDigest(digest.mostSignificantBits, digest.leastSignificantBits)
}
