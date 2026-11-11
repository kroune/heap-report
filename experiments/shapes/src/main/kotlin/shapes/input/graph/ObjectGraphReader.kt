package shapes.input.graph

import shapes.graph.ObjectGraph

/** Reads an already materialized object graph from a serialized representation. */
fun interface ObjectGraphReader {
    fun read(): ObjectGraph
}
