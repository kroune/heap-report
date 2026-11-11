package shapes.input.graph

import shapes.graph.ClassName
import shapes.graph.ObjectGraph
import java.nio.file.Path

private const val GRPH_MAGIC_NUMBER = 0x47525048

/** Reader for the GRPH interchange format with dense, remapped object ids. */
class GrphObjectGraphReader(private val path: Path) : ObjectGraphReader {
    override fun read(): ObjectGraph {
        BinaryFileReader(path).use { reader ->
            require(reader.readInt() == GRPH_MAGIC_NUMBER) { "bad GRPH magic in $path" }

            val sourceObjectCount = reader.readLong()
            val classNames = Array(reader.readInt()) {
                ClassName(reader.readBytes(reader.readInt()).toString(Charsets.UTF_8))
            }
            val objectCount = reader.readInt()
            require(objectCount >= 0) { "negative object count in $path" }

            val builder = ObjectGraph.Builder(
                sourceObjectCount = sourceObjectCount,
                expectedObjectCount = objectCount,
            )
            classNames.forEach(builder::classIndex)

            repeat(objectCount) {
                val classIndex = reader.readInt()
                require(classIndex in classNames.indices) {
                    "bad class index $classIndex in $path"
                }
                val shallowSize = reader.readLong().toULong()
                val referenceCount = reader.readInt()
                require(referenceCount >= 0) { "negative reference count in $path" }
                val references = IntArray(referenceCount) {
                    reader.readInt().also { targetId ->
                        require(targetId in 0 until objectCount) {
                            "bad reference $targetId in $path"
                        }
                    }
                }
                builder.addObject(classIndex, shallowSize, references)
            }
            return builder.build()
        }
    }
}
