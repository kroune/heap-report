package shapes.extraction

import shapes.graph.ClassName
import shapes.graph.storage.IntStack
import shapes.input.heap.HeapObjectSource
import java.util.BitSet

internal class HeapObjectSelection internal constructor(
    internal val objectIds: BitSet,
    val rootIds: IntArray,
    val sourceRetainedObjectCount: Int = 0,
    val filteredOutObjectCount: Long = 0,
    val filteredOutShallowSize: Long = 0,
) {
    val objectCount: Int
        get() = objectIds.cardinality()
}

/** Selects source-heap objects according to the requested extraction scope. */
internal class HeapScopeSelector(
    private val heap: HeapObjectSource,
    private val referenceFilter: HeapReferenceFilter,
    private val classObjectExclusion: ClassObjectExclusion,
    private val progress: (String) -> Unit,
) {
    fun select(scope: HeapExtractionScope, rootIds: IntArray): HeapObjectSelection = when (scope) {
        is HeapExtractionScope.ReachableFromClass -> selectReachable(rootIds)
        is HeapExtractionScope.RetainedByClass -> selectRetained(rootIds)
        HeapExtractionScope.WholeHeap -> selectWholeHeap(rootIds)
    }

    private fun selectReachable(rootIds: IntArray): HeapObjectSelection {
        val selected = BitSet(heap.objectCount)
        val queue = IntStack()
        for (rootId in rootIds) {
            if (heap.isObjectLive(rootId) && !selected.get(rootId) &&
                !classObjectExclusion.isExcluded(rootId)
            ) {
                selected.set(rootId)
                queue.push(rootId)
            }
        }

        val startedAt = System.nanoTime()
        var queueHead = 0
        var nextProgressSize = 1_000_000
        while (queueHead < queue.size) {
            val sourceId = queue[queueHead++]
            for (targetId in referenceFilter.references(sourceId)) {
                if (targetId >= 0 && heap.isObjectLive(targetId) && !selected.get(targetId) &&
                    !classObjectExclusion.isExcluded(targetId)
                ) {
                    selected.set(targetId)
                    queue.push(targetId)
                }
            }
            if (queue.size >= nextProgressSize) {
                val elapsedSeconds = (System.nanoTime() - startedAt) / 1_000_000_000.0
                progress("  BFS: ${queue.size} objects, ${"%.0f".format(queue.size / elapsedSeconds)}/s")
                nextProgressSize += 1_000_000
            }
        }
        queue.release()
        return HeapObjectSelection(selected, rootIds)
    }

    private fun selectRetained(rootIds: IntArray): HeapObjectSelection {
        val retainedIds = heap.retainedSet(rootIds)
        val retainedSelection = BitSet(heap.objectCount)
        for (objectId in retainedIds) {
            if (!classObjectExclusion.isExcluded(objectId)) retainedSelection.set(objectId)
        }
        progress(
            "retained: ${retainedIds.size} input objects, " +
                "${retainedSelection.cardinality()} non-class objects"
        )

        val retainedRootIds = rootIds.filter { retainedSelection.get(it) }.toIntArray()
        return restrictRetainedSelectionToFilteredReferences(
            retainedSelection,
            retainedRootIds,
            retainedIds.size,
        )
    }

    private fun restrictRetainedSelectionToFilteredReferences(
        retainedSelection: BitSet,
        rootIds: IntArray,
        sourceRetainedObjectCount: Int,
    ): HeapObjectSelection {
        val stronglyReachable = BitSet(heap.objectCount)
        val pending = IntStack()
        for (rootId in rootIds) {
            if (retainedSelection.get(rootId)) {
                stronglyReachable.set(rootId)
                pending.push(rootId)
            }
        }

        while (!pending.isEmpty()) {
            val sourceId = pending.pop()
            for (targetId in referenceFilter.references(sourceId)) {
                if (targetId >= 0 && retainedSelection.get(targetId) &&
                    !stronglyReachable.get(targetId)
                ) {
                    stronglyReachable.set(targetId)
                    pending.push(targetId)
                }
            }
        }
        pending.release()

        var filteredOutObjectCount = 0L
        var filteredOutShallowSize = 0L
        var objectId = retainedSelection.nextSetBit(0)
        while (objectId >= 0) {
            if (!stronglyReachable.get(objectId)) {
                filteredOutObjectCount++
                filteredOutShallowSize += heap.shallowSize(objectId).toLong()
            }
            objectId = retainedSelection.nextSetBit(objectId + 1)
        }
        progress(
            "scope filter: $filteredOutObjectCount objects " +
                "($filteredOutShallowSize shallow bytes) dropped — " +
                "unreachable over filtered source references"
        )
        return HeapObjectSelection(
            stronglyReachable,
            rootIds,
            sourceRetainedObjectCount = sourceRetainedObjectCount,
            filteredOutObjectCount = filteredOutObjectCount,
            filteredOutShallowSize = filteredOutShallowSize,
        )
    }

    private fun selectWholeHeap(rootIds: IntArray): HeapObjectSelection {
        val selected = BitSet(heap.objectCount)
        for (objectId in 0 until heap.objectCount) {
            if (heap.isObjectLive(objectId) && !classObjectExclusion.isExcluded(objectId)) {
                selected.set(objectId)
            }
        }
        progress("heap: ${selected.cardinality()} non-class objects")
        return HeapObjectSelection(selected, rootIds)
    }
}

/** Excludes class objects and their statics from program object structure. */
internal class ClassObjectExclusion private constructor(
    private val heap: HeapObjectSource,
    private val classObjectsOutsideClassTable: IntArray,
) {
    fun isExcluded(objectId: Int): Boolean =
        heap.isClassObject(objectId) || objectId in classObjectsOutsideClassTable

    companion object {
        fun create(heap: HeapObjectSource, progress: (String) -> Unit): ClassObjectExclusion {
            var classObjectsOutsideClassTable = IntArray(0)
            for (heapClass in heap.classesNamed(ClassName("java.lang.Class"))) {
                for (objectId in heapClass.instanceIds) {
                    if (!heap.isClassObject(objectId)) classObjectsOutsideClassTable += objectId
                }
            }
            if (classObjectsOutsideClassTable.isNotEmpty()) {
                progress(
                    "class objects outside the class table: " +
                        "${classObjectsOutsideClassTable.size} — also filtered"
                )
            }
            return ClassObjectExclusion(heap, classObjectsOutsideClassTable)
        }
    }
}

internal object ClassInstanceRootsResolver {
    fun resolve(
        scope: HeapExtractionScope,
        heap: HeapObjectSource,
        progress: (String) -> Unit,
    ): IntArray {
        if (scope !is HeapExtractionScope.ClassScope) return IntArray(0)

        val matchingClasses = if (scope is HeapExtractionScope.RetainedByClass) {
            heap.classesNamedIncludingUnreachable(scope.className)
        } else {
            heap.classesNamed(scope.className)
        }
        require(matchingClasses.isNotEmpty()) { "class not found: ${scope.className}" }
        val roots = matchingClasses.flatMap { it.instanceIds.asIterable() }.distinct().toIntArray()
        progress(
            "class ${scope.className.name}: ${matchingClasses.size} class object(s), " +
                "${roots.size} instances"
        )
        return roots
    }
}
