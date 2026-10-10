package shapes

/**
 * XXHash64, single-shot. Reference: https://github.com/Cyan4973/xxHash
 * Verified against `xxhsum` test vectors in XxHash64Test.
 */
object XxHash64 {
    private const val PRIME64_1: Long = -7046029288634856825L // 0x9E3779B185EBCA87
    private const val PRIME64_2: Long = -4417276706812531889L // 0xC2B2AE3D27D4EB4F
    private const val PRIME64_3: Long = 1609587929392839161L  // 0x165667B19E3779F9
    private const val PRIME64_4: Long = -8796714831421723037L // 0x85EBCA77C2B2AE63
    private const val PRIME64_5: Long = 2870177450012600261L  // 0x27D4EB2F165667C5

    fun hash(input: ByteArray, seed: Long = 0L): Long {
        val length = input.size
        var offset = 0
        var hash: Long

        if (length >= 32) {
            var v1 = seed + PRIME64_1 + PRIME64_2
            var v2 = seed + PRIME64_2
            var v3 = seed
            var v4 = seed - PRIME64_1
            do {
                v1 = round(v1, getLong(input, offset)); offset += 8
                v2 = round(v2, getLong(input, offset)); offset += 8
                v3 = round(v3, getLong(input, offset)); offset += 8
                v4 = round(v4, getLong(input, offset)); offset += 8
            } while (offset <= length - 32)
            hash = (java.lang.Long.rotateLeft(v1, 1) + java.lang.Long.rotateLeft(v2, 7) +
                    java.lang.Long.rotateLeft(v3, 12) + java.lang.Long.rotateLeft(v4, 18))
            hash = mergeRound(hash, v1)
            hash = mergeRound(hash, v2)
            hash = mergeRound(hash, v3)
            hash = mergeRound(hash, v4)
        } else {
            hash = seed + PRIME64_5
        }
        hash += length.toLong()

        while (offset <= length - 8) {
            hash = hash xor round(0, getLong(input, offset))
            hash = java.lang.Long.rotateLeft(hash, 27) * PRIME64_1 + PRIME64_4
            offset += 8
        }
        if (offset <= length - 4) {
            hash = hash xor (getInt(input, offset).toLong() and 0xFFFFFFFFL) * PRIME64_1
            hash = java.lang.Long.rotateLeft(hash, 23) * PRIME64_2 + PRIME64_3
            offset += 4
        }
        while (offset < length) {
            hash = hash xor (input[offset].toLong() and 0xFF) * PRIME64_5
            hash = java.lang.Long.rotateLeft(hash, 11) * PRIME64_1
            offset++
        }

        hash = hash xor (hash ushr 33)
        hash *= PRIME64_2
        hash = hash xor (hash ushr 29)
        hash *= PRIME64_3
        hash = hash xor (hash ushr 32)
        return hash
    }

    private fun round(acc: Long, input: Long): Long =
        java.lang.Long.rotateLeft(acc + input * PRIME64_2, 31) * PRIME64_1

    private fun mergeRound(acc: Long, value: Long): Long =
        (acc xor round(0, value)) * PRIME64_1 + PRIME64_4

    private fun getLong(b: ByteArray, off: Int): Long {
        var r = 0L
        for (i in 7 downTo 0) r = (r shl 8) or (b[off + i].toLong() and 0xFF)
        return r
    }

    private fun getInt(b: ByteArray, off: Int): Int {
        var r = 0
        for (i in 3 downTo 0) r = (r shl 8) or (b[off + i].toInt() and 0xFF)
        return r
    }
}
