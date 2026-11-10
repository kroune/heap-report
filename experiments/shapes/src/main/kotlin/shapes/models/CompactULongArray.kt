package shapes.models

class CompactULongArray(expectedSize: Int = 0) {
    init {
        require(expectedSize >= 0) { "negative expected size" }
    }

    val size: Int
        get() = freeIntArrayPosition

    private var isImmutable: Boolean = false
    private var freeLongArrayPosition = 0
    private var longArray: ULongArray = ULongArray(0)
    private var freeIntArrayPosition = 0
    private var intArray: IntArray = IntArray(expectedSize)

    fun add(value: ULong) {
        if (isImmutable) {
            error("CompactULongArray is immutable")
        }
        if (freeIntArrayPosition == intArray.size) {
            intArray = intArray.copyOf((intArray.size * 2).coerceAtLeast(freeIntArrayPosition + 1))
        }
        if (value <= Int.MAX_VALUE.toULong()) {
            intArray[freeIntArrayPosition] = value.toInt()
            freeIntArrayPosition++
            return
        }

        if (freeLongArrayPosition == longArray.size) {
            longArray = longArray.copyOf((longArray.size * 2).coerceAtLeast(freeLongArrayPosition + 1))
        }
        longArray[freeLongArrayPosition] = value
        intArray[freeIntArrayPosition] = -freeLongArrayPosition - 1
        freeIntArrayPosition++
        freeLongArrayPosition++
    }

    operator fun get(index: Int): ULong {
        val result = intArray[index]
        if (result >= 0) {
            return result.toULong()
        }
        return longArray[-(result + 1)];
    }

    fun markImmutable() {
        isImmutable = true
        if (longArray.size != freeLongArrayPosition) longArray = longArray.copyOf(freeLongArrayPosition)
        if (intArray.size != freeIntArrayPosition) intArray = intArray.copyOf(freeIntArrayPosition)
    }
}