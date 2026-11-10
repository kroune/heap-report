package shapes.extraction

import shapes.ObjectGraph
import shapes.input.HeapInput
import shapes.models.ClassName
import java.lang.Long.bitCount
import java.util.BitSet
import java.util.HashSet

/**
 * Selects a graph from [HeapInput] and materializes it directly as [ObjectGraph].
 *
 * Scope selection is necessarily a separate phase: retained/reachable membership
 * must be known before dense target ids can be assigned. Once selected, classes,
 * shallow sizes, references, map projection, and optional boundary stubs are all
 * built in ONE pass over the selected objects. There is no GRPH intermediate and
 * no class-table prepass.
 */
class GraphExtractor(
    private val input: HeapInput,
    private val filterReferences: Boolean = true,
    private val fieldRules: Map<ClassName, Set<String>> = emptyMap(),
    private val boundaryStubs: Boolean = false,
    private val progress: (String) -> Unit = {},
) {
    fun extract(extractionMode: ExtractionMode): ExtractionResult {
        require(!boundaryStubs || extractionMode is ExtractionMode.PartialExtraction.Retained) {
            "boundary stubs only make sense for retained scope"
        }
        val time0 = System.nanoTime()
        val rootIds = rootIds(extractionMode)

        val referenceClassIds = input.classesNamed("java.lang.ref.Reference", true)
            .mapTo(HashSet()) { it.objectId }
        val droppedRefFields = if (filterReferences) REFERENCE_MECHANICS_FIELDS else DISCOVERED_ONLY
        progress(
            "java.lang.ref.Reference subtypes: ${referenceClassIds.size} classes — " +
                "filtered fields: $droppedRefFields"
        )

        val concealedFields = HashMap<Int, Set<String>>()
        for ((className, fields) in fieldRules) {
            val matches = input.classesNamed(className.name, true)
            for (match in matches) {
                concealedFields[match.objectId] = concealedFields[match.objectId].orEmpty() + fields
            }
            progress("conceal-field rule: $className + subclasses = ${matches.size} classes, drop fields $fields")
            if (matches.isEmpty()) progress("conceal-field: WARNING $className matches no class in the input")
        }
        val referencePolicy = ReferencePolicy(
            indexedRefs = input::indexedReferences,
            rawNamedRefs = input::namedReferences,
            isReference = { id -> input.classId(id) in referenceClassIds },
            concealedFields = { id -> concealedFields[input.classId(id)] },
            filterReferences = filterReferences,
        )

        // Primitive java.lang.Class instances are absent from MAT's class table.
        // The generic rule is the same: every Class instance is excluded even if
        // the input does not recognize it as a class-table object.
        var classObjectLeaks = IntArray(0)
        for (clazz in input.classesNamed("java.lang.Class")) {
            for (id in clazz.instanceIds) {
                if (!input.isClassObject(id)) classObjectLeaks += id
            }
        }
        if (classObjectLeaks.isNotEmpty()) {
            progress("class objects outside the class table: ${classObjectLeaks.size} — also filtered")
        }
        fun isExcluded(id: Int): Boolean = input.isClassObject(id) || id in classObjectLeaks

        val n = input.objectCount
        var selected = BitSet(n)
        var retainedCount = 0
        when (extractionMode) {
            is ExtractionMode.PartialExtraction.Reachable -> {
                val queue = IntStack()
                for (root in rootIds) {
                    if (!selected.get(root) && !isExcluded(root)) {
                        selected.set(root)
                        queue.push(root)
                    }
                }
                var head = 0
                var progressAt = 1_000_000
                while (head < queue.size) {
                    val source = queue.array[head++]
                    for (target in referencePolicy.refs(source)) {
                        if (target >= 0 && !selected.get(target) && !isExcluded(target)) {
                            selected.set(target)
                            queue.push(target)
                        }
                    }
                    if (queue.size >= progressAt) {
                        val elapsed = (System.nanoTime() - time0) / 1_000_000_000.0
                        progress("  BFS: ${queue.size} objects, ${"%.0f".format(queue.size / elapsed)}/s")
                        progressAt += 1_000_000
                    }
                }
                queue.release()
            }

            is ExtractionMode.PartialExtraction.Retained -> {
                val retained = input.retainedSet(rootIds)
                retainedCount = retained.size
                for (id in retained) {
                    if (!isExcluded(id)) selected.set(id)
                }
                progress("retained: ${retained.size} input objects, ${selected.cardinality()} non-class objects")
            }

            ExtractionMode.WholeHeap -> {
                for (id in 0 until n) {
                    if (!isExcluded(id)) selected.set(id)
                }
                progress("heap: ${selected.cardinality()} non-class objects")
            }
        }

        // MAT's retained set includes objects reachable only through weak/soft
        // mechanics or concealed fields. Restrict it to the filtered source graph.
        var droppedScopeObjects = 0L
        var droppedScopeShallow = 0L
        if (extractionMode is ExtractionMode.PartialExtraction.Retained) {
            val strong = BitSet(n)
            val stack = IntStack()
            for (root in rootIds) {
                if (selected.get(root)) {
                    strong.set(root)
                    stack.push(root)
                }
            }
            while (stack.size > 0) {
                val source = stack.pop()
                for (target in referencePolicy.refs(source)) {
                    if (target >= 0 && selected.get(target) && !strong.get(target)) {
                        strong.set(target)
                        stack.push(target)
                    }
                }
            }
            stack.release()
            var id = selected.nextSetBit(0)
            while (id >= 0) {
                if (!strong.get(id)) {
                    droppedScopeObjects++
                    droppedScopeShallow += input.shallowSize(id)
                }
                id = selected.nextSetBit(id + 1)
            }
            selected = strong
            progress(
                "scope filter: $droppedScopeObjects objects ($droppedScopeShallow shallow bytes) " +
                    "dropped — unreachable over filtered source references"
            )
        }

        val selectedCount = selected.cardinality()
        progress("scope $extractionMode: $selectedCount objects in ${(System.nanoTime() - time0) / 1_000_000} ms")
        if (selectedCount == 0) error("empty $extractionMode set")

        // Dense id = rank in the selected bitmap. Popcount prefixes remap refs
        // without a source-id array or binary search.
        val words = selected.toLongArray()
        val rank = IntArray(words.size + 1)
        for (i in words.indices) {
            rank[i + 1] = rank[i] + bitCount(words[i])
        }
        fun remap(id: Int): Int {
            val word = id ushr 6
            return rank[word] + bitCount(words[word] and ((1L shl (id and 63)) - 1))
        }

        val projection = MapProjection(
            className = input::className,
            indexedRefs = { id -> referencePolicy.refs(id) },
            namedRefs = referencePolicy::namedRefs,
        )
        val builder = ObjectGraph.Builder(
            sourceObjectCount = n.toLong(),
            expectedObjectCount = if (boundaryStubs) 0 else selectedCount,
        )
        val stubByClass = HashMap<Int, Int>() // input class id -> dense synthetic object id
        val stubClassNames = ArrayList<ClassName>()
        var sourceEdges = 0L
        var boundaryEdges = 0L
        var projectedEdges = 0L
        var projectedBoundaryEdges = 0L
        var concealedFieldEdges = 0L
        var shallowTotal = 0L
        var processed = 0

        fun denseStubId(target: Int): Int {
            val targetClass = input.classId(target)
            return stubByClass.getOrPut(targetClass) {
                if (extractionMode is ExtractionMode.PartialExtraction) {
                    check(input.className(target) != extractionMode.className.name) {
                        "a boundary target is an instance of the queried class; roots must be in scope"
                    }
                }
                // Register the class after all real rows, matching the old class-table order.
                stubClassNames.add(ClassName(input.className(target)))
                selectedCount + stubClassNames.size - 1
            }
        }

        var id = selected.nextSetBit(0)
        while (id >= 0) {
            val className = ClassName(projection.structuralClassName(input.className(id)))
            val classIndex = builder.classIndex(className)
            val shallow = input.shallowSize(id)
            shallowTotal += shallow

            val read = referencePolicy.read(id)
            val original = read.refs
            concealedFieldEdges += read.concealedFieldEdges
            for (target in original) {
                if (target < 0 || isExcluded(target)) continue
                if (selected.get(target)) {
                    sourceEdges++
                } else {
                    check(extractionMode is ExtractionMode.PartialExtraction.Retained) {
                        "ref $target of $id escaped the reachable set"
                    }
                    boundaryEdges++
                    if (boundaryStubs) denseStubId(target)
                }
            }

            val projected = projection.refs(id, original)
            var outputCount = 0
            for (target in projected) {
                if (target < 0 || isExcluded(target)) continue
                if (selected.get(target) || boundaryStubs) outputCount++ else projectedBoundaryEdges++
            }
            val refs = IntArray(outputCount)
            var outputIndex = 0
            for (target in projected) {
                if (target < 0 || isExcluded(target)) continue
                val mapped = if (selected.get(target)) {
                    remap(target)
                } else if (boundaryStubs) {
                    denseStubId(target)
                } else {
                    continue
                }
                refs[outputIndex++] = mapped
            }
            builder.add(classIndex, shallow.toULong(), refs)
            projectedEdges += outputCount
            processed++
            if (processed % 5_000_000 == 0) progress("  materialize: $processed/$selectedCount")
            id = selected.nextSetBit(id + 1)
        }
        for (stubClassName in stubClassNames) builder.add(builder.classIndex(stubClassName), 0UL)
        if (stubClassNames.isNotEmpty()) {
            progress("boundary stubs: ${stubClassNames.size} target classes, $boundaryEdges boundary refs")
        }

        val graph = builder.build()
        progress(
            "materialized ${graph.size} objects / ${graph.referenceCount} refs, " +
                "${graph.classNames.size} classes in ${(System.nanoTime() - time0) / 1_000_000} ms total"
        )
        progress("edges: source=$sourceEdges projected=$projectedEdges outside-scope=$boundaryEdges")
        return ExtractionResult(
            graph = graph,
            stats = ExtractionStats(
                extractionMode = extractionMode,
                objects = selectedCount,
                roots = rootIds.size,
                shallowTotal = shallowTotal,
                sourceEdges = sourceEdges,
                scopeBoundaryEdges = boundaryEdges,
                projectedEdges = projectedEdges,
                projectedBoundaryEdges = projectedBoundaryEdges,
                matRetainedObjects = retainedCount,
                referenceFiltered = filterReferences,
                scopeFilteredOutObjects = droppedScopeObjects,
                scopeFilteredOutShallow = droppedScopeShallow,
                concealedFieldEdges = concealedFieldEdges,
                fieldRules = fieldRules.entries.sortedBy { it.key.name }.map { (className, fields) ->
                    ExtractionFieldRule(className.name, fields.sorted())
                },
            ),
        )
    }

    private fun rootIds(mode: ExtractionMode): IntArray {
        if (mode !is ExtractionMode.PartialExtraction) return IntArray(0)
        val classes = input.classesNamed(mode.className.name)
        require(classes.isNotEmpty()) { "class not found: ${mode.className}" }
        val roots = classes.flatMap { it.instanceIds.asIterable() }.distinct().toIntArray()
        progress("class ${mode.className.name}: ${classes.size} class object(s), ${roots.size} instances")
        return roots
    }

    private class IntStack {
        var array = IntArray(1 shl 16)
            private set
        var size = 0
            private set

        fun push(value: Int) {
            if (size == array.size) array = array.copyOf(array.size * 2)
            array[size++] = value
        }

        fun pop(): Int = array[--size]

        fun release() {
            array = IntArray(0)
            size = 0
        }
    }

    private companion object {
        val REFERENCE_MECHANICS_FIELDS = setOf("referent", "queue", "next", "discovered")
        val DISCOVERED_ONLY = setOf("discovered")
    }
}
