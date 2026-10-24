package shapes

import java.util.BitSet

/**
 * Exact structural collapse (SHAPES.md): collapse the object graph reachable
 * from all instances of a class into a shape-DAG, where each node groups
 * structurally identical objects. Everything served is exact.
 *
 * Pipeline (single-threaded v1; parallel phases in the spec are scale
 * optimizations, not semantics):
 *   1. reachability from roots over outbound refs
 *   2. SCC (iterative Tarjan) → condensation DAG
 *   3. bottom-up cons over the condensation (children consed before parents —
 *      Tarjan emits components in reverse topological order)
 *   4. aggregates per shape
 *
 * Determinism: cons order is fully determined by the input; public shape ids
 * are assigned after the merge by sorting on the 128-bit digest.
 */

enum class ShapeKind { LEAF, NODE, SCC, SCC_UNIQUE }

/** One edge in a shape key / shape-DAG. Multiplicity = refs held by ONE member. */
data class Edge(val child: Shape, val mult: Int)

/** Canonical SCC member (SHAPES.md "SCC node"): neighbor identity by color rank. */
data class SccMember(
    val className: String,
    /** refs into the same SCC, per member (with multiplicity). */
    val internalCount: Int,
    /** sorted color ranks of internal neighbors — the isomorphism certificate. */
    val internalRanks: List<Int>,
    /** external edges of this member (identical for all members of a merged SCC). */
    val external: List<Edge>,
)

class Shape internal constructor(
    val internalId: Int,
    val kind: ShapeKind,
    /** class for LEAF/NODE, null for SCC kinds. */
    val className: String?,
    /** graph class-table index for LEAF/NODE (the cons key), -1 for SCC kinds. */
    val clsIdx: Int,
    /**
     * Per-member external edges, packed: (childInternalId shl 32) | mult,
     * sorted by (child digest, childId) — the cons key for LEAF/NODE and the
     * edge list for the emitted shape-DAG (empty for LEAF and SCC_UNIQUE).
     */
    val packedKids: LongArray,
    /** SCC_UNIQUE only: totals over all members (members are not isomorphic). */
    val edges: List<Edge>,
    /** SCC payload: canonical member list (SCC) or actual members (SCC_UNIQUE).
     *  For a mega SCC_UNIQUE this is TRUNCATED to the first few members (the
     *  label needs nothing more) — [membersTotal] then holds the real count. */
    val members: List<SccMember>?,
    val digest: Digest128,
    /** set-children SCC: [members] is the WL-color quotient, not the full
     *  member list — instance accounting counts quotient-size per instance. */
    val quotient: Boolean = false,
    /** SCC_UNIQUE with truncated [members]: real member count (0 = not truncated). */
    val membersTotal: Int = 0,
    /** SCC_UNIQUE with truncated [members]: Σ per-member internal edges. */
    val internalEdgesTotal: Int = 0,
)

// ---- cons: exact structural equality via interned child shape ids, never ----
// ---- via the 128-bit digest. Open-addressed map over packed keys: at      ----
// ---- 10M+ objects, per-lookup key allocations were the top cost (JFR).    ----

private const val PACK_EMPTY_MULT_MASK = 0xFFFFFFFFL

private fun packKid(childId: Int, mult: Int): Long =
    (childId.toLong() shl 32) or (mult.toLong() and PACK_EMPTY_MULT_MASK)

private fun packChild(k: Long): Int = (k ushr 32).toInt()

private fun packMult(k: Long): Int = k.toInt()

private data class Kid(val childId: Int, val mult: Int)

private data class MemLk(val cls: String, val internalRanks: List<Int>, val ext: List<Kid>)

private data class SccLk(val members: List<MemLk>)

// ---- public result ----

class ShapeEntry(
    val id: Int,
    val digest: Digest128,
    val kind: ShapeKind,
    val label: String,
    val instanceCount: Long,
    val shallowTotal: Long,
    val shallowMin: Long,
    val shallowMax: Long,
    /** members that are instances of the queried class. */
    val rootCount: Long,
    /** objects per collapsed instance: >1 only for SCC kinds. */
    val sccInstanceSize: Int,
    /** refs within the SCC, per collapsed instance (0 for LEAF/NODE). */
    val internalEdgesPerInstance: Int,
    /** false only for SCC_UNIQUE: its edges are totals, not per-member. */
    val edgesPerMember: Boolean,
)

data class EdgeEntry(val shapeId: Int, val childShapeId: Int, val mult: Long)

class ShapeTable(
    val reachableCount: Int,
    val entries: List<ShapeEntry>,
    val edges: List<EdgeEntry>,
    /** shapeId -> number of queried-class instances with that shape. */
    val rootsHistogram: Map<Int, Long>,
) {
    fun edgesOf(shapeId: Int): List<EdgeEntry> = edges.filter { it.shapeId == shapeId }
}

