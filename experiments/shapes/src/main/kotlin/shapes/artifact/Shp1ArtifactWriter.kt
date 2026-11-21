package shapes.artifact

import shapes.analysis.ShapeAnalysisResult
import java.io.DataOutputStream
import java.nio.file.Files
import java.nio.file.Path

/**
 * Writes the SHP1 shape table and its SCC2 member-graph section in big-endian
 * binary form. Unsigned shallow-size values are written as their raw 64 bits.
 *
 * SCC2 member rows are WL quotient groups: label, member count, and the
 * group's internal connections (target group index, per-member multiplicity).
 */
class Shp1ArtifactWriter(private val path: Path) {
    fun write(result: ShapeAnalysisResult) {
        val labels = buildList {
            addAll(result.entries.map { it.label })
            addAll(result.entries.flatMap { entry ->
                entry.members.orEmpty().map { it.className.name }
            })
        }.distinct()
        val labelIndices = labels.withIndex().associate { (index, label) -> label to index }

        Files.newOutputStream(path).use { rawStream ->
            val output = DataOutputStream(rawStream.buffered())
            output.writeBytes(SHP1_MAGIC)
            writeLabels(output, labels)
            writeShapes(output, result, labelIndices)
            writeEdges(output, result)
            writeRootsHistogram(output, result)
            writeSccMembers(output, result, labelIndices)
            output.flush()
        }
    }

    private fun writeLabels(output: DataOutputStream, labels: List<String>) {
        output.writeInt(labels.size)
        for (label in labels) {
            val bytes = label.toByteArray(Charsets.UTF_8)
            output.writeInt(bytes.size)
            output.write(bytes)
        }
    }

    private fun writeShapes(
        output: DataOutputStream,
        result: ShapeAnalysisResult,
        labelIndices: Map<String, Int>,
    ) {
        output.writeInt(result.entries.size)
        for (entry in result.entries) {
            output.writeLong(entry.digest.mostSignificantBits)
            output.writeLong(entry.digest.leastSignificantBits)
            output.writeByte(entry.kind.ordinal)
            output.writeInt(labelIndices.getValue(entry.label))
            output.writeLong(entry.instanceCount)
            output.writeLong(entry.shallowTotal.toLong())
            output.writeLong(entry.shallowMin.toLong())
            output.writeLong(entry.shallowMax.toLong())
            output.writeLong(entry.rootCount)
            output.writeInt(entry.sccInstanceSize)
            output.writeInt(entry.internalEdgesPerInstance)
            output.writeByte(if (entry.edgesPerMember) 1 else 0)
        }
    }

    private fun writeEdges(output: DataOutputStream, result: ShapeAnalysisResult) {
        output.writeInt(result.edges.size)
        for (edge in result.edges) {
            output.writeInt(edge.shapeId)
            output.writeInt(edge.childShapeId)
            output.writeLong(edge.multiplicity)
        }
    }

    private fun writeRootsHistogram(output: DataOutputStream, result: ShapeAnalysisResult) {
        output.writeInt(result.rootsHistogram.size)
        for ((shapeId, count) in result.rootsHistogram) {
            output.writeInt(shapeId)
            output.writeLong(count)
        }
    }

    private fun writeSccMembers(
        output: DataOutputStream,
        result: ShapeAnalysisResult,
        labelIndices: Map<String, Int>,
    ) {
        output.writeBytes(SCC2_MAGIC)
        val describedShapes = result.entries.filter { it.members != null }
        output.writeInt(describedShapes.size)
        for (shape in describedShapes) {
            val members = shape.members.orEmpty()
            output.writeInt(shape.id)
            output.writeInt(members.size)
            output.writeByte(if (shape.membersComplete) 1 else 0)
            for (member in members) {
                output.writeInt(labelIndices.getValue(member.className.name))
                output.writeInt(member.memberCount)
                output.writeInt(member.internalConnections.size)
                for (connection in member.internalConnections) {
                    output.writeInt(connection.targetMemberIndex)
                    output.writeInt(connection.multiplicity)
                }
            }
        }
    }

    private companion object {
        const val SHP1_MAGIC = "SHP1"
        const val SCC2_MAGIC = "SCC2"
    }
}
