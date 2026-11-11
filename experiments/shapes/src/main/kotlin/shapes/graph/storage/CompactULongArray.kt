package shapes.graph.storage

/**
 * Append-only unsigned-long array that stores small values inline in an IntArray
 * and keeps only large values in a secondary ULongArray.
 */
internal class CompactULongArray(expectedSize: Int = 0) {
    init {
        require(expectedSize >= 0) { "negative expected size" }
    }

    val size: Int
        get() = valueCount

    private var immutable = false
    private var largeValueCount = 0
    private var largeValues = ULongArray(0)
    private var valueCount = 0
    private var inlineValues = IntArray(expectedSize)

    fun add(value: ULong) {
        check(!immutable) { "CompactULongArray is immutable" }
        ensureInlineCapacity()

        if (value <= Int.MAX_VALUE.toULong()) {
            inlineValues[valueCount++] = value.toInt()
            return
        }

        if (largeValueCount == largeValues.size) {
            val grownCapacity = (largeValues.size * 2).coerceAtLeast(largeValueCount + 1)
            largeValues = largeValues.copyOf(grownCapacity)
        }
        largeValues[largeValueCount] = value
        inlineValues[valueCount++] = -largeValueCount - 1
        largeValueCount++
    }

    private fun ensureInlineCapacity() {
        if (valueCount < inlineValues.size) return
        val grownCapacity = (inlineValues.size * 2).coerceAtLeast(valueCount + 1)
        inlineValues = inlineValues.copyOf(grownCapacity)
    }

    operator fun get(index: Int): ULong {
        val inlineValue = inlineValues[index]
        if (inlineValue >= 0) return inlineValue.toULong()
        return largeValues[-(inlineValue + 1)]
    }

    fun markImmutable() {
        immutable = true
        if (largeValues.size != largeValueCount) {
            largeValues = largeValues.copyOf(largeValueCount)
        }
        if (inlineValues.size != valueCount) {
            inlineValues = inlineValues.copyOf(valueCount)
        }
    }
}
