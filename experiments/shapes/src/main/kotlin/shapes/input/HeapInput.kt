package shapes.input

/**
 * Random-access object data needed by graph extraction.
 *
 * Implementations adapt an input source; they do not select scopes, project
 * references, or build graphs. Source object ids must be dense in
 * `0 until objectCount`. Keeping this interface free of MAT types makes the
 * extraction pipeline reusable for other heap formats and synthetic inputs.
 */
interface HeapInput : AutoCloseable {
    val objectCount: Int

    fun classId(objectId: Int): Int
    fun className(objectId: Int): String
    fun shallowSize(objectId: Int): Long
    fun indexedReferences(objectId: Int): IntArray
    fun namedReferences(objectId: Int): List<Pair<String, Int>>
    fun isClassObject(objectId: Int): Boolean

    /** Exact name matches, or the complete subtype hierarchy when requested. */
    fun classesNamed(name: String, includeSubclasses: Boolean = false): List<HeapClass>

    /** Source ids in the input's retained-set semantics. */
    fun retainedSet(rootIds: IntArray): IntArray

    override fun close()
}

data class HeapClass(
    val objectId: Int,
    val name: String,
    val instanceIds: IntArray,
)
