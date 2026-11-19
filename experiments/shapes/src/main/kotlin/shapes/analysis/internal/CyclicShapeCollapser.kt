package shapes.analysis.internal

import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap
import it.unimi.dsi.fastutil.ints.Int2LongOpenHashMap
import shapes.analysis.ShapeDigest
import shapes.analysis.SccMemberConnection
import shapes.analysis.computeShapeDigest
import shapes.graph.AnalysisGraph
import shapes.relaxation.ShapeRelaxationPolicy
import shapes.relaxation.ShapeRelaxationStats
import shapes.relaxation.StructuralRelaxation

/** Class cap for the summarized giant-SCC artifact payload. */
private const val SUMMARY_MEMBER_LIMIT = 50

/** Lazy shapes up to this many members keep a full per-member payload. */
private const val FULL_PAYLOAD_MEMBER_CAP = 1 shl 20

/**
 * Signature-colliding components are adjudicated by WL refinement up to this
 * size; larger pairs stay distinct shapes (conservative split, logged).
 */
private const val COMPARE_MEMBER_CAP = 1 shl 21

/** Builds and intern canonical shapes for strongly connected components. */
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
    fun collapse(memberIds: IntArray): CyclicShape {
        val build = buildCanonicalForm(memberIds, relaxationStats)
        return internedShapes.getOrPut(SccStructuralKey(build.canonicalKeys)) {
            createCanonicalShape(
                memberIds.size,
                build.canonicalKeys,
                build.orderedMemberIndices,
                build.sourceIndexByCanonicalIndex,
                build.keptKeyByPreQuotientIndex,
                build.internalEdges,
                build.uniformExternalEdges,
                build.setChildren,
            )
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
        val localIndexByObjectId = Int2IntOpenHashMap(memberCount * 2).apply {
            defaultReturnValue(-1)
        }
        for (memberIndex in memberIds.indices) {
            localIndexByObjectId[memberIds[memberIndex]] = memberIndex
        }

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
        val refinement = SccColorRefinement(
            classNames,
            externalEdges,
            internalEdges,
            setChildren,
            edgeComparator,
        )
        val refinementResult = refinement.refine()
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

    /** Exact canonical identity of one component, for collision adjudication. */
    private fun canonicalFormDigest(componentId: Int, memberCount: Int): ShapeDigest {
        val memberIds = IntArray(memberCount)
        var fill = 0
        components.members.forEachMember(componentId) { memberIds[fill++] = it }
        // Throwaway stats: the streaming signature pass already accounted them.
        val build = buildCanonicalForm(memberIds, ShapeRelaxationStats())
        return computeSccDigest(build.canonicalKeys)
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
            if (targetId < 0) {
                multiplicityByChildShape.merge(leafShapeInternalIds[-targetId - 1], 1, Int::plus)
                continue
            }
            val localTargetIndex = localIndexByObjectId.get(targetId)
            if (localTargetIndex >= 0) {
                internalNeighbors[memberIndex].add(localTargetIndex)
            } else {
                val componentId = components.assignment.componentOf(targetId)
                val childShapeId = shapeOfComponent[componentId]
                multiplicityByChildShape.merge(childShapeId, 1, Int::plus)
            }
        }
        externalEdges[memberIndex] = multiplicityByChildShape.map { (childShapeId, multiplicity) ->
            if (setChildren) stats.setChildrenHiddenEdges += multiplicity - 1
            ChildShapeReference(childShapeId, if (setChildren) 1 else multiplicity)
        }.sortedWith(edgeComparator).toMutableList()
    }

    private class LazyEntry(
        val shape: CyclicShape,
        val componentId: Int,
        var canonicalDigest: ShapeDigest?,
    )

    /** Isomorphism-invariant quick key for large components. */
    private data class LazySignature(
        val memberCount: Int,
        val internalEdgeCount: Long,
        /** Sorted packed (classIndex << 32 | count). */
        val classHistogram: List<Long>,
        /** Sorted flat (childShapeId, total) pairs. */
        val externalTotals: List<Long>,
    )

    private val lazyBuckets = HashMap<LazySignature, MutableList<LazyEntry>>()

    /**
     * Lazy WL for large components: a cheap invariant signature (member count,
     * internal edge count, class histogram, external child totals) decides
     * whether refinement is needed at all. Unique signatures — the common case,
     * including the mega-SCC — skip WL entirely; collisions are adjudicated by
     * running the exact WL canonical form on both components and merging only
     * on a match. Pairs beyond [COMPARE_MEMBER_CAP] stay distinct (the
     * conservative direction) with a logged warning.
     */
    fun collapseLazy(componentId: Int, memberCount: Int, progress: (String) -> Unit): CyclicShape {
        val classHistogram = Int2LongOpenHashMap()
        val externalTotals = Int2LongOpenHashMap()
        var internalEdges = 0L
        var relaxedMembers = 0L
        var classOnlyHidden = 0L
        var setChildrenHidden = 0L
        val memberChildScratch = it.unimi.dsi.fastutil.ints.IntOpenHashSet()

        components.members.forEachMember(componentId) { objectId ->
            val classIndex = graph.classIndex(objectId)
            classHistogram.addTo(classIndex, 1L)
            val relaxation = relaxationPolicy.relaxationOf(classIndex)
            if (relaxation != StructuralRelaxation.EXACT) relaxedMembers++
            val setChildren = relaxation == StructuralRelaxation.SET_CHILDREN
            if (setChildren) memberChildScratch.clear()
            for (referenceIndex in 0 until graph.referenceCount(objectId)) {
                val targetId = graph.referenceAt(objectId, referenceIndex)
                val isInternal = targetId >= 0 &&
                    components.assignment.componentOf(targetId) == componentId
                if (isInternal) {
                    internalEdges++
                    continue
                }
                if (relaxation == StructuralRelaxation.CLASS_ONLY) {
                    classOnlyHidden++
                    continue
                }
                val childShapeId = if (targetId < 0) {
                    leafShapeInternalIds[-targetId - 1]
                } else {
                    shapeOfComponent[components.assignment.componentOf(targetId)]
                }
                if (setChildren && !memberChildScratch.add(childShapeId)) {
                    setChildrenHidden++
                } else {
                    externalTotals.addTo(childShapeId, 1L)
                }
            }
        }
        relaxationStats.relaxedObjects += relaxedMembers
        relaxationStats.classOnlyHiddenEdges += classOnlyHidden
        relaxationStats.setChildrenHiddenEdges += setChildrenHidden

        val signature = LazySignature(
            memberCount,
            internalEdges,
            classHistogram.int2LongEntrySet()
                .map { (it.intKey.toLong() shl 32) or it.longValue }
                .sorted(),
            externalTotals.int2LongEntrySet()
                .sortedBy { it.intKey }
                .flatMap { listOf(it.intKey.toLong(), it.longValue) },
        )

        val bucket = lazyBuckets.getOrPut(signature) { mutableListOf() }
        var newCanonicalDigest: ShapeDigest? = null
        if (bucket.isNotEmpty()) {
            if (memberCount <= COMPARE_MEMBER_CAP) {
                progress(
                    "scc-lazy: signature collision at $memberCount members — " +
                        "running WL refinement to adjudicate"
                )
                newCanonicalDigest = canonicalFormDigest(componentId, memberCount)
                for (entry in bucket) {
                    val entryDigest = entry.canonicalDigest
                        ?: canonicalFormDigest(entry.componentId, memberCount)
                            .also { entry.canonicalDigest = it }
                    if (entryDigest == newCanonicalDigest) return entry.shape
                }
            } else {
                progress(
                    "scc-lazy: WARNING signature collision at $memberCount members " +
                        "exceeds the WL compare cap — components kept as distinct shapes"
                )
            }
        }

        val shape = createLazyShape(
            componentId,
            memberCount,
            internalEdges,
            classHistogram,
            externalTotals,
            bucketOrdinal = bucket.size,
        )
        bucket.add(LazyEntry(shape, componentId, newCanonicalDigest))
        return shape
    }

    private fun createLazyShape(
        componentId: Int,
        memberCount: Int,
        internalEdges: Long,
        classHistogram: Int2LongOpenHashMap,
        externalTotals: Int2LongOpenHashMap,
        bucketOrdinal: Int,
    ): CyclicShape {
        val totalEdges = externalTotals.int2LongEntrySet()
            .map { entry ->
                check(entry.longValue <= Int.MAX_VALUE) { "external multiplicity overflow" }
                CanonicalShapeEdge(shapes[entry.intKey], entry.longValue.toInt())
            }
            .sortedWith { first, second -> first.child.digest.compareTo(second.child.digest) }
        check(internalEdges <= Int.MAX_VALUE) { "internal edge count overflow" }

        val sortedClassIndices = classHistogram.keys.toIntArray().sortedBy {
            graph.classNames[it].name
        }
        val digest = computeShapeDigest {
            writeByte(4)
            writeInt(memberCount)
            writeLong(internalEdges)
            writeInt(sortedClassIndices.size)
            for (classIndex in sortedClassIndices) {
                writeUtf8(graph.classNames[classIndex].name)
                writeLong(classHistogram.get(classIndex))
            }
            for (edge in totalEdges) {
                writeDigest(edge.child.digest)
                writeInt(edge.multiplicity)
            }
            writeInt(bucketOrdinal)
        }

        val memberPayload = if (memberCount <= FULL_PAYLOAD_MEMBER_CAP) {
            buildMemberPayload(componentId, memberCount)
        } else {
            sortedClassIndices
                .sortedByDescending { classHistogram.get(it) }
                .take(SUMMARY_MEMBER_LIMIT)
                .map { classIndex ->
                    CanonicalSccMember(
                        graph.classNames[classIndex],
                        internalReferenceCount = 0,
                        internalConnections = emptyList(),
                        externalEdges = emptyList(),
                    )
                }
        }

        return SummarizedSccShape(
            internalId = shapes.size,
            digest = digest,
            totalExternalEdges = totalEdges,
            members = memberPayload,
            memberCount = memberCount,
            totalInternalEdges = internalEdges.toInt(),
            distinctClassCount = sortedClassIndices.size,
        ).also(shapes::add)
    }

    /**
     * Per-member payload for the nested-shapes view, in member order (not WL
     * canonical order — the order is presentational, not part of the key).
     */
    private fun buildMemberPayload(componentId: Int, memberCount: Int): List<CanonicalSccMember> {
        val memberIds = IntArray(memberCount)
        var fill = 0
        components.members.forEachMember(componentId) { memberIds[fill++] = it }
        val localIndexByObjectId = Int2IntOpenHashMap(memberCount * 2).apply {
            defaultReturnValue(-1)
        }
        for (memberIndex in memberIds.indices) {
            localIndexByObjectId[memberIds[memberIndex]] = memberIndex
        }

        return memberIds.map { objectId ->
            val relaxation = relaxationPolicy.relaxationOf(graph.classIndex(objectId))
            val internalTargets = ArrayList<Int>()
            val externalCounts = LinkedHashMap<Int, Int>()
            for (referenceIndex in 0 until graph.referenceCount(objectId)) {
                val targetId = graph.referenceAt(objectId, referenceIndex)
                val localTargetIndex = if (targetId < 0) -1 else localIndexByObjectId.get(targetId)
                if (localTargetIndex >= 0) {
                    internalTargets.add(localTargetIndex)
                } else if (relaxation != StructuralRelaxation.CLASS_ONLY) {
                    val childShapeId = if (targetId < 0) {
                        leafShapeInternalIds[-targetId - 1]
                    } else {
                        shapeOfComponent[components.assignment.componentOf(targetId)]
                    }
                    externalCounts.merge(childShapeId, 1, Int::plus)
                }
            }
            internalTargets.sort()
            CanonicalSccMember(
                graph.className(objectId),
                internalReferenceCount = internalTargets.size,
                internalConnections = foldConnections(
                    internalTargets,
                    relaxation == StructuralRelaxation.SET_CHILDREN,
                ),
                externalEdges = externalCounts.map { (childShapeId, multiplicity) ->
                    CanonicalShapeEdge(
                        shapes[childShapeId],
                        if (relaxation == StructuralRelaxation.SET_CHILDREN) 1 else multiplicity,
                    )
                }.sortedWith { first, second -> first.child.digest.compareTo(second.child.digest) },
            )
        }
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
                relaxationStats.setChildrenHiddenEdges +=
                    internalNeighbors[orderedMemberIndices[keyIndex]].size -
                        key.internalColorRanks.size
            } else {
                keptKeyByPreQuotientIndex[keyIndex] = existingQuotientIndex
                relaxationStats.setChildrenHiddenEdges +=
                    internalNeighbors[orderedMemberIndices[keyIndex]].size + key.externalEdges.size
            }
        }
        return QuotientResult(quotientKeys, sourceIndices.toIntArray())
    }

    private fun createCanonicalShape(
        originalMemberCount: Int,
        canonicalKeys: List<SccMemberStructuralKey>,
        orderedMemberIndices: IntArray,
        sourceIndexByCanonicalIndex: IntArray,
        keptKeyByPreQuotientIndex: IntArray,
        internalNeighbors: Array<MutableList<Int>>,
        uniformExternalEdges: Boolean,
        setChildren: Boolean,
    ): CyclicShape {
        val preQuotientIndexByOriginalIndex = IntArray(originalMemberCount)
        for (preQuotientIndex in orderedMemberIndices.indices) {
            preQuotientIndexByOriginalIndex[orderedMemberIndices[preQuotientIndex]] =
                preQuotientIndex
        }

        val memberPayload = canonicalKeys.mapIndexed { canonicalIndex, key ->
            val sourceIndex = orderedMemberIndices[sourceIndexByCanonicalIndex[canonicalIndex]]
            val targets = internalNeighbors[sourceIndex]
                .map { keptKeyByPreQuotientIndex[preQuotientIndexByOriginalIndex[it]] }
                .sorted()
            val internalConnections = foldConnections(targets, setChildren)
            CanonicalSccMember(
                key.className,
                key.internalColorRanks.size,
                internalConnections,
                key.externalEdges.map { edge ->
                    CanonicalShapeEdge(shapes[edge.childShapeId], edge.multiplicity)
                },
            )
        }

        val totalExternalEdges = if (uniformExternalEdges) {
            emptyList()
        } else {
            totalExternalEdges(memberPayload)
        }
        val packedChildEdges = if (uniformExternalEdges) {
            memberPayload.first().externalEdges.map { edge ->
                ChildShapeEdge(edge.child.internalId, edge.multiplicity).packed
            }.toLongArray()
        } else {
            LongArray(0)
        }
        val digest = computeSccDigest(canonicalKeys)
        return CyclicShape(
            internalId = shapes.size,
            digest = digest,
            packedChildEdges = packedChildEdges,
            totalExternalEdges = totalExternalEdges,
            members = memberPayload,
            quotient = setChildren,
        ).also(shapes::add)
    }

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
            } else {
                if (previousTarget >= 0) {
                    connections += SccMemberConnection(
                        previousTarget,
                        if (setChildren) 1 else multiplicity,
                    )
                }
                previousTarget = target
                multiplicity = 1
            }
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
        }.sortedWith { first, second ->
            first.child.digest.compareTo(second.child.digest)
        }
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
}
