package shapes.extraction

import shapes.graph.ClassName
import shapes.input.heap.HeapClassDescription
import shapes.input.heap.HeapObjectSource
import shapes.input.heap.NamedReference
import shapes.relaxation.FieldConcealmentRules

internal class FakeHeapObjectSource(
    private val classes: Array<String>,
    private val shallowSizes: LongArray,
    private val references: Array<IntArray>,
    private val namedReferences: Array<List<NamedReference>> = references.map { row ->
        row.mapIndexed { index, targetId -> NamedReference("f$index", targetId) }
    }.toTypedArray(),
    private val retained: IntArray = IntArray(0),
    private val classObjects: Set<Int> = emptySet(),
) : HeapObjectSource {
    override val objectCount: Int
        get() = classes.size

    val referenceReads = IntArray(classes.size)
    val shallowSizeReads = IntArray(classes.size)
    var namedReferenceReads = 0
        private set

    override fun classId(objectId: Int): Int = classId(classes[objectId])

    override fun className(objectId: Int): ClassName = ClassName(classes[objectId])

    override fun shallowSize(objectId: Int): ULong {
        shallowSizeReads[objectId]++
        return shallowSizes[objectId].toULong()
    }

    override fun indexedReferences(objectId: Int): IntArray {
        referenceReads[objectId]++
        return references[objectId]
    }

    override fun namedReferences(objectId: Int): List<NamedReference> {
        namedReferenceReads++
        return namedReferences[objectId]
    }

    override fun isClassObject(objectId: Int): Boolean = objectId in classObjects

    override fun classesNamed(
        name: ClassName,
        includeSubclasses: Boolean,
    ): List<HeapClassDescription> {
        val matchingInstances = classes.withIndex()
            .filter { it.value == name.name }
            .map { it.index }
        if (matchingInstances.isEmpty()) return emptyList()
        return listOf(
            HeapClassDescription(
                classId(name.name),
                name,
                matchingInstances.toIntArray(),
            )
        )
    }

    override fun retainedSet(rootIds: IntArray): IntArray = retained

    override fun close() = Unit

    private fun classId(className: String): Int = 1000 + className.hashCode()
}

internal fun referenceFilterFor(heap: HeapObjectSource): HeapReferenceFilter =
    HeapReferenceFilter.create(heap, FieldConcealmentRules(), filterReferenceMechanics = true) {}
