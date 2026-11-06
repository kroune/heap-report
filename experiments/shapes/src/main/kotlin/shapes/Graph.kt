package shapes

import shapes.models.ClassName
import shapes.models.CompactULongArray
import shapes.models.ReferencesList
import java.nio.file.Path
import java.util.*
import kotlin.io.path.readLines
import kotlin.io.path.writeText

/**
 * Compact object graph used by the analyzer (SHAPES.md "Definitions"):
 * objects + outbound references. Object ids are dense, 0-based.
 *
 * CSR storage (flat refs + offsets, class table + per-object index): at
 * 10M+ objects, per-node IntArray shells cost more than the refs themselves
 * (measured on real heaps — see README). Hot loops use [forEachRef].
 *
 * Text format (one object per line):
 *   # comment
 *   <id> <className> <shallowBytes> [refId,refId,...]
 * Ids must be dense 0..n-1. Repeated refIds are allowed and meaningful
 * (multi-edges, e.g. Object[]{x, x}).
 */
class ObjectGraph(
    val classNames: Array<ClassName>,
    val classIdx: CharArray,       // per object -> classNames index (classCount <= 65535)
    val shallow: CompactULongArray,
    val referencesList: ReferencesList,           // flat outbound refs
    /** object count of the source heap (GRPH from a real dump), else == size. */
    val sourceObjectCount: Long = shallow.size.toLong(),
) {
    val size: Int get() = shallow.size

    fun className(index: Int): ClassName = classNames[classIdx[index].code]

    fun instancesOf(className: ClassName): IntArray {
        val index = classNames.indexOf(className)
        if (index == -1) {
            return IntArray(0)
        }
        var count = 0
        for (i in classIdx.indices) {
            if (classIdx[i].code == index) {
                count++
            }
        }
        val out = IntArray(count)
        var p = 0
        for (i in classIdx.indices) {
            if (classIdx[i].code == index) {
                out[p++] = i
            }
        }
        return out
    }

    fun save(path: Path) {
        val sb = StringBuilder()
        for (i in 0 until size) {
            sb
                .append(i)
                .append(' ')
                .append(className(i).name)
                .append(' ')
                .append(shallow[i])
            if (referencesList[i].size > 0) {
                sb.append(' ')
                var firstElement = true
                referencesList.forEachReference(referencesList[i]) {
                    if (!firstElement) {
                        sb.append(',')
                    }
                    firstElement = false
                    sb.append(it)
                }
            }
            sb.append('\n')
        }
        path.writeText(sb.toString())
    }

    class Builder {

        private val classNames = ArrayList<ClassName>()
        private val shallowSizes = CompactULongArray()
        private val referencesList = ReferencesList()

        fun add(className: ClassName, shallow: ULong, references: IntArray = IntArray(0)): Int {
            classNames.add(className)
            shallowSizes.add(shallow)
            referencesList.add(references)
            return classNames.size - 1
        }

        fun build(): ObjectGraph {
            val names = ArrayList<ClassName>()
            val nameIdx = HashMap<ClassName, Int>()
            val classIdx = CharArray(classNames.size)
            for (i in classNames.indices) {
                classIdx[i] = nameIdx.getOrPut(classNames[i]) {
                    names.add(classNames[i])
                    require(names.size <= 65535) { "class table exceeds 65535 (CharArray classIdx)" }
                    names.size - 1
                }.toChar()
            }
            referencesList.markImmutable()
            shallowSizes.markImmutable()
            return ObjectGraph(
                classNames = names.toTypedArray(),
                classIdx = classIdx,
                shallow = shallowSizes,
                referencesList = referencesList
            )
        }
    }

    companion object {
        /** Direct construction from per-node arrays (tests). */
        fun of(classes: Array<ClassName>, shallow: ULongArray, refs: Array<IntArray>): ObjectGraph {
            val builder = Builder()
            for (index in classes.indices) {
                builder.add(classes[index], shallow[index], refs[index])
            }
            return builder.build()
        }

        /** GRPH binary, written by shapes.mat.MatExtract (dense ids, refs remapped). */
        fun loadGraph(path: Path): ObjectGraph {
            val classNames: Array<ClassName>
            val sourceN: Long
            val n: Int
            val classIdx: CharArray
            val shallowSizes = CompactULongArray()
            val referencesList = ReferencesList()
            FastReader(path).use { reader ->
                require(reader.int() == GRAPH_MAGIC_NUMBER) { "bad GRPH magic in $path" }
                sourceN = reader.long()
                classNames = Array(reader.int()) {
                    ClassName(reader.bytes(reader.int()).toString(Charsets.UTF_8))
                }
                require(classNames.size <= 65535) { "class table exceeds 65535 (CharArray classIdx)" }
                n = reader.int()
                classIdx = CharArray(n)
                for (i in 0 until n) {
                    classIdx[i] = reader.int().toChar()
                    val v = reader.long().toULong()
                    shallowSizes.add(v)


                    val referencesSize = reader.int()
                    val refs = IntArray(referencesSize) {
                        reader.int().also {
                            require(it in 0 until n)
                        }
                    }
                    referencesList.add(refs)
                }
            }
            referencesList.markImmutable()
            shallowSizes.markImmutable()
            return ObjectGraph(classNames, classIdx, shallowSizes, referencesList, sourceN)
        }

        fun load(path: Path): ObjectGraph {
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
            val builder = Builder()
            for (i in rows.indices) {
                val row = rowById[i]!!
                builder.add(row.className, row.shallow, row.refs)
            }
            return builder.build()
        }
    }
}
