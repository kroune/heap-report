package shapes.input.graph

import shapes.graph.ObjectGraph
import java.nio.file.Path
import kotlin.io.path.writeText

class TextObjectGraphWriter(private val path: Path) {
    fun write(graph: ObjectGraph) {
        val text = StringBuilder()
        for (objectId in 0 until graph.size) {
            text.append(objectId)
                .append(' ')
                .append(graph.className(objectId).name)
                .append(' ')
                .append(graph.shallowSize(objectId))

            if (graph.referenceCount(objectId) > 0) {
                text.append(' ')
                var firstReference = true
                graph.forEachReference(objectId) { targetId ->
                    if (!firstReference) text.append(',')
                    firstReference = false
                    text.append(targetId)
                }
            }
            text.append('\n')
        }
        path.writeText(text.toString())
    }
}
