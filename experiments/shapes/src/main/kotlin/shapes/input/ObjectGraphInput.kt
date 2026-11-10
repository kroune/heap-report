package shapes.input

import shapes.ObjectGraph

/** A source that materializes the analyzer's input model. */
fun interface ObjectGraphInput {
    fun read(): ObjectGraph
}
