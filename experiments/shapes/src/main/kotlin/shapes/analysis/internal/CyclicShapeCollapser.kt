package shapes.analysis.internal

import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap
import shapes.analysis.ShapeDigest
import shapes.analysis.SccMemberConnection
import shapes.analysis.computeShapeDigest
import shapes.graph.AnalysisGraph
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
    private val relaxations: ObjectRelaxations,
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
    private fun childShapeIdOf(targetId: Int, targetComponent: Int = -1): Int = if (targetId < 0) {
        leafShapeInternalIds[-targetId - 1]
    } else {
        shapeOfComponent[
            if (targetComponent >= 0) targetComponent else components.assignment.componentOf(targetId)
        ]
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
        return internedShapes.getOrPut(SccStructuralKey(form.groups)) {
            createCanonicalShape(form)
        }
    }

    private class CanonicalForm(
        /** Color classes in canonical order; group index == WL color rank. */
        val groups: List<SccMemberGroup>,
        val setChildren: Boolean,
        val uniformExternalEdges: Boolean,
    )

    /**
     * WL refinement to the fixpoint quotient: one [SccMemberGroup] per stable
     * color. The fixpoint partition is equitable, so each group's key carries
     * the exact per-member connection profile. [stats] receives relaxation
     * counters.
     *
     * [identity] selects the semantics. The identity path applies set
     * relaxation component-wide: internal multiplicities and external edges
     * are deduplicated, duplicate members collapse to count 1 and are
     * accounted as concealed. The payload path (lazy shapes, whose identity is
     * the signature) keeps real internal multiplicities and real group counts;
     * only each set-children member's own external edges are deduplicated,
     * exactly as [summarizeComponent] counts them.
     */
    private fun buildCanonicalForm(
        memberIds: IntArray,
        stats: ShapeRelaxationStats,
        identity: Boolean = true,
    ): CanonicalForm {
        val memberCount = memberIds.size
        val localIndexByObjectId = localIndexOf(memberIds)

        val relaxations = Array(memberCount) { memberIndex ->
            relaxations.relaxationOf(memberIds[memberIndex])
        }
        val setChildren = identity &&
            relaxations.any { it == StructuralRelaxation.SET_CHILDREN }
        val externalEdges = Array(memberCount) { mutableListOf<ChildShapeReference>() }
        val internalEdges = Array(memberCount) { mutableListOf<Int>() }

        for (memberIndex in 0 until memberCount) {
            collectMemberEdges(
                objectId = memberIds[memberIndex],
                memberIndex = memberIndex,
                relaxation = relaxations[memberIndex],
                setChildren = setChildren ||
                    relaxations[memberIndex] == StructuralRelaxation.SET_CHILDREN,
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

        val groups = ArrayList<SccMemberGroup>()
        var groupStart = 0
        while (groupStart < memberCount) {
            val representative = orderedMemberIndices[groupStart]
            var groupEnd = groupStart + 1
            while (groupEnd < memberCount &&
                colorRanks[orderedMemberIndices[groupEnd]] == colorRanks[representative]
            ) {
                groupEnd++
            }
            val internalColorRanks = internalEdges[representative]
                .map { colorRanks[it] }
                .let { if (setChildren) it.distinct() else it }
                .sorted()
            val key = SccMemberStructuralKey(
                classNames[representative],
                internalColorRanks,
                externalEdges[representative],
            )
            if (setChildren) {
                stats.setChildrenConcealedObjects += (groupEnd - groupStart - 1).toLong()
                stats.setChildrenHiddenEdges +=
                    internalEdges[representative].size - internalColorRanks.size
                for (position in groupStart + 1 until groupEnd) {
                    stats.setChildrenHiddenEdges +=
                        internalEdges[orderedMemberIndices[position]].size +
                            key.externalEdges.size
                }
                groups += SccMemberGroup(key, 1)
            } else {
                groups += SccMemberGroup(key, groupEnd - groupStart)
            }
            groupStart = groupEnd
        }

        val uniformExternalEdges = groups.all {
            it.key.externalEdges == groups[0].key.externalEdges
        }
        return CanonicalForm(
            groups,
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

    /**
     * Group payload straight from the canonical keys: a group's connections
     * target other groups by WL color rank, which equals the group index.
     */
    private fun memberPayloadOf(form: CanonicalForm): List<CanonicalSccMember> =
        form.groups.map { group ->
            CanonicalSccMember(
                className = group.key.className,
                memberCount = group.memberCount,
                internalReferenceCount = group.key.internalColorRanks.size,
                internalConnections = foldConnections(
                    group.key.internalColorRanks,
                    form.setChildren,
                ),
                externalEdges = group.key.externalEdges.map { edge ->
                    CanonicalShapeEdge(shapes[edge.childShapeId], edge.multiplicity)
                },
            )
        }

    private fun createCanonicalShape(form: CanonicalForm): CyclicShape {
        val memberPayload = memberPayloadOf(form)
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
            digest = computeSccDigest(form.groups),
            packedChildEdges = packedChildEdges,
            totalExternalEdges = totalExternalEdges,
            members = memberPayload,
            memberCount = form.groups.sumOf { it.memberCount },
            quotient = form.setChildren,
        ).also(shapes::add)
    }

    private fun computeSccDigest(
        groups: List<SccMemberGroup>,
    ): ShapeDigest = computeShapeDigest {
        writeByte(3)
        writeInt(groups.size)
        for (group in groups) {
            val member = group.key
            writeUtf8(member.className.name)
            writeInt(group.memberCount)
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
        val classHistogram: LongArray,
        val externalTotals: LongArray,
    ) {
        fun signature() = LazySignature(
            memberCount,
            internalEdges,
            buildList {
                for (classIndex in classHistogram.indices) {
                    val count = classHistogram[classIndex]
                    if (count > 0) add((classIndex.toLong() shl 32) or count)
                }
            },
            buildList {
                for (childShapeId in externalTotals.indices) {
                    val count = externalTotals[childShapeId]
                    if (count > 0) {
                        add(childShapeId.toLong())
                        add(count)
                    }
                }
            },
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

        val shape = createLazyShape(componentId, summary, bucketOrdinal = bucket.size, progress)
        bucket.add(LazyEntry(shape, componentId, canonicalDigest))
        return shape
    }

    /** One streaming pass: class histogram, edge totals, relaxation counters. */
    private fun summarizeComponent(componentId: Int, memberCount: Int): ComponentSummary {
        val classHistogram = LongArray(graph.classNames.size)
        val externalTotals = LongArray(shapes.size)
        val memberChildGenerations = IntArray(shapes.size)
        var memberChildGeneration = 0
        var internalEdges = 0L

        components.members.forEachMember(componentId) { objectId ->
            val classIndex = graph.classIndex(objectId)
            classHistogram[classIndex]++
            val relaxation = relaxations.relaxationOf(objectId)
            if (relaxation != StructuralRelaxation.EXACT) relaxationStats.relaxedObjects++
            val setChildren = relaxation == StructuralRelaxation.SET_CHILDREN
            if (setChildren) {
                if (memberChildGeneration == Int.MAX_VALUE) {
                    memberChildGenerations.fill(0)
                    memberChildGeneration = 0
                }
                memberChildGeneration++
            }

            for (referenceIndex in 0 until graph.referenceCount(objectId)) {
                val targetId = graph.referenceAt(objectId, referenceIndex)
                val targetComponent = if (targetId < 0) {
                    -1
                } else {
                    components.assignment.componentOf(targetId)
                }
                when {
                    targetComponent == componentId -> internalEdges++
                    relaxation == StructuralRelaxation.CLASS_ONLY ->
                        relaxationStats.classOnlyHiddenEdges++
                    else -> {
                        val childShapeId = childShapeIdOf(targetId, targetComponent)
                        if (setChildren && memberChildGenerations[childShapeId] == memberChildGeneration) {
                            relaxationStats.setChildrenHiddenEdges++
                        } else {
                            externalTotals[childShapeId]++
                            if (setChildren) memberChildGenerations[childShapeId] = memberChildGeneration
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
        return computeSccDigest(form.groups)
    }

    /**
     * WL quotient payload for a lazy shape. The refinement runs to the
     * fixpoint — incremental refinement makes the cost independent of the
     * round count — so the partition is always equitable and the group
     * profiles exact. Counts stay real even under set semantics: the lazy
     * identity is the signature, which conceals nothing, so the payload must
     * not either.
     */
    private fun quotientMemberPayload(
        componentId: Int,
        memberCount: Int,
    ): List<CanonicalSccMember> {
        val memberIds = memberIdsOf(componentId, memberCount)
        // Throwaway stats: the streaming signature pass already accounted them.
        val form = buildCanonicalForm(memberIds, ShapeRelaxationStats(), identity = false)
        return memberPayloadOf(form)
    }

    private fun createLazyShape(
        componentId: Int,
        summary: ComponentSummary,
        bucketOrdinal: Int,
        progress: (String) -> Unit,
    ): CyclicShape {
        val totalEdges = buildList {
            for (childShapeId in summary.externalTotals.indices) {
                val count = summary.externalTotals[childShapeId]
                if (count > 0) {
                    check(count <= Int.MAX_VALUE) { "external multiplicity overflow" }
                    add(CanonicalShapeEdge(shapes[childShapeId], count.toInt()))
                }
            }
        }.sortedWith(childEdgeByDigest)
        check(summary.internalEdges <= Int.MAX_VALUE) { "internal edge count overflow" }

        val sortedClassIndices = summary.classHistogram.indices
            .filter { summary.classHistogram[it] > 0 }
            .sortedBy { graph.classNames[it].name }
        val digest = computeShapeDigest {
            writeByte(4)
            writeInt(summary.memberCount)
            writeLong(summary.internalEdges)
            writeInt(sortedClassIndices.size)
            for (classIndex in sortedClassIndices) {
                writeUtf8(graph.classNames[classIndex].name)
                writeLong(summary.classHistogram[classIndex])
            }
            for (edge in totalEdges) {
                writeDigest(edge.child.digest)
                writeInt(edge.multiplicity)
            }
            writeInt(bucketOrdinal)
        }

        val memberPayload = if (summary.memberCount <= FULL_PAYLOAD_MEMBER_CAP) {
            val startedAt = System.nanoTime()
            val quotient = quotientMemberPayload(componentId, summary.memberCount)
            progress(
                "scc-quotient: ${summary.memberCount} members collapsed to " +
                    "${quotient.size} groups in ${(System.nanoTime() - startedAt) / 1_000_000} ms"
            )
            quotient
        } else {
            sortedClassIndices
                .sortedByDescending { summary.classHistogram[it] }
                .take(SUMMARY_CLASS_LIMIT)
                .map { classIndex ->
                    val instanceCount = summary.classHistogram[classIndex]
                    CanonicalSccMember(
                        graph.classNames[classIndex],
                        memberCount = instanceCount.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
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
                totals.merge(edge.child, edge.multiplicity * member.memberCount, Int::plus)
            }
        }
        return totals.map { (child, multiplicity) ->
            CanonicalShapeEdge(child, multiplicity)
        }.sortedWith(childEdgeByDigest)
    }
}
