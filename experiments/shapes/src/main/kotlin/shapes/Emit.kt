package shapes

import java.io.DataOutputStream
import java.nio.file.Files
import java.nio.file.Path

/**
 * Binary artifact (SHAPES.md phase 5 — "CSV is dead at this scale").
 * Layout (all big-endian):
 *   magic "SHP1"
 *   i32 labelCount; per label: i32 utf8Len + bytes      (shape labels / class names)
 *   i32 shapeCount; per shape:
 *     digest128 (hi i64, lo i64), u8 kind (0 leaf, 1 node, 2 scc, 3 scc-unique),
 *     i32 labelIdx, i64 instanceCount, i64 shallowTotal, i64 shallowMin,
 *     i64 shallowMax, i64 rootCount, i32 sccInstanceSize,
 *     i32 internalEdgesPerInstance, u8 edgesPerMember
 *   i32 edgeCount; per edge: i32 shapeId, i32 childShapeId, i64 multiplicity
 *   i32 rootsHistCount; per row: i32 shapeId, i64 count
 *   magic "SCC1"
 *   i32 describedShapeCount; per SCC shape: i32 shapeId, i32 memberCount,
 *     u8 flags (bit 0 = complete member graph);
 *     per member: i32 labelIdx, i32 internalConnectionCount;
 *     per connection: i32 targetMemberIndex, i32 multiplicity
 *
 * SCC1 is optional when reading: old SHP1 artifacts remain valid, but only
 * new artifacts have enough target identity to render the internal graph.
 * Parallel references are one connection with a multiplicity. Mega SCC_UNIQUE
 * members stay truncated to the existing 8-label budget. The completeness
 * flag is explicit: a custom merge cap below 8 can otherwise make a partial
 * graph look complete by member count alone.
 */
object ShapesEmit {

    fun write(table: ShapeTable, out: Path) {
        val labels =
            buildList {
                addAll(table.entries.map { it.label })
                addAll(table.entries.flatMap { e ->
                    // TODO: figure why .name
                    e.members.orEmpty().map { it.className.name }
                })
            }.distinct()
        val labelIdx = labels.withIndex().associate { (i, l) -> l to i }
        Files.newOutputStream(out).use { raw ->
            val outputStream = DataOutputStream(raw.buffered())
            outputStream.writeBytes("SHP1")
            outputStream.writeInt(labels.size)
            for (l in labels) {
                val b = l.toByteArray(Charsets.UTF_8)
                outputStream.writeInt(b.size); outputStream.write(b)
            }
            outputStream.writeInt(table.entries.size)
            for (entry in table.entries) {
                outputStream.writeLong(entry.digest.hi); outputStream.writeLong(entry.digest.lo)
                outputStream.writeByte(entry.kind.ordinal)
                outputStream.writeInt(labelIdx.getValue(entry.label))
                outputStream.writeLong(entry.instanceCount)
                // TODO
                outputStream.writeLong(entry.shallowTotal.toLong())
                outputStream.writeLong(entry.shallowMin.toLong())
                outputStream.writeLong(entry.shallowMax.toLong())
                outputStream.writeLong(entry.rootCount)
                outputStream.writeInt(entry.sccInstanceSize)
                outputStream.writeInt(entry.internalEdgesPerInstance)
                outputStream.writeByte(if (entry.edgesPerMember) 1 else 0)
            }
            outputStream.writeInt(table.edges.size)
            for (e in table.edges) {
                outputStream.writeInt(e.shapeId); outputStream.writeInt(e.childShapeId); outputStream.writeLong(e.mult)
            }
            outputStream.writeInt(table.rootsHistogram.size)
            for ((sid, cnt) in table.rootsHistogram) {
                outputStream.writeInt(sid); outputStream.writeLong(cnt)
            }
            outputStream.writeBytes("SCC1")
            val described = table.entries.filter { it.members != null }
            outputStream.writeInt(described.size)
            for (e in described) {
                val members = e.members!!
                outputStream.writeInt(e.id)
                outputStream.writeInt(members.size)
                outputStream.writeByte(if (e.membersComplete) 1 else 0)
                for (m in members) {
                    // TODO: figure out why .name
                    outputStream.writeInt(labelIdx.getValue(m.className.name))
                    outputStream.writeInt(m.internal.size)
                    for (edge in m.internal) {
                        outputStream.writeInt(edge.target)
                        outputStream.writeInt(edge.mult)
                    }
                }
            }
            outputStream.flush()
        }
    }
}
