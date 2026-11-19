package shapes.graph.storage

/** CSR storage for per-object outbound reference lists. */
class ReferencesList internal constructor(expectedObjects: Int = 0, expectedReferences: Int = 0) {
    init {
        require(expectedObjects >= 0 && expectedReferences >= 0) { "negative expected size" }
    }

    val referenceCount: Int
        get() = storedReferenceCount

    private var storedObjectCount = 0
    private var referenceOffsets = IntArray(expectedObjects)
    private var storedReferenceCount = 0
    @PublishedApi
    internal var flatReferences = IntArray(expectedReferences)
    private var immutable = false

    fun add(references: IntArray) {
        check(!immutable) { "ReferencesList is immutable" }
        ensureCapacity(references.size)

        referenceOffsets[storedObjectCount++] = storedReferenceCount
        references.copyInto(flatReferences, storedReferenceCount)
        storedReferenceCount += references.size
    }

    private fun ensureCapacity(newReferenceCount: Int) {
        val requiredReferenceCount = storedReferenceCount + newReferenceCount
        if (flatReferences.size < requiredReferenceCount) {
            val grownCapacity = (flatReferences.size * 2).coerceAtLeast(requiredReferenceCount)
            flatReferences = flatReferences.copyOf(grownCapacity)
        }
        if (storedObjectCount == referenceOffsets.size) {
            val grownCapacity = (referenceOffsets.size * 2).coerceAtLeast(storedObjectCount + 1)
            referenceOffsets = referenceOffsets.copyOf(grownCapacity)
        }
    }

    operator fun get(objectId: Int): ReferencesRange {
        val rangeEnd = referenceOffsets.getOrElse(objectId + 1) { storedReferenceCount }
        return ReferencesRange(referenceOffsets[objectId], rangeEnd)
    }

    fun getReference(range: ReferencesRange, referenceIndex: Int): Int {
        require(referenceIndex in 0 until range.size) {
            "reference index $referenceIndex is outside range of size ${range.size}"
        }
        return flatReferences[range.start + referenceIndex]
    }

    fun markImmutable() {
        immutable = true
        if (referenceOffsets.size != storedObjectCount) {
            referenceOffsets = referenceOffsets.copyOf(storedObjectCount)
        }
        if (flatReferences.size != storedReferenceCount) {
            flatReferences = flatReferences.copyOf(storedReferenceCount)
        }
    }
}

@JvmInline
value class ReferencesRange(private val packed: Long) {
    constructor(start: Int, end: Int) : this(
        (start.toLong() shl Int.SIZE_BITS) or (end.toLong() and 0xFFFFFFFFL)
    )

    val start: Int
        get() = (packed ushr Int.SIZE_BITS).toInt()

    val end: Int
        get() = packed.toInt()

    val size: Int
        get() = end - start
}
