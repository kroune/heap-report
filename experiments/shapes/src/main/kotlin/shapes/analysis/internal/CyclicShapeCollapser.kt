package shapes.analysis.internal

import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap
import it.unimi.dsi.fastutil.ints.Int2LongOpenHashMap
import it.unimi.dsi.fastutil.ints.IntOpenHashSet
import shapes.analysis.ShapeDigest
import shapes.analysis.SccMemberConnection
import shapes.analysis.computeShapeDigest
import shapes.graph.AnalysisGraph
import shapes.relaxation.ShapeRelaxationPolicy
import shapes.relaxation.ShapeRelaxationStats
import shapes.relaxation.StructuralRelaxation

/** Class cap for the artifact payload of lazy shapes too large to list fully. */
private const val SUMMARY_CLASS_LIMIT = 50

/** Lazy shapes up to this many members keep a full per-member payload. */
private const val FULL_PAYLOAD_MEMBER_CAP = 1 shl 20

/**
 * Signature-colliding components are adjudicated by WL refinement up to this
 * size; larger pairs stay distinct shapes (conservative split, logged).
 */
private const val COMPARE_MEMBER_CAP = 1 shl 21

/**
 * Builds and interns canonical shapes for strongly connected components.
 *
 * Two strategies share one interned shape space:
 *
 * - [collapse] — the exact path for small components: WL color refinement
 *   produces per-member canonical keys and interning is full key equality.
 * - [collapseLazy] — for components above `LAZY_WL_THRESHOLD`: one streaming
 *   pass computes an isomorphism-invariant signature, and the expensive WL
 *   canonicalization runs only when two components collide on it.
 */
