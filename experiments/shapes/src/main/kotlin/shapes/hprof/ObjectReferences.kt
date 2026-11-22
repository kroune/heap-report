package shapes.hprof

import shapes.graph.ClassName
import shapes.relaxation.FieldConcealmentRules
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout

/** What the record walk drops or rewrites while extracting references. */
class ProjectionRules(
    val concealment: FieldConcealmentRules = FieldConcealmentRules(),
    /** Drop `java.lang.ref.Reference` mechanics fields (referent, queue, next, discovered). */
    val filterReferenceMechanics: Boolean = true,
)

/** Cross-chunk extraction counters; merged, not contended. */
class ExtractionCounters {
    var rawNonNullRefs = 0L
    var classTargetRefs = 0L
    var danglingRefs = 0L
    var mechanicsDropped = 0L
    var concealedDropped = 0L
    var projectionDropped = 0L
    var dedupDropped = 0L
    var mainEmitted = 0L
    var excludedInstanceRefs = 0L
    var truncatedShallow = 0L
    var layoutMismatches = 0L

    fun add(other: ExtractionCounters) {
        rawNonNullRefs += other.rawNonNullRefs
        classTargetRefs += other.classTargetRefs
        danglingRefs += other.danglingRefs
        mechanicsDropped += other.mechanicsDropped
        concealedDropped += other.concealedDropped
        projectionDropped += other.projectionDropped
        dedupDropped += other.dedupDropped
        mainEmitted += other.mainEmitted
        excludedInstanceRefs += other.excludedInstanceRefs
        truncatedShallow += other.truncatedShallow
        layoutMismatches += other.layoutMismatches
    }

    override fun toString(): String =
        "raw=$rawNonNullRefs class=$classTargetRefs dangling=$danglingRefs " +
            "mechanics=$mechanicsDropped concealed=$concealedDropped " +
            "projDropped=$projectionDropped dedup=$dedupDropped main=$mainEmitted " +
            "excludedRefs=$excludedInstanceRefs layoutMismatch=$layoutMismatches"
}

/**
 * Per-object flag bits. LEAF and EXCLUDED are decided by extraction;
 * LIVE, SCOPE and ROOT_SEED are written by selection into the same byte.
 */
internal object ObjectFlags {
    const val LEAF = 1
    const val EXCLUDED = 2
    const val LIVE = 4
    const val SCOPE = 8
    const val ROOT_SEED = 16
}

/**
 * Class table of the extracted graph. Graph classes are store classes merged
 * by name (a dump may carry several class dumps per name).
 */
class GraphClassTable internal constructor(
    val names: List<ClassName>,
    /** Modeled instance shallow size per graph class; `-1` for array classes. */
    val shallowSizes: LongArray,
    /** Store class indices behind each graph class. */
    internal val storeClassesByGraphClass: Array<IntArray>,
    /** Graph classes containing instances with per-object shallow overrides. */
    internal val instanceShallowOverrides: BooleanArray,
) {
    val size: Int
        get() = names.size

    fun indexOf(name: String): Int = names.indexOfFirst { it.name == name }
}

/**
 * Array shallow sizes as unsorted off-heap `(denseId, shallow)` pairs; only
 * arrays carry them, instances use the per-class model.
 */
internal class ShallowOverrideBuffers(
    private val arena: Arena,
    private val buffers: Array<IntBlockBuffer>,
) {
    /** Copies the pairs to the heap as sorted packed longs and frees the buffers. */
    fun toSortedPairs(): LongArray {
        var pairCount = 0L
        for (buffer in buffers) pairCount += buffer.size / 2
        require(pairCount <= Int.MAX_VALUE) { "too many shallow overrides" }
        val pairs = LongArray(pairCount.toInt())
        var pairFill = 0
        for (buffer in buffers) {
            var pendingKey = 0L
            var haveKey = false
            buffer.forEach { value ->
                if (!haveKey) {
                    pendingKey = value.toLong() shl 32
                    haveKey = true
                } else {
                    pairs[pairFill++] = pendingKey or (value.toLong() and 0xFFFFFFFFL)
                    haveKey = false
                }
            }
        }
        arena.close()
        java.util.Arrays.parallelSort(pairs)
        return pairs
    }
}

