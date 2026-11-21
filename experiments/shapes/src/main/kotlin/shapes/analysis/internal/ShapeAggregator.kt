package shapes.analysis.internal

import shapes.analysis.ShapeAnalysisResult
import shapes.analysis.ShapeEdge
import shapes.analysis.ShapeEntry
import shapes.analysis.SccMemberEntry
import shapes.graph.AnalysisGraph
import java.util.BitSet

/** Computes per-shape aggregates and assigns deterministic public shape ids. */
internal class ShapeAggregator(
    private val graph: AnalysisGraph,
    private val components: StronglyConnectedComponents,
    private val collapse: CanonicalShapes,
) {
    operator fun invoke(): ShapeAnalysisResult {
        val rootIds = graph.roots
        val shapes = collapse.shapes
        val shapeCount = shapes.size
        val instanceCounts = LongArray(shapeCount)
        val shallowTotals = ULongArray(shapeCount)
        val shallowMinimums = ULongArray(shapeCount) { ULong.MAX_VALUE }
        val shallowMaximums = ULongArray(shapeCount)
        val rootCounts = LongArray(shapeCount)
        val rootMembership = BitSet(graph.size)
        for (rootId in rootIds) rootMembership.set(rootId)

        for ((leafOrdinal, aggregate) in graph.leafShapes.withIndex()) {
            val internalId = collapse.leafShapeInternalIds[leafOrdinal]
            instanceCounts[internalId] += aggregate.instanceCount
            shallowTotals[internalId] += aggregate.shallowTotal
            if (aggregate.shallowMin < shallowMinimums[internalId]) {
                shallowMinimums[internalId] = aggregate.shallowMin
            }
            if (aggregate.shallowMax > shallowMaximums[internalId]) {
                shallowMaximums[internalId] = aggregate.shallowMax
            }
            rootCounts[internalId] += aggregate.rootCount
        }

        for (componentId in 0 until components.members.count) {
            val shapeId = collapse.shapeOfComponent[componentId]
            val shape = shapes[shapeId]
            val quotientSize = if (shape is CyclicShape && shape.quotient) {
                shape.memberCount
            } else {
                0
            }
            if (quotientSize > 0) instanceCounts[shapeId] += quotientSize

            components.members.forEachMember(componentId) { objectId ->
                if (quotientSize == 0) instanceCounts[shapeId]++
                val shallowSize = graph.shallowSize(objectId)
                shallowTotals[shapeId] += shallowSize
                if (shallowSize < shallowMinimums[shapeId]) shallowMinimums[shapeId] = shallowSize
                if (shallowSize > shallowMaximums[shapeId]) shallowMaximums[shapeId] = shallowSize
                if (rootMembership.get(objectId)) rootCounts[shapeId]++
            }
        }

        val orderedShapes = shapes.sortedBy { it.digest }
        val publicIdByInternalId = IntArray(shapeCount)
        orderedShapes.forEachIndexed { publicId, shape ->
            publicIdByInternalId[shape.internalId] = publicId
        }
        val entries = orderedShapes.mapIndexed { publicId, shape ->
            createEntry(
                publicId,
                shape,
                instanceCounts[shape.internalId],
                shallowTotals[shape.internalId],
                shallowMinimums[shape.internalId],
                shallowMaximums[shape.internalId],
                rootCounts[shape.internalId],
            )
        }
        val edges = createEdges(shapes, publicIdByInternalId)
        val rootsHistogram = createRootsHistogram(
            rootIds,
            components,
            collapse,
            publicIdByInternalId,
        )
        return ShapeAnalysisResult(graph.totalObjectCount, entries, edges, rootsHistogram)
    }

    private fun createEntry(
        publicId: Int,
        shape: CanonicalShape,
        instanceCount: Long,
        shallowTotal: ULong,
        shallowMin: ULong,
        shallowMax: ULong,
        rootCount: Long,
    ): ShapeEntry {
        if (shape is AcyclicShape) {
            return ShapeEntry(
                publicId,
                shape.digest,
                shape.kind,
                shape.className.name,
                instanceCount,
                shallowTotal,
                shallowMin,
                shallowMax,
                rootCount,
                sccInstanceSize = 1,
                internalEdgesPerInstance = 0,
                edgesPerMember = shape.packedChildEdges.isNotEmpty(),
                members = null,
                membersComplete = false,
            )
        }

        if (shape is LazySccShape) {
            val topClasses = shape.members.take(3).joinToString(",") { it.className.name }
            return ShapeEntry(
                publicId,
                shape.digest,
                shape.kind,
                "scc[${shape.memberCount} members/${shape.distinctClassCount} classes: $topClasses,...]",
                instanceCount,
                shallowTotal,
                shallowMin,
                shallowMax,
                rootCount,
                sccInstanceSize = shape.memberCount,
                internalEdgesPerInstance = shape.totalInternalEdges,
                edgesPerMember = false,
                members = shape.members.map { member ->
                    SccMemberEntry(member.className, member.memberCount, member.internalConnections)
                },
                membersComplete = isCompleteQuotient(shape, shape.totalInternalEdges.toLong()),
            )
        }

        shape as CyclicShape
        val label = "scc[${shape.members.joinToString(",") { member ->
            if (member.memberCount > 1) "${member.className.name}×${member.memberCount}"
            else member.className.name
        }}]"
        return ShapeEntry(
            publicId,
            shape.digest,
            shape.kind,
            label,
            instanceCount,
            shallowTotal,
            shallowMin,
            shallowMax,
            rootCount,
            sccInstanceSize = shape.memberCount,
            internalEdgesPerInstance = shape.members.sumOf {
                it.memberCount * it.internalReferenceCount
            },
            edgesPerMember = shape.packedChildEdges.isNotEmpty(),
            members = shape.members.map { member ->
                SccMemberEntry(member.className, member.memberCount, member.internalConnections)
            },
            membersComplete = true,
        )
    }

    /**
     * A payload is a complete quotient when its groups cover every member and
     * the expanded connection multiplicities cover every internal edge; set
     * relaxation inside a lazy component or a summary payload makes it fail
     * (deliberately — the viewer must not render a partial graph as exact).
     */
    private fun isCompleteQuotient(shape: CyclicShape, totalInternalEdges: Long): Boolean {
        val coveredMembers = shape.members.sumOf { it.memberCount.toLong() }
        if (coveredMembers != shape.memberCount.toLong()) return false
        val coveredEdges = shape.members.sumOf { member ->
            member.memberCount.toLong() *
                member.internalConnections.sumOf { it.multiplicity.toLong() }
        }
        return coveredEdges == totalInternalEdges
    }

    private fun createEdges(
        shapes: List<CanonicalShape>,
        publicIdByInternalId: IntArray,
    ): List<ShapeEdge> {
        val result = ArrayList<ShapeEdge>()
        for (shape in shapes) {
            when (shape) {
                is AcyclicShape -> addPackedEdges(shape, shape.packedChildEdges, publicIdByInternalId, result)
                is CyclicShape -> if (shape.packedChildEdges.isEmpty()) {
                    addTotalEdges(shape, publicIdByInternalId, result)
                } else {
                    addPackedEdges(shape, shape.packedChildEdges, publicIdByInternalId, result)
                }
            }
        }
        return result
    }

    private fun addPackedEdges(
        shape: CanonicalShape,
        packedEdges: LongArray,
        publicIdByInternalId: IntArray,
        destination: MutableList<ShapeEdge>,
    ) {
        for (packedEdge in packedEdges) {
            val edge = ChildShapeEdge(packedEdge)
            destination += ShapeEdge(
                publicIdByInternalId[shape.internalId],
                publicIdByInternalId[edge.childShapeId],
                edge.multiplicity.toLong(),
            )
        }
    }

    private fun addTotalEdges(
        shape: CyclicShape,
        publicIdByInternalId: IntArray,
        destination: MutableList<ShapeEdge>,
    ) {
        for (edge in shape.totalExternalEdges) {
            destination += ShapeEdge(
                publicIdByInternalId[shape.internalId],
                publicIdByInternalId[edge.child.internalId],
                edge.multiplicity.toLong(),
            )
        }
    }

    private fun createRootsHistogram(
        rootIds: IntArray,
        components: StronglyConnectedComponents,
        collapse: CanonicalShapes,
        publicIdByInternalId: IntArray,
    ): Map<Int, Long> {
        val histogram = HashMap<Int, Long>()
        for (rootId in rootIds) {
            val componentId = components.assignment.componentOf(rootId)
            val internalShapeId = collapse.shapeOfComponent[componentId]
            histogram.merge(publicIdByInternalId[internalShapeId], 1L, Long::plus)
        }
        return histogram
    }
}
