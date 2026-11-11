package shapes.analysis.internal

import shapes.analysis.ShapeDigest
import shapes.analysis.SccMemberConnection
import shapes.analysis.computeShapeDigest
import shapes.graph.ObjectGraph
import shapes.relaxation.ShapeRelaxationPolicy
import shapes.relaxation.ShapeRelaxationStats
import shapes.relaxation.StructuralRelaxation

/** Builds and intern canonical shapes for strongly connected components. */
internal class CyclicShapeCollapser(
    private val graph: ObjectGraph,
    private val relaxationPolicy: ShapeRelaxationPolicy,
    private val relaxationStats: ShapeRelaxationStats,
    private val setSemantics: Boolean,
    private val shapes: ArrayList<CanonicalShape>,
    private val shapeOfComponent: IntArray,
    private val components: StronglyConnectedComponents,
    private val internedShapes: MutableMap<SccStructuralKey, CyclicShape>,
    private val edgeComparator: Comparator<ChildShapeReference>,
) {
    fun collapse(memberIds: IntArray): CyclicShape {
        val memberCount = memberIds.size
        val localIndexByObjectId = HashMap<Int, Int>(memberCount * 2)
        for (memberIndex in memberIds.indices) {
            localIndexByObjectId[memberIds[memberIndex]] = memberIndex
        }

        val relaxations = Array(memberCount) { memberIndex ->
            relaxationPolicy.relaxationOf(graph.classIndex(memberIds[memberIndex]))
        }
        val useSetSemantics = setSemantics ||
            relaxations.any { it == StructuralRelaxation.SET_CHILDREN }
        val externalEdges = Array(memberCount) { mutableListOf<ChildShapeReference>() }
        val internalNeighbors = Array(memberCount) { mutableListOf<Int>() }

        for (memberIndex in 0 until memberCount) {
            collectMemberEdges(
                memberIds[memberIndex],
                memberIndex,
                relaxations[memberIndex],
                useSetSemantics,
                localIndexByObjectId,
                externalEdges,
                internalNeighbors,
            )
        }

        val classNames = Array(memberCount) { graph.className(memberIds[it]) }
        val refinement = SccColorRefinement(
            classNames,
            externalEdges,
            internalNeighbors,
            useSetSemantics,
            edgeComparator,
        )
        val refinementResult = refinement.refine()
        val colors = refinementResult.colors
        val colorRanks = refinementResult.colorRanks
        val orderedMemberIndices = (0 until memberCount).sortedWith { first, second ->
            refinement.compare(colors[first], colors[second])
        }

        val keysBeforeQuotient = orderedMemberIndices.map { memberIndex ->
            val internalColorRanks = internalNeighbors[memberIndex]
                .map { colorRanks[it] }
                .let { if (useSetSemantics) it.distinct() else it }
                .sorted()
            SccMemberStructuralKey(
                classNames[memberIndex],
                internalColorRanks,
                externalEdges[memberIndex],
            )
        }

        val keptKeyByPreQuotientIndex = IntArray(keysBeforeQuotient.size) { it }
        var sourceIndexByCanonicalIndex = IntArray(keysBeforeQuotient.size) { it }
        val canonicalKeys = if (!useSetSemantics) {
            keysBeforeQuotient
        } else {
            applySetQuotient(
                keysBeforeQuotient,
                orderedMemberIndices,
                internalNeighbors,
                keptKeyByPreQuotientIndex,
            ).also { quotient ->
                sourceIndexByCanonicalIndex = quotient.sourceIndices
                relaxationStats.setChildrenConcealedObjects +=
                    (keysBeforeQuotient.size - quotient.keys.size).toLong()
            }.keys
        }

        val uniformExternalEdges = canonicalKeys.all {
            it.externalEdges == canonicalKeys[0].externalEdges
        }
        return internedShapes.getOrPut(SccStructuralKey(canonicalKeys)) {
            createCanonicalShape(
                memberCount,
                canonicalKeys,
                orderedMemberIndices,
                sourceIndexByCanonicalIndex,
                keptKeyByPreQuotientIndex,
                internalNeighbors,
                uniformExternalEdges,
                useSetSemantics,
            )
        }
    }

    private fun collectMemberEdges(
        objectId: Int,
        memberIndex: Int,
        relaxation: StructuralRelaxation,
        useSetSemantics: Boolean,
        localIndexByObjectId: Map<Int, Int>,
        externalEdges: Array<MutableList<ChildShapeReference>>,
        internalNeighbors: Array<MutableList<Int>>,
    ) {
        if (relaxation != StructuralRelaxation.EXACT) relaxationStats.relaxedObjects++

        if (relaxation == StructuralRelaxation.CLASS_ONLY) {
            var hiddenExternalReferences = 0
            graph.forEachReference(objectId) { targetId ->
                val localTargetIndex = localIndexByObjectId[targetId]
                if (localTargetIndex != null) {
                    internalNeighbors[memberIndex].add(localTargetIndex)
                } else {
                    hiddenExternalReferences++
                }
            }
            relaxationStats.classOnlyHiddenEdges += hiddenExternalReferences
            externalEdges[memberIndex] = mutableListOf()
            return
        }

        val multiplicityByChildShape = LinkedHashMap<Int, Int>()
        graph.forEachReference(objectId) { targetId ->
            val localTargetIndex = localIndexByObjectId[targetId]
            if (localTargetIndex != null) {
                internalNeighbors[memberIndex].add(localTargetIndex)
            } else {
                val componentId = components.assignment.componentOf(targetId)
                val childShapeId = shapeOfComponent[componentId]
                multiplicityByChildShape.merge(childShapeId, 1, Int::plus)
            }
        }
        externalEdges[memberIndex] = multiplicityByChildShape.map { (childShapeId, multiplicity) ->
            if (useSetSemantics) relaxationStats.setChildrenHiddenEdges += multiplicity - 1
            ChildShapeReference(childShapeId, if (useSetSemantics) 1 else multiplicity)
        }.sortedWith(edgeComparator).toMutableList()
    }

    private class QuotientResult(
        val keys: List<SccMemberStructuralKey>,
        val sourceIndices: IntArray,
    )

    private fun applySetQuotient(
        keys: List<SccMemberStructuralKey>,
        orderedMemberIndices: List<Int>,
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
        orderedMemberIndices: List<Int>,
        sourceIndexByCanonicalIndex: IntArray,
        keptKeyByPreQuotientIndex: IntArray,
        internalNeighbors: Array<MutableList<Int>>,
        uniformExternalEdges: Boolean,
        useSetSemantics: Boolean,
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
            val internalConnections = foldConnections(targets, useSetSemantics)
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
            quotient = useSetSemantics,
        ).also(shapes::add)
    }

    private fun foldConnections(
        sortedTargets: List<Int>,
        useSetSemantics: Boolean,
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
                        if (useSetSemantics) 1 else multiplicity,
                    )
                }
                previousTarget = target
                multiplicity = 1
            }
        }
        if (previousTarget >= 0) {
            connections += SccMemberConnection(
                previousTarget,
                if (useSetSemantics) 1 else multiplicity,
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