/**
 * Every object of the dump with its projected references: CSR rows over all
 * `objectCount` objects, dead ones included, each target flagged structural
 * or liveness-only. Plus per-object class and shallow size, the root and
 * class-static references, and the extraction counters.
 *
 * Target encoding: bit 31 ([EXTRA_FLAG]) marks a liveness-only edge that
 * never enters the structural graph; bit 30 ([PROJ_FLAG]) marks a
 * projection-dropped edge that class-scope selection still traverses; the
 * remaining bits are a dense id or `classTargetBase + store class index`.
 *
 * [select] takes ownership: it compacts the rows in place and hands the graph
 * arena to the resulting [HeapGraph].
 */
class ObjectReferences internal constructor(
    val objectCount: Int,
    val classTargetBase: Int,
    /** Owns [classIdx] and [flags]; released at the end of selection. */
    internal val metaArena: Arena,
    /** Owns [offsets] and [targets]; handed to the selected graph. */
    internal val graphArena: Arena,
    /** u32 row offsets, `objectCount + 1` entries. */
    internal val offsets: MemorySegment,
    /** u32 encoded targets. */
    internal val targets: MemorySegment,
    /** u16 graph class index per object. */
    internal val classIdx: MemorySegment,
    /** Flag byte per object, see [ObjectFlags]. */
    internal val flags: MemorySegment,
    internal val shallowOverrides: ShallowOverrideBuffers,
    val graphClasses: GraphClassTable,
    val classes: HprofClassStore,
    /** Encoded GC-root targets. */
    val roots: IntArray,
    /** Encoded targets retained by each store class. */
    val classStatics: Array<IntArray>,
    val counters: ExtractionCounters,
) {
    internal fun flagsOf(denseId: Int): Int =
        flags.get(ValueLayout.JAVA_BYTE, denseId.toLong()).toInt()

    internal fun setFlag(denseId: Int, bit: Int) {
        val current = flags.get(ValueLayout.JAVA_BYTE, denseId.toLong()).toInt()
        flags.set(ValueLayout.JAVA_BYTE, denseId.toLong(), (current or bit).toByte())
    }

    internal fun markLive(denseId: Int, markScope: Boolean): Boolean {
        val current = flagsOf(denseId)
        if (current and ObjectFlags.LIVE != 0) return false
        var updated = current or ObjectFlags.LIVE
        if (markScope && current and ObjectFlags.EXCLUDED == 0) updated = updated or ObjectFlags.SCOPE
        flags.set(ValueLayout.JAVA_BYTE, denseId.toLong(), updated.toByte())
        return true
    }

    internal fun classIndexOf(denseId: Int): Int =
        classIdx.get(ValueLayout.JAVA_SHORT_UNALIGNED, denseId.toLong() shl 1).toInt() and 0xFFFF

    internal fun rowStart(denseId: Int): Long = offsets.get(U32, denseId.toLong() shl 2).toLong()

    internal fun rowEnd(denseId: Int): Long = offsets.get(U32, (denseId.toLong() + 1) shl 2).toLong()

    internal fun targetAt(flatIndex: Long): Int = targets.get(U32, flatIndex shl 2)

    companion object {
        /** Bit 31: liveness-only edge, stripped from the structural graph. */
        const val EXTRA_FLAG = 1 shl 31

        /** Bit 30: projection-dropped edge, traversed by class-scope selection. */
        const val PROJ_FLAG = 1 shl 30

        const val TARGET_MASK = PROJ_FLAG - 1

        private val U32 = ValueLayout.JAVA_INT_UNALIGNED
    }
}
