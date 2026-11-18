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
    fun objectAddress(objectId: Int): Long
    fun resolveObjectAddress(objectAddress: Long): Int
    fun namedReferences(objectId: Int): List<NamedReference>
    fun isClassObject(objectId: Int): Boolean

    /** MAT removes GC-unreachable objects while indexing; direct sources apply the same default. */
    fun isObjectLive(objectId: Int): Boolean = true

    /** Exact name matches, or the complete subtype hierarchy when requested. */
    fun classesNamed(name: ClassName, includeSubclasses: Boolean = false): List<HeapClassDescription>

    /** Physical candidates for a late authoritative retained-set query. */
    fun classesNamedIncludingUnreachable(
        name: ClassName,
        includeSubclasses: Boolean = false,
    ): List<HeapClassDescription> = classesNamed(name, includeSubclasses)

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
    /** A pre-resolved id, or -1 when resolution should be deferred until after projection. */
    val targetObjectId: Int,
    /** HPROF identity used by field-aware projection; dense ids are needed only at graph boundaries. */
    val targetObjectAddress: Long = targetObjectId.toLong(),
)

internal fun HeapObjectSource.denseTargetId(reference: NamedReference): Int =
    if (reference.targetObjectId >= 0) {
        reference.targetObjectId
    } else {
        resolveObjectAddress(reference.targetObjectAddress)
    }
