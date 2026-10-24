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

    fun write(t: ShapeTable, out: Path) {
        val labels = (t.entries.map { it.label } + t.entries.flatMap { e ->
            e.members.orEmpty().map { it.className }
        }).distinct()
        val labelIdx = labels.withIndex().associate { (i, l) -> l to i }
        Files.newOutputStream(out).use { raw ->
            val d = DataOutputStream(raw.buffered())
            d.writeBytes("SHP1")
            d.writeInt(labels.size)
            for (l in labels) {
                val b = l.toByteArray(Charsets.UTF_8)
                d.writeInt(b.size); d.write(b)
            }
            d.writeInt(t.entries.size)
            for (e in t.entries) {
                d.writeLong(e.digest.hi); d.writeLong(e.digest.lo)
                d.writeByte(e.kind.ordinal)
                d.writeInt(labelIdx.getValue(e.label))
                d.writeLong(e.instanceCount)
                d.writeLong(e.shallowTotal)
                d.writeLong(e.shallowMin)
                d.writeLong(e.shallowMax)
                d.writeLong(e.rootCount)
                d.writeInt(e.sccInstanceSize)
                d.writeInt(e.internalEdgesPerInstance)
                d.writeByte(if (e.edgesPerMember) 1 else 0)
            }
            d.writeInt(t.edges.size)
            for (e in t.edges) {
                d.writeInt(e.shapeId); d.writeInt(e.childShapeId); d.writeLong(e.mult)
            }
            d.writeInt(t.rootsHistogram.size)
            for ((sid, cnt) in t.rootsHistogram) {
                d.writeInt(sid); d.writeLong(cnt)
            }
            d.writeBytes("SCC1")
            val described = t.entries.filter { it.members != null }
            d.writeInt(described.size)
            for (e in described) {
                val members = e.members!!
                d.writeInt(e.id)
                d.writeInt(members.size)
                d.writeByte(if (e.membersComplete) 1 else 0)
                for (m in members) {
                    d.writeInt(labelIdx.getValue(m.className))
                    d.writeInt(m.internal.size)
                    for (edge in m.internal) {
                        d.writeInt(edge.target)
                        d.writeInt(edge.mult)
                    }
                }
            }
            d.flush()
        }
    }
}
