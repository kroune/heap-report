package shapes.graph

import shapes.graph.storage.CompactULongArray
import shapes.graph.storage.ReferencesList
import java.util.Collections

/**
 * Compact object graph with dense, zero-based object ids and outbound references.
 *
 * Parallel references are meaningful and preserved. Storage details are hidden
 * behind the object-oriented API so algorithms do not depend on CSR offsets or
 * packed primitive values.
 */
class ObjectGraph internal constructor(
    val classNames: List<ClassName>,
    @PublishedApi internal val classIndices: CharArray,
    @PublishedApi internal val shallowSizes: CompactULongArray,
    @PublishedApi internal val references: ReferencesList,
    /** Object count of the source heap, or [size] when there is no larger source. */
    val sourceObjectCount: Long,
) {
    val size: Int
        get() = shallowSizes.size

    val referenceCount: Int
        get() = references.referenceCount

    init {
        require(classNames.size <= MAX_CLASS_COUNT) {
            "class table exceeds $MAX_CLASS_COUNT entries"
        }
        require(classIndices.size == size) {
            "class index count does not match object count"
        }
    }

    fun className(objectId: Int): ClassName = classNames[classIndices[objectId].code]

    internal fun classIndex(objectId: Int): Int = classIndices[objectId].code

    fun shallowSize(objectId: Int): ULong = shallowSizes[objectId]

    fun referenceCount(objectId: Int): Int = references[objectId].size

    fun referenceAt(objectId: Int, referenceIndex: Int): Int =
        references.getReference(references[objectId], referenceIndex)

    inline fun forEachReference(objectId: Int, action: (targetObjectId: Int) -> Unit) {
        references.forEachReference(references[objectId], action)
    }

    fun instancesOf(className: ClassName): IntArray {
        val requestedClassIndex = classNames.indexOf(className)
        if (requestedClassIndex == -1) return IntArray(0)

        var instanceCount = 0
        for (classIndex in classIndices) {
            if (classIndex.code == requestedClassIndex) instanceCount++
        }

        val instances = IntArray(instanceCount)
        var outputIndex = 0
        for (objectId in classIndices.indices) {
            if (classIndices[objectId].code == requestedClassIndex) {
                instances[outputIndex++] = objectId
            }
        }
        return instances
    }

    class Builder(
        private val sourceObjectCount: Long? = null,
        expectedObjectCount: Int = 0,
        expectedReferenceCount: Int = 0,
    ) {
        init {
            require(expectedObjectCount >= 0) { "negative expected object count" }
            require(expectedReferenceCount >= 0) { "negative expected reference count" }
        }

        private val classNames = ArrayList<ClassName>()
        private val classIndicesByName = HashMap<ClassName, Int>()
        private var objectClasses = CharArray(expectedObjectCount)
        private var objectCount = 0
        private val shallowSizes = CompactULongArray(expectedObjectCount)
        private val references = ReferencesList(expectedObjectCount, expectedReferenceCount)

        /** Registers a class once and returns its stable class-table index. */
        fun classIndex(className: ClassName): Int = classIndicesByName.getOrPut(className) {
            check(classNames.size < MAX_CLASS_COUNT) {
                "class table exceeds $MAX_CLASS_COUNT entries"
            }
            classNames.add(className)
            classNames.size - 1
        }

        fun addObject(
            className: ClassName,
            shallowSize: ULong,
            references: IntArray = IntArray(0),
        ): Int = addObject(classIndex(className), shallowSize, references)

        /** Hot-path insertion for inputs that already resolved the class-table index. */
        fun addObject(
            classIndex: Int,
            shallowSize: ULong,
            references: IntArray = IntArray(0),
        ): Int {
            require(classIndex in classNames.indices) { "unregistered class index $classIndex" }
            ensureObjectCapacity()

            objectClasses[objectCount] = classIndex.toChar()
            shallowSizes.add(shallowSize)
            this.references.add(references)
            return objectCount++
        }

        private fun ensureObjectCapacity() {
            if (objectCount < objectClasses.size) return
            val grownCapacity = (objectClasses.size * 2).coerceAtLeast(objectCount + 1)
            objectClasses = objectClasses.copyOf(grownCapacity)
        }

        fun build(): ObjectGraph {
            references.markImmutable()
            shallowSizes.markImmutable()
            return ObjectGraph(
                classNames = Collections.unmodifiableList(classNames.toList()),
                classIndices = if (objectClasses.size == objectCount) {
                    objectClasses
                } else {
                    objectClasses.copyOf(objectCount)
                },
                shallowSizes = shallowSizes,
                references = references,
                sourceObjectCount = sourceObjectCount ?: objectCount.toLong(),
            )
        }
    }

    companion object {
        private const val MAX_CLASS_COUNT = 65_535

        /** Direct construction from per-object arrays for tests and small synthetic inputs. */
        fun of(
            classes: Array<ClassName>,
            shallow: ULongArray,
            refs: Array<IntArray>,
        ): ObjectGraph {
            val builder = Builder(expectedObjectCount = classes.size)
            for (objectId in classes.indices) {
                builder.addObject(classes[objectId], shallow[objectId], refs[objectId])
            }
            return builder.build()
        }
    }
}
