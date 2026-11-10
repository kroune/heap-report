package shapes.input

import shapes.ObjectGraph
import shapes.models.ClassName
import java.nio.file.Path
import java.util.BitSet
import kotlin.io.path.readLines
import kotlin.io.path.writeText

/**
 * Text format: one `<id> <className> <shallowBytes> [refId,refId,...]` row per
 * object. Empty lines and `#` comments are ignored; ids must be dense.
 */
class TextGraphInput(private val path: Path) : ObjectGraphInput {
    override fun read(): ObjectGraph {
        data class Row(val id: Int, val className: ClassName, val shallow: ULong, val refs: IntArray)

        val rows = ArrayList<Row>()
        path.readLines().forEachIndexed { lineNo, raw ->
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) return@forEachIndexed
            val parts = line.split(Regex("\\s+"))
            require(parts.size >= 3) { "line ${lineNo + 1}: expected '<id> <class> <shallow> [refs]'" }
            val refs = if (parts.size >= 4) {
                parts[3].split(',').map { it.toInt() }.toIntArray()
            } else {
                IntArray(0)
            }
            rows.add(Row(parts[0].toInt(), ClassName(parts[1]), parts[2].toULong(), refs))
        }
        val seen = BitSet(rows.size)
        val rowById = arrayOfNulls<Row>(rows.size)
        for (row in rows) {
            require(row.id in rows.indices) { "id ${row.id} not in dense range 0..${rows.size - 1}" }
            require(!seen.get(row.id)) { "duplicate id ${row.id}" }
            seen.set(row.id)
            rowById[row.id] = row
        }
        val builder = ObjectGraph.Builder()
        for (i in rows.indices) {
            val row = rowById[i]!!
            builder.add(row.className, row.shallow, row.refs)
        }
        return builder.build()
    }
}

object TextGraphOutput {
    fun write(graph: ObjectGraph, path: Path) {
        val sb = StringBuilder()
        for (i in 0 until graph.size) {
            sb.append(i).append(' ').append(graph.className(i).name).append(' ').append(graph.shallow[i])
            val range = graph.referencesList[i]
            if (range.size > 0) {
                sb.append(' ')
                var first = true
                graph.referencesList.forEachReference(range) {
                    if (!first) sb.append(',')
                    first = false
                    sb.append(it)
                }
            }
            sb.append('\n')
        }
        path.writeText(sb.toString())
    }
}
