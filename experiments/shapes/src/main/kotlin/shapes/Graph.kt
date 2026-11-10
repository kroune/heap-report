package shapes

import shapes.models.ClassName
import shapes.models.CompactULongArray
import shapes.models.ReferencesList

/**
 * Compact object graph used by the analyzer (SHAPES.md "Definitions"):
 * objects + outbound references. Object ids are dense, 0-based.
 *
 * CSR storage (flat refs + offsets, class table + per-object index): at
 * 10M+ objects, per-node IntArray shells cost more than the refs themselves.
 * Hot loops use [ReferencesList.forEachReference].
 *
 * Multi-edges are allowed and meaningful (for example Object[]{x, x}).
 * Parsing and serialization live in shapes.input, not in this model.
 */
class ObjectGraph(
    val classNames: Array<ClassName>,
    val classIdx: CharArray,       // per object -> classNames index (classCount <= 65535)
    val shallow: CompactULongArray,
    val referencesList: ReferencesList,
    /** object count of the source heap, else == size. */
    val sourceObjectCount: Long = shallow.size.toLong(),
) {
    val size: Int get() = shallow.size
    val referenceCount: Int get() = referencesList.referenceCount

    init {
        require(classNames.size <= 65535) { "class table exceeds 65535 (CharArray classIdx)" }
        require(classIdx.size == size) { "class index count does not match object count" }
    }

    fun className(index: Int): ClassName = classNames[classIdx[index].code]

    fun instancesOf(className: ClassName): IntArray {
        val index = classNames.indexOf(className)
        if (index == -1) return IntArray(0)
        var count = 0
        for (i in classIdx.indices) if (classIdx[i].code == index) count++
        val out = IntArray(count)
        var p = 0
        for (i in classIdx.indices) if (classIdx[i].code == index) out[p++] = i
        return out
    }

    class Builder(
        private val sourceObjectCount: Long? = null,
        expectedObjectCount: Int = 0,
    ) {
        init {
            require(expectedObjectCount >= 0) { "negative expected object count" }
        }

        private val classNames = ArrayList<ClassName>()
        private val classIdxOf = HashMap<ClassName, Int>()
        private var objectClasses = CharArray(expectedObjectCount)
        private var objectCount = 0
        private val shallowSizes = CompactULongArray(expectedObjectCount)
        private val referencesList = ReferencesList(expectedObjectCount)

        /** Register a class during the single input pass; no per-object names are retained. */
        fun classIndex(className: ClassName): Int = classIdxOf.getOrPut(className) {
            check(classNames.size < 65535) { "class table exceeds 65535 entries" }
            classNames.add(className)
            classNames.size - 1
        }

        fun add(className: ClassName, shallow: ULong, references: IntArray = IntArray(0)): Int =
            add(classIndex(className), shallow, references)

        /** Hot-path insertion for inputs that already resolved their class table index. */
        fun add(classIndex: Int, shallow: ULong, references: IntArray = IntArray(0)): Int {
            require(classIndex in classNames.indices) { "unregistered class index $classIndex" }
            if (objectCount == objectClasses.size) {
                objectClasses = objectClasses.copyOf((objectClasses.size * 2).coerceAtLeast(objectCount + 1))
            }
            objectClasses[objectCount] = classIndex.toChar()
            shallowSizes.add(shallow)
            referencesList.add(references)
            return objectCount++
        }

        fun build(): ObjectGraph {
            referencesList.markImmutable()
            shallowSizes.markImmutable()
            return ObjectGraph(
                classNames = classNames.toTypedArray(),
                classIdx = if (objectClasses.size == objectCount) objectClasses else objectClasses.copyOf(objectCount),
                shallow = shallowSizes,
                referencesList = referencesList,
                sourceObjectCount = sourceObjectCount ?: objectCount.toLong(),
            )
        }
    }

    companion object {
        /** Direct construction from per-node arrays (tests and small synthetic inputs). */
        fun of(classes: Array<ClassName>, shallow: ULongArray, refs: Array<IntArray>): ObjectGraph {
            val builder = Builder()
            for (index in classes.indices) builder.add(classes[index], shallow[index], refs[index])
            return builder.build()
        }
    }
}
