package shapes.extraction

import shapes.graph.ClassName
import shapes.graph.ObjectGraph
import shapes.input.heap.HeapObjectSource

/** Allocates one synthetic boundary leaf per target class. */
internal class BoundaryStubRegistry(
    private val heap: HeapObjectSource,
    private val firstStubObjectId: Int,
    private val queriedClassName: ClassName?,
) {
    private val stubIdsByTargetClass = HashMap<Int, Int>()
    private val stubClassNames = ArrayList<ClassName>()

    val count: Int
        get() = stubClassNames.size

    fun denseIdFor(targetObjectId: Int): Int {
        val targetClassId = heap.classId(targetObjectId)
        return stubIdsByTargetClass.getOrPut(targetClassId) {
            check(heap.className(targetObjectId) != queriedClassName) {
                "a boundary target is an instance of the queried class; roots must be in scope"
            }
            stubClassNames.add(heap.className(targetObjectId))
            firstStubObjectId + stubClassNames.size - 1
        }
    }

    fun appendTo(builder: ObjectGraph.Builder) {
        for (className in stubClassNames) {
            builder.addObject(builder.classIndex(className), 0UL)
        }
    }
}
