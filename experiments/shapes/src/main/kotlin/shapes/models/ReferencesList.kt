package shapes.models

class ReferencesList {
    private var freeOffsetsPosition = 0
    private var offsets = IntArray(0)
    private var freeReferencesFlatArrayPosition = 0
    private var referencesFlatArray = IntArray(0)
    private var isImmutable: Boolean = false

    fun add(references: IntArray) {
        if (isImmutable) {
            error("ReferencesList is immutable")
        }
        val neededSize = freeReferencesFlatArrayPosition + references.size
        if (referencesFlatArray.size < neededSize) {
            referencesFlatArray = referencesFlatArray.copyOf((referencesFlatArray.size * 2).coerceAtLeast(neededSize))
        }
        if (freeOffsetsPosition == offsets.size) {
            offsets = offsets.copyOf((offsets.size * 2).coerceAtLeast(freeOffsetsPosition + 1))
        }
        offsets[freeOffsetsPosition] = freeReferencesFlatArrayPosition
        freeOffsetsPosition++
        references.copyInto(referencesFlatArray, freeReferencesFlatArrayPosition)
        freeReferencesFlatArrayPosition += references.size
    }

    operator fun get(index: Int): ReferencesRange {
        val rangeEnd = offsets.getOrElse(index + 1) {
            freeReferencesFlatArrayPosition
        }
        return ReferencesRange(offsets[index], rangeEnd)
    }

    fun getReference(index: ReferencesRange, offset: Int): Int {
        require(0 <= offset && offset < index.size)
        return referencesFlatArray[index.start + offset]
    }

    internal inline fun forEachReference(index: ReferencesRange, lambda: (Int) -> Unit) {
        for (i in index.start until index.end) {
            lambda(referencesFlatArray[i])
        }
    }

    fun markImmutable() {
        isImmutable = true
        offsets = offsets.copyOf(freeOffsetsPosition)
        referencesFlatArray = referencesFlatArray.copyOf(freeReferencesFlatArrayPosition)
    }
}

@JvmInline
value class ReferencesRange(private val packed: Long) {
    constructor(start: Int, end: Int) : this((start.toLong() shl 32) or (end.toLong() and 0xFFFFFFFFL))

    val start: Int get() = (packed ushr 32).toInt()
    val end: Int get() = packed.toInt()
    val size: Int get() = end - start
}
