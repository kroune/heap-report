package shapes.analysis.internal

import shapes.analysis.ShapeKind
import shapes.analysis.computeShapeDigest
import shapes.graph.ClassName

/** Exact structural interning for acyclic shapes; digests are not equality keys. */
internal class ShapeInterner(
    private val progress: (String) -> Unit,
) {
    private var slots = IntArray(INITIAL_SLOT_COUNT) { EMPTY_SLOT }
    private var internedShapeCount = 0
    private var keyHashes = IntArray(INITIAL_HASH_COUNT)

    var maxProbe = 0
        private set

    private var probeReportAt = 50_000

    fun intern(
        shapes: ArrayList<CanonicalShape>,
        classIndex: Int,
        className: ClassName,
        kind: ShapeKind,
        childEdges: LongArray,
        childEdgeCount: Int,
    ): AcyclicShape {
        require(kind == ShapeKind.LEAF || kind == ShapeKind.NODE)
        if ((internedShapeCount + 1) * 4 >= slots.size * 3) rehash(shapes)

        val keyHash = hash(classIndex, childEdges, childEdgeCount)
        var slot = keyHash and (slots.size - 1)
        val probeStride = stride(keyHash, slots.size - 1)
        var probe = 0

        while (true) {
            probe++
            reportProbeIfNecessary(probe, className, classIndex, childEdges, childEdgeCount)
            val storedShapeId = slots[slot]
            if (storedShapeId == EMPTY_SLOT) {
                val shape = createShape(
                    shapes,
                    classIndex,
                    className,
                    kind,
                    childEdges,
                    childEdgeCount,
                    keyHash,
                )
                slots[slot] = shape.internalId
                internedShapeCount++
                return shape
            }

            val candidate = shapes[storedShapeId] as AcyclicShape
            if (keyHashes[storedShapeId] == keyHash &&
                candidate.classIndex == classIndex &&
                childEdgesEqual(candidate.packedChildEdges, childEdges, childEdgeCount)
            ) {
                return candidate
            }
            slot = (slot + probeStride) and (slots.size - 1)
        }
    }

    private fun createShape(
        shapes: ArrayList<CanonicalShape>,
        classIndex: Int,
        className: ClassName,
        kind: ShapeKind,
        childEdges: LongArray,
        childEdgeCount: Int,
        keyHash: Int,
    ): AcyclicShape {
        val packedChildren = childEdges.copyOf(childEdgeCount)
        val digest = computeShapeDigest {
            writeByte(if (kind == ShapeKind.LEAF) 1 else 2)
            writeUtf8(className.name)
            writeInt(packedChildren.size)
            for (packedChild in packedChildren) {
                val child = ChildShapeEdge(packedChild)
                writeDigest(shapes[child.childShapeId].digest)
                writeInt(child.multiplicity)
            }
        }

        val shapeId = shapes.size
        val shape = if (kind == ShapeKind.LEAF) {
            LeafShape(shapeId, className, classIndex, digest)
        } else {
            NodeShape(shapeId, className, classIndex, packedChildren, digest)
        }
        shapes.add(shape)
        if (shapeId >= keyHashes.size) {
            keyHashes = keyHashes.copyOf(maxOf(shapeId + 1, keyHashes.size * 2))
        }
        keyHashes[shapeId] = keyHash
        return shape
    }

    private fun rehash(shapes: List<CanonicalShape>) {
        val grownSlots = IntArray(slots.size * 2) { EMPTY_SLOT }
        for (shape in shapes) {
            if (shape !is AcyclicShape) continue
            val keyHash = keyHashes[shape.internalId]
            var slot = keyHash and (grownSlots.size - 1)
            val probeStride = stride(keyHash, grownSlots.size - 1)
            while (grownSlots[slot] != EMPTY_SLOT) {
                slot = (slot + probeStride) and (grownSlots.size - 1)
            }
            grownSlots[slot] = shape.internalId
        }
        slots = grownSlots
    }

    private fun childEdgesEqual(stored: LongArray, candidate: LongArray, count: Int): Boolean {
        if (stored.size != count) return false
        for (index in 0 until count) {
            if (stored[index] != candidate[index]) return false
        }
        return true
    }

    private fun hash(classIndex: Int, children: LongArray, childCount: Int): Int {
        var hash = classIndex * 31 + childCount
        for (index in 0 until childCount) {
            val child = children[index]
            hash = 31 * hash + (child xor (child ushr 32)).toInt()
        }
        hash = hash xor (hash ushr 16)
        hash *= 0x85ebca6b.toInt()
        hash = hash xor (hash ushr 13)
        hash *= 0xc2b2ae35.toInt()
        hash = hash xor (hash ushr 16)
        return hash
    }

    private fun stride(hash: Int, mask: Int): Int = ((hash ushr 16) or 1) and mask

    private fun reportProbeIfNecessary(
        probe: Int,
        className: ClassName,
        classIndex: Int,
        childEdges: LongArray,
        childEdgeCount: Int,
    ) {
        if (probe <= maxProbe) return
        maxProbe = probe
        if (probe < probeReportAt) return
        probeReportAt *= 2
        progress(
            "LONGPROBE $probe: size=$internedShapeCount slots=${slots.size} " +
                "class=$className($classIndex) childEdges=$childEdgeCount " +
                "children=${childEdges.take(minOf(6, childEdgeCount)).joinToString { child ->
                    "${child ushr 32}x${child and 0xffffffff}"
                }}"
        )
    }

    private companion object {
        const val INITIAL_SLOT_COUNT = 1 shl 16
        const val INITIAL_HASH_COUNT = 4096
        const val EMPTY_SLOT = -1
    }
}
