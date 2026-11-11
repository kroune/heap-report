package shapes.input.heap

import shapes.graph.ClassName

/**
 * Random-access object data required to select and extract a graph from a heap.
 * Source object ids are dense in `0 until objectCount`.
 */
interface HeapObjectSource : AutoCloseable {
    val objectCount: Int

    fun classId(objectId: Int): Int
    fun className(objectId: Int): ClassName
    fun shallowSize(objectId: Int): ULong
    fun indexedReferences(objectId: Int): IntArray
    fun namedReferences(objectId: Int): List<NamedReference>
    fun isClassObject(objectId: Int): Boolean

    /** Exact name matches, or the complete subtype hierarchy when requested. */
    fun classesNamed(name: ClassName, includeSubclasses: Boolean = false): List<HeapClassDescription>

    /** Source ids in the input's retained-set semantics. */
    fun retainedSet(rootIds: IntArray): IntArray

    override fun close()
}

data class HeapClassDescription(
    val classObjectId: Int,
    val name: ClassName,
    val instanceIds: IntArray,
)

data class NamedReference(
    val fieldName: String,
    val targetObjectId: Int,
)
