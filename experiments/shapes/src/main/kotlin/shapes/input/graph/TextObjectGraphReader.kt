package shapes.input.graph

import shapes.graph.ClassName
import shapes.graph.ObjectGraph
import java.nio.file.Path
import java.util.BitSet
import kotlin.io.path.readLines

/**
 * Text format: one `<id> <className> <shallowBytes> [refId,refId,...]` row per
 * object. Empty lines and `#` comments are ignored; ids must be dense.
 */
class TextObjectGraphReader(private val path: Path) : ObjectGraphReader {
    override fun read(): ObjectGraph {
        val rows = readRows()
        val rowById = orderByDenseObjectId(rows)
        val builder = ObjectGraph.Builder(expectedObjectCount = rows.size)
        for (objectId in rowById.indices) {
            val row = checkNotNull(rowById[objectId])
            builder.addObject(row.className, row.shallowSize, row.references)
        }
        return builder.build()
    }

    private fun readRows(): List<ObjectRow> {
        val rows = ArrayList<ObjectRow>()
        path.readLines().forEachIndexed { lineIndex, rawLine ->
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("#")) return@forEachIndexed

            val columns = line.split(Regex("\\s+"))
            require(columns.size >= 3) {
                "line ${lineIndex + 1}: expected '<id> <class> <shallow> [refs]'"
            }
            val references = if (columns.size >= 4) {
                columns[3].split(',').map(String::toInt).toIntArray()
            } else {
                IntArray(0)
            }
            rows += ObjectRow(
                objectId = columns[0].toInt(),
                className = ClassName(columns[1]),
                shallowSize = columns[2].toULong(),
                references = references,
            )
        }
        return rows
    }

    private fun orderByDenseObjectId(rows: List<ObjectRow>): Array<ObjectRow?> {
        val seen = BitSet(rows.size)
        val rowById = arrayOfNulls<ObjectRow>(rows.size)
        for (row in rows) {
            require(row.objectId in rows.indices) {
                "id ${row.objectId} not in dense range 0..${rows.size - 1}"
            }
            require(!seen.get(row.objectId)) { "duplicate id ${row.objectId}" }
            seen.set(row.objectId)
            rowById[row.objectId] = row
        }
        return rowById
    }

    private data class ObjectRow(
        val objectId: Int,
        val className: ClassName,
        val shallowSize: ULong,
        val references: IntArray,
    )
}