class ShapesAnalyzer @JvmOverloads constructor(
    private val g: ObjectGraph,
    /** class relaxation rules (Relax.kt): class name -> CLASS_ONLY | SET_CHILDREN. */
    private val relax: Map<String, Int> = emptyMap(),
) {

    /** phase timings (reach/scc/cons/aggregate) — Main wires ::println so a
     *  minutes-long analysis reports progress instead of sitting silent. */
    var progress: (String) -> Unit = {}

    /** what relaxation concealed, exactly (Relax.kt) — read after analyze(). */
    val relaxStats = RelaxStats()

    // class-table index -> relax level (0 = exact); empty when no rules
    private val levelOfClass: IntArray by lazy(LazyThreadSafetyMode.NONE) {
        Relax.levels(g.classNames, relax)
    }

    private fun levelOf(obj: Int): Int =
        if (levelOfClass.isEmpty()) 0 else levelOfClass[g.classIdx[obj].code]

    /** class-table index -> relax level (0 = exact), for verifyEdgeAccounting. */
    fun relaxLevels(): IntArray = levelOfClass

    fun analyze(roots: IntArray, wholeGraph: Boolean = false): ShapeTable {
        relaxStats.reset()
        var t = System.nanoTime()
        val (inR, rList, rCount) = scopeForAnalyze(roots, wholeGraph)
        progress("reach: $rCount objects in ${(System.nanoTime() - t) / 1_000_000} ms")
        t = System.nanoTime()
        val (compEnc, comps) = scc(inR, rList, rCount)
        progress("scc: ${comps.count} components in ${(System.nanoTime() - t) / 1_000_000} ms")
        t = System.nanoTime()
        val (shapes, shapeOfComp) = cons(compEnc, comps)
        progress("cons: ${shapes.size} shapes in ${(System.nanoTime() - t) / 1_000_000} ms")
        t = System.nanoTime()
        // no shapeOfObj array (4 B/object): aggregate resolves via shapeOfComp
        val table = aggregate(shapes, compEnc, shapeOfComp, comps, roots, rCount)
        progress("aggregate: ${(System.nanoTime() - t) / 1_000_000} ms")
        return table
    }

    private fun scopeForAnalyze(roots: IntArray, wholeGraph: Boolean): Triple<BitSet, IntArray?, Int> {
        // mat-extract already selected the scope. Projection may remove paths
        // to an object; keep its counts/bytes without inventing extra roots.
        if (wholeGraph) return Triple(BitSet(g.size).apply { set(0, g.size) }, null, g.size)
        return reachForAnalyze(roots)
    }

    private fun reachForAnalyze(roots: IntArray): Triple<BitSet, IntArray?, Int> {
        val (inR, arr) = reach(roots)
        // whole-graph reach (always the case on the GRPH path): scc iterates
        // 0..n-1 instead, and the 4 B/object id array must become garbage
        // HERE — a method-scope local in analyze() would keep it alive
        // through the scc/cons peaks
        return Triple(inR, if (arr.size == g.size) null else arr, arr.size)
    }

    /** analyze() + the internals, for Verify.verifyEdgeAccounting (inv3 audit). */
    class DebugResult(
        val table: ShapeTable,
        val compEnc: IntArray,
        val comps: Comps,
        val shapes: List<Shape>,
        val shapeOfComp: IntArray,
        val rCount: Int,
    )

    fun analyzeDebug(roots: IntArray, wholeGraph: Boolean = false): DebugResult {
        relaxStats.reset()
        val (inR, rList, rCount) = scopeForAnalyze(roots, wholeGraph)
        val (compEnc, comps) = scc(inR, rList, rCount)
        val (shapes, shapeOfComp) = cons(compEnc, comps)
        val table = aggregate(shapes, compEnc, shapeOfComp, comps, roots, rCount)
        return DebugResult(table, compEnc, comps, shapes, shapeOfComp, rCount)
    }

    // ---- phase 1: reachability ----

    private fun reach(roots: IntArray): Pair<BitSet, IntArray> {
        val inR = BitSet(g.size)
        // rList doubles as the BFS queue: discovered == queued (no separate
        // stack array, no copy unless R is smaller than the graph)
        val rList = IntArray(g.size)
        var head = 0
        var tail = 0
        for (r in roots) if (!inR.get(r)) {
            inR.set(r); rList[tail++] = r
        }
        while (head < tail) {
            val v = rList[head++]
            g.forEachRef(v) { w ->
                if (!inR.get(w)) {
                    inR.set(w); rList[tail++] = w
                }
            }
        }
        return inR to if (tail == rList.size) rList else rList.copyOf(tail)
    }

    // ---- phase 2: SCC, iterative Tarjan over R ----

    /**
     * Component members without per-component object lists (boxing kills the
     * heap at 10M+ objects) AND without a full CSR over every component: at
     * 100M+ mostly-singleton components a CSR (start[count+1] + members[n] +
     * fill cursor) costs ~18 B/component + 4 B/object. Here: singleton
     * members inline in [first], only multi-member components spill into the
     * (small) multiMembers CSR — ~8 B/component total.
     */
    class Comps(
        val count: Int,
        val size: IntArray,        // members per component
        val first: IntArray,       // size==1 -> the member; size>1 -> -(row+1) into the multi CSR
        val multiStart: IntArray,  // multi-comp row -> offset in multiMembers
        val multiMembers: IntArray,
    ) {
        inline fun forEachMember(c: Int, block: (Int) -> Unit) {
            if (size[c] == 1) {
                block(first[c])
                return
            }
            val row = -first[c] - 1
            for (i in multiStart[row] until multiStart[row + 1]) block(multiMembers[i])
        }
    }

    private fun scc(inR: BitSet, rList: IntArray?, rCount: Int): Pair<IntArray, Comps> {
        val n = g.size
        // ONE array for both the dfs index and the component ids (4 B/object
        // saved): -1 = unvisited, >= 0 = dfs index (only while on the dfs
        // path), <= -2 = assigned component encoded as -(comp + 2) — a node's
        // dfs index is dead the moment its component is assigned, so the two
        // never overlap. Decode with compOfEnc().
        val index = IntArray(n) { -1 }
        // iteration order over R; null rList == identity (R is the whole graph)
        fun rAt(i: Int): Int = rList?.get(i) ?: i
        // no low[n] array (4 B/object): the low-link is only ever read for
        // nodes on the current dfs path (back edges use index[w]), so it
        // lives in the per-frame lowStack below.
        // hand-rolled bitset, NOT java.util.BitSet: BitSet.clear(i) calls
        // recalculateWordsInUse() (O(words) scan) — that alone was ~99% of scc
        // time on real heaps. XOR flip is O(1) with no such trap.
        val onStackBits = LongArray((n ushr 6) + 1)
        fun isOnStack(i: Int) = (onStackBits[i ushr 6] ushr (i and 63)) and 1L != 0L
        fun flipStack(i: Int) {
            onStackBits[i ushr 6] = onStackBits[i ushr 6] xor (1L shl (i and 63))
        }
        // dfs stacks grow on demand (1.5x: the copyOf transient at 2x is
        // itself a peak); real dfs depth is usually thousands, not n
        fun grow(a: IntArray, need: Int) =
            if (need <= a.size) a else a.copyOf(maxOf(need, a.size + (a.size ushr 1)))
        var tarjanStack = IntArray(1 shl 16)
        var tsp = 0
        var nodeStack = IntArray(1 shl 16)
        var lowStack = IntArray(1 shl 16)
        var ptrStack = IntArray(1 shl 16)
        var nsp = 0
        var next = 0
        var compCount = 0

        for (ri in 0 until rCount) {
            val s = rAt(ri)
            if (index[s] != -1) continue
            index[s] = next++
            tarjanStack = grow(tarjanStack, tsp + 1)
            tarjanStack[tsp++] = s; flipStack(s)
            nodeStack = grow(nodeStack, nsp + 1)
            lowStack = grow(lowStack, nsp + 1)
            ptrStack = grow(ptrStack, nsp + 1)
            nodeStack[nsp] = s; lowStack[nsp] = index[s]; ptrStack[nsp] = 0; nsp++
            while (nsp > 0) {
                val v = nodeStack[nsp - 1]
                val p = ptrStack[nsp - 1]
                if (g.refOffsets[v] + p < g.refOffsets[v + 1]) {
                    ptrStack[nsp - 1] = p + 1
                    val w = g.refs[g.refOffsets[v] + p]
                    if (!inR.get(w)) continue          // unreachable from roots: outside scope
                    if (index[w] == -1) {
                        index[w] = next++
                        tarjanStack = grow(tarjanStack, tsp + 1)
                        tarjanStack[tsp++] = w; flipStack(w)
                        nodeStack = grow(nodeStack, nsp + 1)
                        lowStack = grow(lowStack, nsp + 1)
                        ptrStack = grow(ptrStack, nsp + 1)
                        nodeStack[nsp] = w; lowStack[nsp] = index[w]; ptrStack[nsp] = 0; nsp++
                    } else if (isOnStack(w)) {
                        if (index[w] < lowStack[nsp - 1]) lowStack[nsp - 1] = index[w]
                    }
                } else {
                    val lowV = lowStack[nsp - 1]
                    nsp--
                    if (nsp > 0 && lowV < lowStack[nsp - 1]) lowStack[nsp - 1] = lowV
                    if (lowV == index[v]) {
                        while (true) {
                            val w = tarjanStack[--tsp]
                            flipStack(w)
                            index[w] = -(compCount + 2)   // dfs index is dead now — encode the component
                            if (w == v) break
                        }
                        compCount++
                    }
                }
            }
        }

        // member grouping: singleton comps inline their member in first[];
        // only multi-member comps (rare — real cycles) spill into a small CSR
        val compSize = IntArray(compCount)
        for (ri in 0 until rCount) compSize[compOfEnc(index, rAt(ri))]++
        var multiCount = 0
        for (c in 0 until compCount) if (compSize[c] > 1) multiCount++
        val multiStart = IntArray(multiCount + 1)
        val first = IntArray(compCount) { -1 }
        run {
            var row = 0
            for (c in 0 until compCount) if (compSize[c] > 1) {
                first[c] = -(row + 1)
                multiStart[row + 1] = multiStart[row] + compSize[c]
                row++
            }
        }
        val multiMembers = IntArray(multiStart[multiCount])
        val cursor = multiStart.copyOf()
        for (ri in 0 until rCount) {
            val o = rAt(ri)
            val c = compOfEnc(index, o)
            if (compSize[c] == 1) first[c] = o
            else multiMembers[cursor[-first[c] - 1]++] = o
        }
        return index to Comps(compCount, compSize, first, multiStart, multiMembers)
    }

    /** Decode the component id packed into scc's index array (see scc). */
    private inline fun compOfEnc(enc: IntArray, o: Int): Int = -enc[o] - 2

    // ---- phase 3: bottom-up cons over the condensation DAG ----
    // Tarjan pops SCCs in reverse topological order, so component ids are a
    // reverse topo sort: every external child of component c has id < c and
    // is consed before c (level-synchronous processing in the spec collapses
    // to plain id order in a single-threaded v1).

    /**
     * Open-addressed cons-map: slot -> internal shape id. Lookup compares the
     * full structural key (class-table index + packed kids) against the stored
     * shape — zero allocation on hits, exactness is structural, never digest.
     */
    private class ConsMap {
        private var slots = IntArray(1 shl 16) { -1 }
        private var size = 0
        // full key hash per shape: a probe step checks this int BEFORE
        // kidsEqual — comparing a multi-thousand-kid key per probed slot was
        // the cons hot spot on bucket-heavy heaps (jstack: 25 min in intern)
        private var keyHashes = IntArray(4096)

        private fun kidsEqual(stored: LongArray, kids: LongArray, n: Int): Boolean {
            if (stored.size != n) return false
            for (i in 0 until n) if (stored[i] != kids[i]) return false
            return true
        }

        var maxProbe = 0   // diagnostic: longest probe chain ever taken
        private var probeReportAt = 50_000

        private fun hash(clsIdx: Int, kids: LongArray, n: Int): Int {
            var h = clsIdx * 31 + n
            for (i in 0 until n) {
                val k = kids[i]
                h = 31 * h + (k xor (k ushr 32)).toInt()
            }
            // Murmur3 fmix32 finalizer: the probe uses the LOW bits of the
            // hash — a multiply-only mixer leaves them linear in the inputs,
            // which clumped the map into megaprobes on real heaps (the probe
            // walked ~2M slots/lookup at 1M shapes; observed in jstack)
            h = h xor (h ushr 16)
            h *= 0x85ebca6b.toInt()
            h = h xor (h ushr 13)
            h *= 0xc2b2ae35.toInt()
            h = h xor (h ushr 16)
            return h
        }

        /** probe stride for double hashing: odd, derived from different bits
         *  — linear probing's primary clustering is what turns unlucky
         *  collisions into table-long runs */
        private fun stride(h: Int, mask: Int): Int = ((h ushr 16) or 1) and mask

        fun intern(shapes: ArrayList<Shape>, clsIdx: Int, clsName: String, kind: ShapeKind,
                   kids: LongArray, nKids: Int): Shape {
            if ((size + 1) * 4 >= slots.size * 3) {
                val grown = IntArray(slots.size * 2) { -1 }
                for (s in shapes) {
                    // SCC shapes are never interned (clsIdx = -1) and slots
                    // only serve LEAF/NODE keys — keep them out of the table
                    if (s.clsIdx < 0) continue
                    val h = keyHashes[s.internalId]
                    var i = h and (grown.size - 1)
                    val st = stride(h, grown.size - 1)
                    while (grown[i] >= 0) i = (i + st) and (grown.size - 1)
                    grown[i] = s.internalId
                }
                slots = grown
            }
            val h = hash(clsIdx, kids, nKids)
            var i = h and (slots.size - 1)
            val st = stride(h, slots.size - 1)
            var probe = 0
            while (true) {
                probe++
                if (probe > maxProbe) {
                    maxProbe = probe
                    if (probe >= probeReportAt) {
                        probeReportAt *= 2
                        println("LONGPROBE $probe: size=$size slots=${slots.size} " +
                            "cls=$clsName($clsIdx) nKids=$nKids " +
                            "kids=${kids.take(minOf(6, nKids)).joinToString { k -> "${k ushr 32}x${k and 0xffffffff}" }}")
                    }
                }
                val s = slots[i]
                if (s < 0) {
                    val packed = kids.copyOf(nKids)
                    val shape = Shape(
                        shapes.size, kind, clsName, clsIdx, packed, emptyList(), null,
                        digestOf {
                            tag(if (kind == ShapeKind.LEAF) 1 else 2)
                            str(clsName)
                            i32(packed.size)
                            for (k in packed) {
                                digest(shapes[packChild(k)].digest); i32(packMult(k))
                            }
                        },
                    )
                    shapes.add(shape)
                    slots[i] = shape.internalId
                    if (shape.internalId >= keyHashes.size)
                        keyHashes = keyHashes.copyOf(maxOf(shape.internalId + 1, keyHashes.size * 2))
                    keyHashes[shape.internalId] = h
                    size++
                    return shape
                }
                val cand = shapes[s]
                if (keyHashes[s] == h && cand.clsIdx == clsIdx &&
                    kidsEqual(cand.packedKids, kids, nKids)) return cand
                i = (i + st) and (slots.size - 1)
            }
        }
    }

    private fun cons(compEnc: IntArray, comps: Comps): Pair<List<Shape>, IntArray> {
        val shapes = ArrayList<Shape>()
        val consMap = ConsMap()
        val sccConsMap = HashMap<SccLk, Shape>()
        // internal shape id per component (-1 = not consed yet); an Array<Shape?>
        // here was 8 B/component of pure reference fat at 100M+ components
        val shapeOfComp = IntArray(comps.count) { -1 }

        // scratch buffers for kid building (reused; never returned)
        var kidIds = IntArray(64)      // child internal ids per ref
        var kidPacked = LongArray(64)

        fun kidDigestCmp(a: Int, b: Int): Int {
            val c = shapes[a].digest.compareTo(shapes[b].digest)
            return if (c != 0) c else a.compareTo(b) // childId tiebreak: full determinism
        }

        fun kidCmp(a: Kid, b: Kid): Int {
            val c = kidDigestCmp(a.childId, b.childId)
            return if (c != 0) c else a.mult.compareTo(b.mult)
        }

        for (c in 0 until comps.count) {
            val memberCount = comps.size[c]
            val shape: Shape
            var selfLoop = false
            if (memberCount == 1) {
                val o = comps.first[c]
                g.forEachRef(o) { if (it == o) selfLoop = true }
            }
            if (memberCount == 1 && !selfLoop) {
                // acyclic single node
                val obj = comps.first[c]
                val rc = g.refCount(obj)
                val lvl = levelOf(obj)
                if (lvl > 0) relaxStats.relaxedObjects++
                if (rc == 0 || lvl == Relax.CLASS_ONLY) {
                    // class-only relaxation: children stay in scope (conse
                    // normally) but are not part of this object's key — every
                    // instance of the class conses to one LEAF regardless of
                    // state below it. The refs are concealed, not removed.
                    if (lvl == Relax.CLASS_ONLY) relaxStats.classOnlyHiddenEdges += rc
                    shape = consMap.intern(shapes, g.classIdx[obj].code, g.className(obj), ShapeKind.LEAF,
                        kidPacked, 0)
                } else {
                    if (rc > kidIds.size) {
                        kidIds = IntArray(maxOf(rc, kidIds.size * 2))
                        kidPacked = LongArray(kidIds.size)
                    }
                    var m = 0
                    g.forEachRef(obj) { w ->
                        kidIds[m++] = shapeOfComp[compOfEnc(compEnc, w)].also {
                            if (it < 0) error("child comp ${compOfEnc(compEnc, w)} consed after parent — topo order violated")
                        }
                    }
                    // sort by (child digest, childId), then run-length to packed (id, mult)
                    if (m <= 64) {
                        for (i in 1 until m) {
                            val v = kidIds[i]
                            var j = i - 1
                            while (j >= 0 && kidDigestCmp(kidIds[j], v) > 0) {
                                kidIds[j + 1] = kidIds[j]; j--
                            }
                            kidIds[j + 1] = v
                        }
                    } else {
                        val sorted = kidIds.copyOf(m).sortedWith(::kidDigestCmp)
                        sorted.forEachIndexed { i, v -> kidIds[i] = v }
                    }
                    val setKids = SET_SEMANTICS || lvl == Relax.SET_CHILDREN
                    var nKids = 0
                    var prev = -1
                    var mult = 0
                    for (i in 0 until m) {
                        if (kidIds[i] == prev) mult++
                        else {
                            if (prev >= 0) kidPacked[nKids++] = packKid(prev, if (setKids) 1 else mult)
                            prev = kidIds[i]; mult = 1
                        }
                    }
                    if (prev >= 0) kidPacked[nKids++] = packKid(prev, if (setKids) 1 else mult)
                    if (lvl == Relax.SET_CHILDREN) relaxStats.setChildrenHiddenEdges += m - nKids
                    shape = consMap.intern(shapes, g.classIdx[obj].code, g.className(obj), ShapeKind.NODE,
                        kidPacked, nKids)
                }
            } else if (memberCount > MAX_SCC_MERGE) {
                // mega SCC: no 4 B/member copy (568 MB at 142M members — the
                // OOM spot) — uniqueBig sorts the shared CSR segment in place;
                // member order inside a component is semantically irrelevant
                val row = -comps.first[c] - 1
                shape = consScc(comps.multiMembers, comps.multiStart[row], memberCount,
                    compEnc, shapeOfComp, shapes, sccConsMap, ::kidCmp)
            } else {
                val members = IntArray(memberCount)
                var p = 0
                comps.forEachMember(c) { m -> members[p++] = m }
                shape = consScc(members, 0, memberCount, compEnc, shapeOfComp, shapes, sccConsMap, ::kidCmp)
            }
            shapeOfComp[c] = shape.internalId
            if (c and 0x7FFFFF == 0x7FFFFF) {   // every 8M comps: where the memory goes
                val rt = Runtime.getRuntime()
                progress("cons: ${c + 1}/${comps.count} comps, ${shapes.size} shapes, " +
                    "${(rt.totalMemory() - rt.freeMemory()) / 1_000_000} MB used, " +
                    "maxProbe=${consMap.maxProbe}")
            }
        }
        return shapes to shapeOfComp
    }

    /** SHAPES.md "SCC node": color refinement; merge on the WL-fixpoint
     *  signature (automorphism-invariant — discretization not required). */
    private fun consScc(
        members: IntArray,
        off: Int,                // members live in members[off .. off+n)
        n: Int,
        compEnc: IntArray,
        shapeOfComp: IntArray,   // internal shape id per comp, -1 = not consed
        shapes: ArrayList<Shape>,
        consMap: HashMap<SccLk, Shape>,
        kidCmp: (Kid, Kid) -> Int,
    ): Shape {

        // |SCC| > MAX_SCC_MERGE -> SCC_UNIQUE, computed WITHOUT the refinement
        // structures: a mega-SCC never merges, so the boxed per-member
        // local/extKids/intNeigh maps (~200 B/member) would be allocated only
        // to be discarded — on a 2.6M-member service-graph SCC that alone
        // OOM'd the analyzer after an hour of GC thrash.
        if (n > MAX_SCC_MERGE) {
            members.sort(off, off + n)   // in place — no copy
            var internalEdgesTotal = 0
            val totals = HashMap<Int, Int>()   // child internal shape id -> total mult
            for (i in off until off + n) {
                var ic = 0
                g.forEachRef(members[i]) { w ->
                    if (members.binarySearch(w, off, off + n) >= 0) ic++
                    else totals.merge(shapeOfComp[compOfEnc(compEnc, w)], 1, Int::plus)
                }
                internalEdgesTotal += ic
            }
            // members list TRUNCATED to the label's needs: 2.6M boxed SccMember
            // (~100 MB) would be allocated for a label that shows 8 class names
            val memPayload = (off until minOf(off + 8, off + n)).map { o ->
                SccMember(g.className(members[o]), 0, emptyList(), emptyList())
            }
            return Shape(
                shapes.size, ShapeKind.SCC_UNIQUE, null, -1, LongArray(0),
                totals.map { (id, m) -> Edge(shapes[id], m) }.sortedWith { a, b ->
                    a.child.digest.compareTo(b.child.digest)
                },
                memPayload,
                digestOf { tag(4); i64(members[off].toLong()); i32(n) },
                membersTotal = n,
                internalEdgesTotal = internalEdgesTotal,
            ).also { shapes.add(it) }
        }

        val local = HashMap<Int, Int>(n * 2)
        for (i in 0 until n) local[members[off + i]] = i

        // per-member external kids and internal neighbors (local indices, with multiplicity)
        val lvls = IntArray(n) { levelOf(members[off + it]) }
        // set-children on ANY member makes the whole SCC set-semantic: an SCC
        // is one strongly-connected object, and the multiplicity of identical
        // members inside it (e.g. 7 ElementShadows per project dependency of
        // a configuration) is exactly the "children list" fact set-children
        // discards. The SCC is keyed by its WL-color quotient then; totals
        // and instance counts stay exact, concealed edges are accounted.
        val anySet = SET_SEMANTICS || lvls.any { it == Relax.SET_CHILDREN }
        val extKids = Array(n) { mutableListOf<Kid>() }
        val intNeigh = Array(n) { mutableListOf<Int>() }
        for (i in 0 until n) {
            val lvl = lvls[i]
            if (lvl > 0) relaxStats.relaxedObjects++
            if (lvl == Relax.CLASS_ONLY) {
                // class-only: external children are concealed (internal SCC
                // wiring stays exact — it is the SCC's own structure)
                var hidden = 0
                g.forEachRef(members[off + i]) { w ->
                    val li = local[w]
                    if (li != null) intNeigh[i].add(li) else hidden++
                }
                relaxStats.classOnlyHiddenEdges += hidden
                extKids[i] = mutableListOf()
                continue
            }
            val byShape = LinkedHashMap<Int, Int>()   // child internal shape id -> mult
            g.forEachRef(members[off + i]) { w ->
                val li = local[w]
                if (li != null) intNeigh[i].add(li)
                else byShape.merge(shapeOfComp[compOfEnc(compEnc, w)], 1, Int::plus)
            }
            extKids[i] = byShape.map { (id, m) ->
                if (anySet) relaxStats.setChildrenHiddenEdges += m - 1
                Kid(id, if (anySet) 1 else m)
            }.sortedWith(kidCmp).toMutableList()
        }

        // color refinement: color = (class, external child shapes, internal neighbor colors)
        data class Color(val cls: String, val ext: List<Kid>, val internal: List<Int>)

        fun colorCmp(a: Color, b: Color): Int {
            var c = a.cls.compareTo(b.cls)
            if (c != 0) return c
            c = a.ext.size.compareTo(b.ext.size)
            if (c != 0) return c
            for (i in a.ext.indices) {
                c = kidCmp(a.ext[i], b.ext[i])
                if (c != 0) return c
            }
            c = a.internal.size.compareTo(b.internal.size)
            if (c != 0) return c
            for (i in a.internal.indices) {
                c = a.internal[i].compareTo(b.internal[i])
                if (c != 0) return c
            }
            return 0
        }

        var colors = Array(n) { i -> Color(g.className(members[off + i]), extKids[i], emptyList()) }
        var ranks = IntArray(n)
        var prevDistinct = -1
        while (true) {
            val distinct = colors.distinct().sortedWith(::colorCmp)
            val rankOf = HashMap<Color, Int>(distinct.size * 2)
            distinct.forEachIndexed { i, col -> rankOf[col] = i }
            ranks = IntArray(n) { rankOf[colors[it]]!! }
            if (distinct.size == prevDistinct) break // fixpoint: partition stopped refining
            prevDistinct = distinct.size
            if (distinct.size == n) break            // fully discretized
            colors = Array(n) { i ->
                Color(colors[i].cls, colors[i].ext, intNeigh[i].map { ranks[it] }.let { if (anySet) it.distinct() else it }.sorted())
            }
        }

        // Merge on the WL-fixpoint signature: the multiset of per-member
        // (class, internal-neighbor color ranks, external child shapes).
        // This is automorphism-invariant, so it is well-defined even when
        // refinement did NOT fully discretize (symmetric members, e.g. a
        // 2-cycle of identical LinkedHashMap entries — those used to bail
        // to per-instance unique shapes: 201k duplicate shapes on
        // run-jfr-38). At the fixpoint, equal-color members have equal
        // signatures, so the sorted member list is canonical. WL-equivalence
        // is a superset of isomorphism — for heap SCCs (lists, maps, trees)
        // it is exact in practice; totals/counts stay exact regardless
        // (same-color members carry identical edges).
        val order = (0 until n).sortedWith { a, b -> colorCmp(colors[a], colors[b]) }
        val canonical = order.map { i ->
            MemLk(
                g.className(members[off + i]),
                intNeigh[i].map { ranks[it] }.let { if (anySet) it.distinct() else it }.sorted(),
                extKids[i],
            )
        }.let { pre ->
            if (!anySet) pre else {
                // set semantics: the SCC is its WL-color quotient graph.
                // Account the concealed edges per SCC INSTANCE (consScc runs
                // per instance; only the Shape is cached): a kept color's
                // internal ranks were distincted, a dropped duplicate's
                // internal + (multiplicity-normalized) external edges vanish.
                val seen = HashSet<MemLk>()
                val out = ArrayList<MemLk>(pre.size)
                for (idx in pre.indices) {
                    val m = pre[idx]
                    if (seen.add(m)) {
                        out += m
                        relaxStats.setChildrenHiddenEdges +=
                            intNeigh[order[idx]].size - m.internalRanks.size
                    } else {
                        relaxStats.setChildrenHiddenEdges +=
                            intNeigh[order[idx]].size + m.ext.size
                    }
                }
                // the dropped members are real objects concealed from the
                // artifact's count (quotient-size per instance instead of
                // the true member count) — account them like concealed edges
                relaxStats.setChildrenConcealedObjects += (pre.size - out.size).toLong()
                out
            }
        }
        val lk = SccLk(canonical)
        // "per member" edge semantics is exact only when every canonical
        // member holds the SAME external edges: a non-uniform SCC (e.g.
        // LinkedHashMap ⇄ its entrySet — map→table, entrySet→entries) gets
        // its mults doubled by the × instanceCount expansion (verify-edges
        // on a real heap: emitted 4 vs true 2). Those go to SCC_UNIQUE —
        // totals are always exact.
        // Edge emission for a merged SCC: "per member" mults are exact only
        // when every canonical member holds the SAME external edges
        // (uniform). A non-uniform SCC (e.g. LinkedHashMap ⇄ its entrySet —
        // map→table, entrySet→entries) would get mults inflated by the
        // × instanceCount expansion (verify-edges on a real heap: emitted 4
        // vs true 2). Non-uniform SCCs still MERGE (the isomorphism is real)
        // but emit per-INSTANCE totals (identical across instances by the
        // same isomorphism) — edgesPerMember=false then means
        // "× collapsedInstances", exactly like SCC_UNIQUE (which has one).
        val uniformExt = canonical.all { it.ext == canonical[0].ext }
        return consMap.getOrPut(lk) {
            val memPayload = canonical.map { m ->
                SccMember(m.cls, m.internalRanks.size, m.internalRanks,
                    m.ext.map { Edge(shapes[it.childId], it.mult) })
            }
            val totals: List<Edge> = if (uniformExt) emptyList() else {
                val t = LinkedHashMap<Shape, Int>()
                for (m in memPayload) for (e in m.external) t.merge(e.child, e.mult, Int::plus)
                t.map { (s, m) -> Edge(s, m) }.sortedWith { a, b ->
                    a.child.digest.compareTo(b.child.digest)
                }
            }
            Shape(
                shapes.size, ShapeKind.SCC, null, -1,
                // per-member external edges, identical for every member (part
                // of the color); empty for non-uniform SCCs — their edges are
                // the per-instance totals in [edges]
                if (uniformExt) memPayload.first().external
                    .map { packKid(it.child.internalId, it.mult) }.toLongArray()
                else LongArray(0),
                totals,
                memPayload,
                quotient = anySet,
                digest = digestOf {
                    tag(3); i32(canonical.size)
                    for (m in canonical) {
                        str(m.cls)
                        i32(m.internalRanks.size); for (r in m.internalRanks) i32(r)
                        i32(m.ext.size); for (k in m.ext) {
                            digest(shapes[k.childId].digest); i32(k.mult)
                        }
                    }
                },
            ).also { shapes.add(it) }
        }
    }

    // ---- phase 4: aggregates; public ids assigned post-merge by digest sort ----

    private fun aggregate(
        shapes: List<Shape>,
        compEnc: IntArray,        // object -> component, scc-encoded (for the roots histogram)
        shapeOfComp: IntArray,   // component -> internal shape id
        comps: Comps,
        roots: IntArray,
        reachableCount: Int,
    ): ShapeTable {
        val nShapes = shapes.size
        val count = LongArray(nShapes)
        val shallowSum = LongArray(nShapes)
        val shallowMin = LongArray(nShapes) { Long.MAX_VALUE }
        val shallowMax = LongArray(nShapes)
        val rootCount = LongArray(nShapes)
        val isRoot = BitSet(g.size)
        for (r in roots) isRoot.set(r)

        for (c in 0 until comps.count) {
            val sid = shapeOfComp[c]
            // quotient SCC: members differ per instance (that is the point of
            // the quotient), so count quotient-size per SCC INSTANCE —
            // instanceCount / sccInstanceSize then stays the instance count
            if (shapes[sid].quotient) count[sid] += shapes[sid].members!!.size
            comps.forEachMember(c) { o ->
                if (!shapes[sid].quotient) count[sid]++
                val sh = g.shallowOf(o)
                shallowSum[sid] += sh
                if (sh < shallowMin[sid]) shallowMin[sid] = sh
                if (sh > shallowMax[sid]) shallowMax[sid] = sh
                if (isRoot.get(o)) rootCount[sid]++
            }
        }

        val order = shapes.sortedBy { it.digest }
        val finalId = IntArray(nShapes)
        order.forEachIndexed { i, s -> finalId[s.internalId] = i }

        val entries = order.mapIndexed { id, s ->
            ShapeEntry(
                id = id,
                digest = s.digest,
                kind = s.kind,
                label = when (s.kind) {
                    ShapeKind.LEAF, ShapeKind.NODE -> s.className!!
                    ShapeKind.SCC -> "scc[${s.members!!.joinToString(",") { it.className }}]"
                    // mega-SCC labels must not embed every member class (a 2.6M-member
                    // service-graph SCC would be a megabyte of text in the artifact)
                    ShapeKind.SCC_UNIQUE -> {
                        val total = if (s.membersTotal > 0) s.membersTotal else s.members!!.size
                        "scc![${s.members!!.take(8).joinToString(",") { it.className }}" +
                            (if (total > 8) ",+${total - 8} more]" else "]")
                    }
                },
                instanceCount = count[s.internalId],
                shallowTotal = shallowSum[s.internalId],
                shallowMin = shallowMin[s.internalId],
                shallowMax = shallowMax[s.internalId],
                rootCount = rootCount[s.internalId],
                sccInstanceSize = if (s.membersTotal > 0) s.membersTotal else (s.members?.size ?: 1),
                internalEdgesPerInstance = if (s.membersTotal > 0) s.internalEdgesTotal
                    else (s.members?.sumOf { it.internalCount } ?: 0),
                edgesPerMember = s.kind != ShapeKind.SCC_UNIQUE && s.packedKids.isNotEmpty(),
            )
        }
        val edges = ArrayList<EdgeEntry>()
        for (s in shapes) {
            if (s.kind == ShapeKind.SCC_UNIQUE || s.packedKids.isEmpty()) {
                // SCC_UNIQUE: totals over all members; non-uniform merged SCC:
                // per-instance totals (LEAF simply has no edges)
                for (e in s.edges) {
                    edges.add(EdgeEntry(finalId[s.internalId], finalId[e.child.internalId], e.mult.toLong()))
                }
            } else {
                for (k in s.packedKids) {
                    edges.add(EdgeEntry(finalId[s.internalId], finalId[packChild(k)], packMult(k).toLong()))
                }
            }
        }
        val rootsHist = HashMap<Int, Long>()
        for (r in roots) {
            rootsHist.merge(finalId[shapeOfComp[compOfEnc(compEnc, r)]], 1L, Long::plus)
        }
        return ShapeTable(reachableCount, entries, edges, rootsHist)
    }

    companion object {
        /** SHAPES.md: |SCC| > cap → unique, never merged. Env-overridable for
         *  experiments: the cap exists for the ~200 B/member refinement cost
         *  — a 141M-member mega-SCC cannot afford it; mid-size SCCs can. */
        val MAX_SCC_MERGE: Int =
            System.getenv("SHAPES_MAX_SCC_MERGE")?.toInt() ?: 64

        /** EXPERIMENT: set semantics — child multiplicities collapse to 1 and an
         *  SCC is identified by its WL-color quotient graph (members of equal
         *  color merge). Counts/shallow stay exact; edge mults are NOT (>= 1). */
        val SET_SEMANTICS: Boolean = System.getenv("SHAPES_SET_SEMANTICS") == "1"
    }
}
