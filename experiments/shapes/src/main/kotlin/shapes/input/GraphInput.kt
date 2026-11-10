package shapes.input

import shapes.ObjectGraph
import shapes.models.ClassName
import shapes.models.CompactULongArray
import shapes.models.ReferencesList
import java.nio.file.Path

private const val GRAPH_MAGIC_NUMBER = 0x47525048

/** Reader for the standalone GRPH interchange format (dense ids, remapped refs). */
class GraphInput(private val path: Path) : ObjectGraphInput {
    override fun read(): ObjectGraph {
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
            require(n >= 0) { "negative object count in $path" }
            classIdx = CharArray(n)
            for (i in 0 until n) {
                val classIndex = reader.int()
                require(classIndex in classNames.indices) { "bad class index $classIndex in $path" }
                classIdx[i] = classIndex.toChar()
                shallowSizes.add(reader.long().toULong())
                val referencesSize = reader.int()
                require(referencesSize >= 0) { "negative reference count in $path" }
                val refs = IntArray(referencesSize) {
                    reader.int().also { require(it in 0 until n) { "bad reference $it in $path" } }
                }
                referencesList.add(refs)
            }
        }
        referencesList.markImmutable()
        shallowSizes.markImmutable()
        return ObjectGraph(classNames, classIdx, shallowSizes, referencesList, sourceN)
    }
}