internal class CyclicShapeCollapser(
    private val graph: AnalysisGraph,
    private val relaxationPolicy: ShapeRelaxationPolicy,
    private val relaxationStats: ShapeRelaxationStats,
    private val shapes: ArrayList<CanonicalShape>,
    private val shapeOfComponent: IntArray,
    private val components: StronglyConnectedComponents,
    private val internedShapes: MutableMap<SccStructuralKey, CyclicShape>,
    private val edgeComparator: Comparator<ChildShapeReference>,
    private val leafShapeInternalIds: IntArray,
) {
    // ------------------------------------------------------------------
    // Shared helpers
    // ------------------------------------------------------------------

    /** Shape id of an external target: a folded leaf class or a collapsed component. */
    private fun childShapeIdOf(targetId: Int): Int = if (targetId < 0) {
        leafShapeInternalIds[-targetId - 1]
    } else {
        shapeOfComponent[components.assignment.componentOf(targetId)]
    }

    private fun memberIdsOf(componentId: Int, memberCount: Int): IntArray {
        val memberIds = IntArray(memberCount)
        var fill = 0
        components.members.forEachMember(componentId) { memberIds[fill++] = it }
        return memberIds
    }

    private fun localIndexOf(memberIds: IntArray): Int2IntOpenHashMap {
        val localIndexByObjectId = Int2IntOpenHashMap(memberIds.size * 2).apply {
            defaultReturnValue(-1)
        }
        for (memberIndex in memberIds.indices) {
            localIndexByObjectId[memberIds[memberIndex]] = memberIndex
        }
        return localIndexByObjectId
    }

    private val childEdgeByDigest = Comparator<CanonicalShapeEdge> { first, second ->
        first.child.digest.compareTo(second.child.digest)
    }

    // ------------------------------------------------------------------
    // Exact path: WL refinement + full-key interning
    // ------------------------------------------------------------------

    fun collapse(memberIds: IntArray): CyclicShape {
        val form = buildCanonicalForm(memberIds, relaxationStats)
        return internedShapes.getOrPut(SccStructuralKey(form.canonicalKeys)) {
            createCanonicalShape(memberIds.size, form)
        }
    }

    private class CanonicalForm(
        val canonicalKeys: List<SccMemberStructuralKey>,
        val orderedMemberIndices: IntArray,
        val sourceIndexByCanonicalIndex: IntArray,
        val keptKeyByPreQuotientIndex: IntArray,
        val internalEdges: Array<MutableList<Int>>,
        val setChildren: Boolean,
        val uniformExternalEdges: Boolean,
    )

    /** WL refinement + canonical member keys; [stats] receives relaxation counters. */
    private fun buildCanonicalForm(
        memberIds: IntArray,
        stats: ShapeRelaxationStats,
    ): CanonicalForm {
        val memberCount = memberIds.size
        val localIndexByObjectId = localIndexOf(memberIds)

        val relaxations = Array(memberCount) { memberIndex ->
            relaxationPolicy.relaxationOf(graph.classIndex(memberIds[memberIndex]))
        }
        val setChildren = relaxations.any { it == StructuralRelaxation.SET_CHILDREN }
        val externalEdges = Array(memberCount) { mutableListOf<ChildShapeReference>() }
        val internalEdges = Array(memberCount) { mutableListOf<Int>() }

        for (memberIndex in 0 until memberCount) {
            collectMemberEdges(
                objectId = memberIds[memberIndex],
                memberIndex = memberIndex,
                relaxation = relaxations[memberIndex],
                setChildren = setChildren,
                localIndexByObjectId = localIndexByObjectId,
                externalEdges = externalEdges,
                internalNeighbors = internalEdges,
                stats = stats,
            )
        }

        val classNames = Array(memberCount) { graph.className(memberIds[it]) }
        val refinementResult = SccColorRefinement(
            classNames,
            externalEdges,
            internalEdges,
            setChildren,
            edgeComparator,
        ).refine()
        val colorRanks = refinementResult.colorRanks
        val orderedMemberIndices = refinementResult.orderedMemberIndices

        val keysBeforeQuotient = orderedMemberIndices.map { memberIndex ->
            val internalColorRanks = internalEdges[memberIndex]
                .map { colorRanks[it] }
                .let { if (setChildren) it.distinct() else it }
                .sorted()
            SccMemberStructuralKey(
                classNames[memberIndex],
                internalColorRanks,
                externalEdges[memberIndex],
            )
        }

        val keptKeyByPreQuotientIndex = IntArray(keysBeforeQuotient.size) { it }
        var sourceIndexByCanonicalIndex = IntArray(keysBeforeQuotient.size) { it }
        val canonicalKeys = if (!setChildren) {
            keysBeforeQuotient
        } else {
            applySetQuotient(
                keysBeforeQuotient,
                orderedMemberIndices,
                internalEdges,
                keptKeyByPreQuotientIndex,
                stats,
            ).also { quotient ->
                sourceIndexByCanonicalIndex = quotient.sourceIndices
                stats.setChildrenConcealedObjects +=
                    (keysBeforeQuotient.size - quotient.keys.size).toLong()
            }.keys
        }

        val uniformExternalEdges = canonicalKeys.all {
            it.externalEdges == canonicalKeys[0].externalEdges
        }
        return CanonicalForm(
            canonicalKeys,
            orderedMemberIndices,
            sourceIndexByCanonicalIndex,
            keptKeyByPreQuotientIndex,
            internalEdges,
            setChildren,
            uniformExternalEdges,
        )
    }

    private fun collectMemberEdges(
        objectId: Int,
        memberIndex: Int,
        relaxation: StructuralRelaxation,
        setChildren: Boolean,
        localIndexByObjectId: Int2IntOpenHashMap,
        externalEdges: Array<MutableList<ChildShapeReference>>,
        internalNeighbors: Array<MutableList<Int>>,
        stats: ShapeRelaxationStats,
    ) {
        if (relaxation != StructuralRelaxation.EXACT) stats.relaxedObjects++

        if (relaxation == StructuralRelaxation.CLASS_ONLY) {
            var hiddenExternalReferences = 0
            for (referenceIndex in 0 until graph.referenceCount(objectId)) {
                val targetId = graph.referenceAt(objectId, referenceIndex)
                val localTargetIndex = if (targetId < 0) -1 else localIndexByObjectId.get(targetId)
                if (localTargetIndex >= 0) {
                    internalNeighbors[memberIndex].add(localTargetIndex)
                } else {
                    hiddenExternalReferences++
                }
            }
            stats.classOnlyHiddenEdges += hiddenExternalReferences
            externalEdges[memberIndex] = mutableListOf()
            return
        }

        val multiplicityByChildShape = LinkedHashMap<Int, Int>()
        for (referenceIndex in 0 until graph.referenceCount(objectId)) {
            val targetId = graph.referenceAt(objectId, referenceIndex)
            val localTargetIndex = if (targetId < 0) -1 else localIndexByObjectId.get(targetId)
            if (localTargetIndex >= 0) {
                internalNeighbors[memberIndex].add(localTargetIndex)
            } else {
                multiplicityByChildShape.merge(childShapeIdOf(targetId), 1, Int::plus)
            }
        }
        externalEdges[memberIndex] = multiplicityByChildShape.map { (childShapeId, multiplicity) ->
            if (setChildren) stats.setChildrenHiddenEdges += multiplicity - 1
            ChildShapeReference(childShapeId, if (setChildren) 1 else multiplicity)
        }.sortedWith(edgeComparator).toMutableList()
    }

    private class QuotientResult(
        val keys: List<SccMemberStructuralKey>,
        val sourceIndices: IntArray,
    )

    private fun applySetQuotient(
        keys: List<SccMemberStructuralKey>,
        orderedMemberIndices: IntArray,
        internalNeighbors: Array<MutableList<Int>>,
        keptKeyByPreQuotientIndex: IntArray,
        stats: ShapeRelaxationStats,
    ): QuotientResult {
        val quotientIndexByKey = HashMap<SccMemberStructuralKey, Int>()
        val quotientKeys = ArrayList<SccMemberStructuralKey>(keys.size)
        val sourceIndices = ArrayList<Int>(keys.size)

        for (keyIndex in keys.indices) {
            val key = keys[keyIndex]
            val existingQuotientIndex = quotientIndexByKey[key]
            if (existingQuotientIndex == null) {
                quotientIndexByKey[key] = quotientKeys.size
                keptKeyByPreQuotientIndex[keyIndex] = quotientKeys.size
                sourceIndices += keyIndex
                quotientKeys += key
                stats.setChildrenHiddenEdges +=
                    internalNeighbors[orderedMemberIndices[keyIndex]].size -
                        key.internalColorRanks.size
            } else {
                keptKeyByPreQuotientIndex[keyIndex] = existingQuotientIndex
                stats.setChildrenHiddenEdges +=
                    internalNeighbors[orderedMemberIndices[keyIndex]].size + key.externalEdges.size
            }
        }
        return QuotientResult(quotientKeys, sourceIndices.toIntArray())
    }

    private fun createCanonicalShape(
        originalMemberCount: Int,
        form: CanonicalForm,
    ): CyclicShape {
        val preQuotientIndexByOriginalIndex = IntArray(originalMemberCount)
        for (preQuotientIndex in form.orderedMemberIndices.indices) {
            preQuotientIndexByOriginalIndex[form.orderedMemberIndices[preQuotientIndex]] =
                preQuotientIndex
        }

        val memberPayload = form.canonicalKeys.mapIndexed { canonicalIndex, key ->
            val sourceIndex =
                form.orderedMemberIndices[form.sourceIndexByCanonicalIndex[canonicalIndex]]
            val targets = form.internalEdges[sourceIndex]
                .map { form.keptKeyByPreQuotientIndex[preQuotientIndexByOriginalIndex[it]] }
                .sorted()
            CanonicalSccMember(
                key.className,
                key.internalColorRanks.size,
                foldConnections(targets, form.setChildren),
                key.externalEdges.map { edge ->
                    CanonicalShapeEdge(shapes[edge.childShapeId], edge.multiplicity)
                },
            )
        }

        val totalExternalEdges = if (form.uniformExternalEdges) {
            emptyList()
        } else {
            totalExternalEdges(memberPayload)
        }
        val packedChildEdges = if (form.uniformExternalEdges) {
            memberPayload.first().externalEdges.map { edge ->
                ChildShapeEdge(edge.child.internalId, edge.multiplicity).packed
            }.toLongArray()
        } else {
            LongArray(0)
        }
        return CyclicShape(
            internalId = shapes.size,
            digest = computeSccDigest(form.canonicalKeys),
            packedChildEdges = packedChildEdges,
            totalExternalEdges = totalExternalEdges,
            members = memberPayload,
            quotient = form.setChildren,
        ).also(shapes::add)
    }

    private fun computeSccDigest(
        canonicalKeys: List<SccMemberStructuralKey>,
    ): ShapeDigest = computeShapeDigest {
        writeByte(3)
        writeInt(canonicalKeys.size)
        for (member in canonicalKeys) {
            writeUtf8(member.className.name)
            writeInt(member.internalColorRanks.size)
            for (rank in member.internalColorRanks) writeInt(rank)
            writeInt(member.externalEdges.size)
            for (edge in member.externalEdges) {
                writeDigest(shapes[edge.childShapeId].digest)
                writeInt(edge.multiplicity)
            }
        }
    }

    // ------------------------------------------------------------------
    // Lazy path: invariant signature first, WL only on collision
    // ------------------------------------------------------------------

    /** Isomorphism-invariant quick key for large components. */
    private data class LazySignature(
        val memberCount: Int,
        val internalEdgeCount: Long,
        /** Sorted packed (classIndex << 32 | count). */
        val classHistogram: List<Long>,
        /** Sorted flat (childShapeId, total) pairs. */
        val externalTotals: List<Long>,
    )

    private class LazyEntry(
        val shape: CyclicShape,
        val componentId: Int,
        /** Exact WL identity, computed on first collision and then reused. */
        var canonicalDigest: ShapeDigest?,
    )

    private val lazyBuckets = HashMap<LazySignature, MutableList<LazyEntry>>()

    /** Aggregates of one streaming pass over a component's members. */
    private class ComponentSummary(
        val memberCount: Int,
        val internalEdges: Long,
        val classHistogram: Int2LongOpenHashMap,
        val externalTotals: Int2LongOpenHashMap,
    ) {
        fun signature() = LazySignature(
            memberCount,
            internalEdges,
            classHistogram.int2LongEntrySet()
                .map { (it.intKey.toLong() shl 32) or it.longValue }
                .sorted(),
            externalTotals.int2LongEntrySet()
                .sortedBy { it.intKey }
                .flatMap { listOf(it.intKey.toLong(), it.longValue) },
        )
    }

    /**
     * Collapses a large component without materializing per-member state
     * unless two components genuinely need to be told apart: a unique
     * [LazySignature] — the common case, including the mega-SCC — interns the
     * shape with no WL refinement at all; a collision materializes both
     * components' exact WL canonical forms and merges only on a match. Pairs
     * beyond [COMPARE_MEMBER_CAP] stay distinct (the conservative direction)
     * with a logged warning.
     */
    fun collapseLazy(componentId: Int, memberCount: Int, progress: (String) -> Unit): CyclicShape {
        val summary = summarizeComponent(componentId, memberCount)

        val bucket = lazyBuckets.getOrPut(summary.signature()) { mutableListOf() }
        var canonicalDigest: ShapeDigest? = null
        if (bucket.isNotEmpty()) {
            if (memberCount <= COMPARE_MEMBER_CAP) {
                progress(
                    "scc-lazy: signature collision at $memberCount members — " +
                        "running WL refinement to adjudicate"
                )
                canonicalDigest = canonicalFormDigest(componentId, memberCount)
                for (entry in bucket) {
                    val entryDigest = entry.canonicalDigest
                        ?: canonicalFormDigest(entry.componentId, memberCount)
                            .also { entry.canonicalDigest = it }
                    if (entryDigest == canonicalDigest) return entry.shape
                }
            } else {
                progress(
                    "scc-lazy: WARNING signature collision at $memberCount members " +
                        "exceeds the WL compare cap — components kept as distinct shapes"
                )
            }
        }

        val shape = createLazyShape(componentId, summary, bucketOrdinal = bucket.size)
        bucket.add(LazyEntry(shape, componentId, canonicalDigest))
        return shape
    }

    /** One streaming pass: class histogram, edge totals, relaxation counters. */
    private fun summarizeComponent(componentId: Int, memberCount: Int): ComponentSummary {
        val classHistogram = Int2LongOpenHashMap()
        val externalTotals = Int2LongOpenHashMap()
        var internalEdges = 0L
        val memberChildScratch = IntOpenHashSet()

        components.members.forEachMember(componentId) { objectId ->
            val classIndex = graph.classIndex(objectId)
            classHistogram.addTo(classIndex, 1L)
            val relaxation = relaxationPolicy.relaxationOf(classIndex)
            if (relaxation != StructuralRelaxation.EXACT) relaxationStats.relaxedObjects++
            val setChildren = relaxation == StructuralRelaxation.SET_CHILDREN
            if (setChildren) memberChildScratch.clear()

            for (referenceIndex in 0 until graph.referenceCount(objectId)) {
                val targetId = graph.referenceAt(objectId, referenceIndex)
                val isInternal = targetId >= 0 &&
                    components.assignment.componentOf(targetId) == componentId
                when {
                    isInternal -> internalEdges++
                    relaxation == StructuralRelaxation.CLASS_ONLY ->
                        relaxationStats.classOnlyHiddenEdges++
                    else -> {
                        val childShapeId = childShapeIdOf(targetId)
                        if (setChildren && !memberChildScratch.add(childShapeId)) {
                            relaxationStats.setChildrenHiddenEdges++
                        } else {
                            externalTotals.addTo(childShapeId, 1L)
                        }
                    }
                }
            }
        }
        return ComponentSummary(memberCount, internalEdges, classHistogram, externalTotals)
    }

    /** Exact canonical identity of one component, for collision adjudication. */
    private fun canonicalFormDigest(componentId: Int, memberCount: Int): ShapeDigest {
        val memberIds = memberIdsOf(componentId, memberCount)
        // Throwaway stats: the streaming signature pass already accounted them.
        val form = buildCanonicalForm(memberIds, ShapeRelaxationStats())
        return computeSccDigest(form.canonicalKeys)
    }

    private fun createLazyShape(
        componentId: Int,
        summary: ComponentSummary,
        bucketOrdinal: Int,
    ): CyclicShape {
        val totalEdges = summary.externalTotals.int2LongEntrySet()
            .map { entry ->
                check(entry.longValue <= Int.MAX_VALUE) { "external multiplicity overflow" }
                CanonicalShapeEdge(shapes[entry.intKey], entry.longValue.toInt())
            }
            .sortedWith(childEdgeByDigest)
        check(summary.internalEdges <= Int.MAX_VALUE) { "internal edge count overflow" }

        val sortedClassIndices = summary.classHistogram.keys.toIntArray().sortedBy {
            graph.classNames[it].name
        }
        val digest = computeShapeDigest {
            writeByte(4)
            writeInt(summary.memberCount)
            writeLong(summary.internalEdges)
            writeInt(sortedClassIndices.size)
            for (classIndex in sortedClassIndices) {
                writeUtf8(graph.classNames[classIndex].name)
                writeLong(summary.classHistogram.get(classIndex))
            }
            for (edge in totalEdges) {
                writeDigest(edge.child.digest)
                writeInt(edge.multiplicity)
            }
            writeInt(bucketOrdinal)
        }

        val memberPayload = if (summary.memberCount <= FULL_PAYLOAD_MEMBER_CAP) {
            buildMemberPayload(componentId, summary.memberCount)
        } else {
            sortedClassIndices
                .sortedByDescending { summary.classHistogram.get(it) }
                .take(SUMMARY_CLASS_LIMIT)
                .map { classIndex ->
                    CanonicalSccMember(
                        graph.classNames[classIndex],
                        internalReferenceCount = 0,
                        internalConnections = emptyList(),
                        externalEdges = emptyList(),
                    )
                }
        }

        return LazySccShape(
            internalId = shapes.size,
            digest = digest,
            totalExternalEdges = totalEdges,
            members = memberPayload,
            memberCount = summary.memberCount,
            totalInternalEdges = summary.internalEdges.toInt(),
            distinctClassCount = sortedClassIndices.size,
        ).also(shapes::add)
    }

    /**
     * Per-member payload for the nested-shapes view, in member order (not WL
     * canonical order — the order is presentational, not part of the key).
     */
    private fun buildMemberPayload(componentId: Int, memberCount: Int): List<CanonicalSccMember> {
        val memberIds = memberIdsOf(componentId, memberCount)
        val localIndexByObjectId = localIndexOf(memberIds)

        return memberIds.map { objectId ->
            val relaxation = relaxationPolicy.relaxationOf(graph.classIndex(objectId))
            val setChildren = relaxation == StructuralRelaxation.SET_CHILDREN
            val internalTargets = ArrayList<Int>()
            val externalCounts = LinkedHashMap<Int, Int>()
            for (referenceIndex in 0 until graph.referenceCount(objectId)) {
                val targetId = graph.referenceAt(objectId, referenceIndex)
                val localTargetIndex = if (targetId < 0) -1 else localIndexByObjectId.get(targetId)
                if (localTargetIndex >= 0) {
                    internalTargets.add(localTargetIndex)
                } else if (relaxation != StructuralRelaxation.CLASS_ONLY) {
                    externalCounts.merge(childShapeIdOf(targetId), 1, Int::plus)
                }
            }
            internalTargets.sort()
            CanonicalSccMember(
                graph.className(objectId),
                internalReferenceCount = internalTargets.size,
                internalConnections = foldConnections(internalTargets, setChildren),
                externalEdges = externalCounts.map { (childShapeId, multiplicity) ->
                    CanonicalShapeEdge(shapes[childShapeId], if (setChildren) 1 else multiplicity)
                }.sortedWith(childEdgeByDigest),
            )
        }
    }

    // ------------------------------------------------------------------
    // Payload helpers
    // ------------------------------------------------------------------

    private fun foldConnections(
        sortedTargets: List<Int>,
        setChildren: Boolean,
    ): List<SccMemberConnection> {
        val connections = ArrayList<SccMemberConnection>(sortedTargets.size)
        var previousTarget = -1
        var multiplicity = 0
        for (target in sortedTargets) {
            if (target == previousTarget) {
                multiplicity++
                continue
            }
            if (previousTarget >= 0) {
                connections += SccMemberConnection(
                    previousTarget,
                    if (setChildren) 1 else multiplicity,
                )
            }
            previousTarget = target
            multiplicity = 1
        }
        if (previousTarget >= 0) {
            connections += SccMemberConnection(
                previousTarget,
                if (setChildren) 1 else multiplicity,
            )
        }
        return connections
    }

    private fun totalExternalEdges(
        members: List<CanonicalSccMember>,
    ): List<CanonicalShapeEdge> {
        val totals = LinkedHashMap<CanonicalShape, Int>()
        for (member in members) {
            for (edge in member.externalEdges) {
                totals.merge(edge.child, edge.multiplicity, Int::plus)
            }
        }
        return totals.map { (child, multiplicity) ->
            CanonicalShapeEdge(child, multiplicity)
        }.sortedWith(childEdgeByDigest)
    }
}
