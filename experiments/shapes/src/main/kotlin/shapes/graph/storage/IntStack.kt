package shapes.graph.storage

/** Primitive growable stack used by traversals without boxed object ids. */
internal class IntStack(initialCapacity: Int = 1 shl 16) {
    var array = IntArray(initialCapacity)
        private set

    var size = 0
        private set

    fun push(value: Int) {
        if (size == array.size) array = array.copyOf(array.size * 2)
        array[size++] = value
    }

    fun pop(): Int = array[--size]

    operator fun get(index: Int): Int = array[index]

    fun isEmpty(): Boolean = size == 0

    fun release() {
        array = IntArray(0)
        size = 0
    }
}
