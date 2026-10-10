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
    /**
     * External edges. For LEAF/NODE and merged SCCs: per-member (every member
     * holds exactly these). For SCC_UNIQUE: totals over all members (members
     * are not isomorphic — that is why they were not merged).
     */
    val edges: List<Edge>,
    /** SCC payload: canonical member list (SCC) or actual members (SCC_UNIQUE). */
    val members: List<SccMember>?,
    val digest: Digest128,
)

// ---- cons-map lookup keys: exact structural tuples, equality via interned ----
// ---- child ids (Shape identity), never via the 128-bit digest.          ----

private sealed interface Lk

private data class Kid(val childId: Int, val mult: Int)

private data class LeafLk(val cls: String) : Lk

private data class NodeLk(val cls: String, val kids: List<Kid>) : Lk

private data class MemLk(val cls: String, val internalRanks: List<Int>, val ext: List<Kid>)

private data class SccLk(val members: List<MemLk>) : Lk

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

class ShapesAnalyzer(private val g: ObjectGraph) {

    fun analyze(roots: IntArray): ShapeTable {
        val (inR, rList) = reach(roots)
        val (compOf, comps) = scc(inR, rList)
        val (shapes, shapeOfComp) = cons(compOf, comps)
        val shapeOfObj = IntArray(g.size) { -1 }
        comps.forEachIndexed { c, members ->
            for (o in members) shapeOfObj[o] = shapeOfComp[c]
        }
        return aggregate(shapes, shapeOfObj, comps, roots, rList.size)
    }

    // ---- phase 1: reachability ----

    private fun reach(roots: IntArray): Pair<BitSet, IntArray> {
        val inR = BitSet(g.size)
        val stack = IntArray(g.size)
        var sp = 0
        val rList = ArrayList<Int>()
        for (r in roots) if (!inR.get(r)) {
            inR.set(r); stack[sp++] = r
        }
        while (sp > 0) {
            val v = stack[--sp]
            rList.add(v)
            for (w in g.refs[v]) if (!inR.get(w)) {
                inR.set(w); stack[sp++] = w
            }
        }
        return inR to rList.toIntArray()
    }

    // ---- phase 2: SCC, iterative Tarjan over R ----

    private fun scc(inR: BitSet, rList: IntArray): Pair<IntArray, List<List<Int>>> {
        val n = g.size
        val index = IntArray(n) { -1 }
        val low = IntArray(n)
        val compOf = IntArray(n) { -1 }
        val onStack = BitSet(n)
        val tarjanStack = IntArray(n)
        var tsp = 0
        val nodeStack = IntArray(n)
        var nsp = 0
        val ptrStack = IntArray(n)
        var psp = 0
        var next = 0
        var compCount = 0
        val comps = ArrayList<List<Int>>()

        for (s in rList) {
            if (index[s] >= 0) continue
            index[s] = next; low[s] = next++
            tarjanStack[tsp++] = s; onStack.set(s)
            nodeStack[nsp++] = s; ptrStack[psp++] = 0
            while (nsp > 0) {
                val v = nodeStack[nsp - 1]
                val p = ptrStack[psp - 1]
                if (p < g.refs[v].size) {
                    ptrStack[psp - 1] = p + 1
                    val w = g.refs[v][p]
                    if (!inR.get(w)) continue          // unreachable from roots: outside scope
                    if (index[w] < 0) {
                        index[w] = next; low[w] = next++
                        tarjanStack[tsp++] = w; onStack.set(w)
                        nodeStack[nsp++] = w; ptrStack[psp++] = 0
                    } else if (onStack.get(w)) {
                        if (index[w] < low[v]) low[v] = index[w]
                    }
                } else {
                    nsp--; psp--
                    if (nsp > 0) {
                        val u = nodeStack[nsp - 1]
                        if (low[v] < low[u]) low[u] = low[v]
                    }
                    if (low[v] == index[v]) {
                        val members = ArrayList<Int>()
                        while (true) {
                            val w = tarjanStack[--tsp]
                            onStack.clear(w)
                            compOf[w] = compCount
                            members.add(w)
                            if (w == v) break
                        }
                        comps.add(members)
                        compCount++
                    }
                }
            }
        }
        return compOf to comps
    }

    // ---- phase 3: bottom-up cons over the condensation DAG ----
    // Tarjan pops SCCs in reverse topological order, so component ids are a
    // reverse topo sort: every external child of component c has id < c and
    // is consed before c (level-synchronous processing in the spec collapses
    // to plain id order in a single-threaded v1).

    private fun cons(compOf: IntArray, comps: List<List<Int>>): Pair<List<Shape>, IntArray> {
        val shapes = ArrayList<Shape>()
        val consMap = HashMap<Lk, Shape>()
        val shapeOfComp = arrayOfNulls<Shape>(comps.size)

        fun kidCmp(a: Kid, b: Kid): Int {
            val c = shapes[a.childId].digest.compareTo(shapes[b.childId].digest)
            return if (c != 0) c else a.mult.compareTo(b.mult)
        }

        fun groupedKids(memberRefs: IntArray): List<Kid> {
            val byShape = LinkedHashMap<Shape, Int>()
            for (w in memberRefs) {
                val child = shapeOfComp[compOf[w]]
                    ?: error("child comp ${compOf[w]} consed after parent — topo order violated")
                byShape.merge(child, 1, Int::plus)
            }
            return byShape.map { (s, m) -> Kid(s.internalId, m) }.sortedWith(::kidCmp)
        }

        fun digestOfKids(tag: Int, cls: String, kids: List<Kid>): Digest128 = digestOf {
            tag(tag); str(cls); i32(kids.size)
            for (k in kids) {
                digest(shapes[k.childId].digest); i32(k.mult)
            }
        }

        for (c in comps.indices) {
            val members = comps[c]
            val shape: Shape
            if (members.size == 1 && g.refs[members[0]].none { it == members[0] }) {
                // acyclic single node
                val obj = members[0]
                val cls = g.classes[obj]
                if (g.refs[obj].isEmpty()) {
                    val lk = LeafLk(cls)
                    shape = consMap.getOrPut(lk) {
                        Shape(shapes.size, ShapeKind.LEAF, cls, emptyList(), null,
                            digestOf { tag(1); str(cls) }).also { shapes.add(it) }
                    }
                } else {
                    val kids = groupedKids(g.refs[obj])
                    val lk = NodeLk(cls, kids)
                    shape = consMap.getOrPut(lk) {
                        Shape(shapes.size, ShapeKind.NODE, cls,
                            kids.map { Edge(shapes[it.childId], it.mult) }, null,
                            digestOfKids(2, cls, kids)).also { shapes.add(it) }
                    }
                }
            } else {
                shape = consScc(members, compOf, shapeOfComp, shapes, consMap, ::kidCmp)
            }
            shapeOfComp[c] = shape
        }
        @Suppress("UNCHECKED_CAST")
        return shapes to IntArray(comps.size) { shapeOfComp[it]!!.internalId }
    }

    /** SHAPES.md "SCC node": color refinement; merge only on discretization. */
    private fun consScc(
        members: List<Int>,
        compOf: IntArray,
        shapeOfComp: Array<Shape?>,
        shapes: ArrayList<Shape>,
        consMap: HashMap<Lk, Shape>,
        kidCmp: (Kid, Kid) -> Int,
    ): Shape {
        val n = members.size
        val local = HashMap<Int, Int>(n * 2)
        members.forEachIndexed { i, o -> local[o] = i }

        // per-member external kids and internal neighbors (local indices, with multiplicity)
        val extKids = Array(n) { mutableListOf<Kid>() }
        val intNeigh = Array(n) { mutableListOf<Int>() }
        for (i in 0 until n) {
            val byShape = LinkedHashMap<Shape, Int>()
            for (w in g.refs[members[i]]) {
                val li = local[w]
                if (li != null) intNeigh[i].add(li)
                else byShape.merge(shapeOfComp[compOf[w]]!!, 1, Int::plus)
            }
            extKids[i] = byShape.map { (s, m) -> Kid(s.internalId, m) }.sortedWith(kidCmp).toMutableList()
        }

        fun unique(): Shape {
            // never merged: the SCC is its own unique node (SHAPES.md)
            val sortedMembers = members.sorted()
            val memPayload = sortedMembers.map { o ->
                val i = local[o]!!
                SccMember(
                    g.classes[o],
                    internalCount = intNeigh[i].size,
                    internalRanks = emptyList(),
                    external = extKids[i].map { Edge(shapes[it.childId], it.mult) },
                )
            }
            val totals = LinkedHashMap<Shape, Int>()
            for (kids in extKids) for (k in kids) totals.merge(shapes[k.childId], k.mult, Int::plus)
            val minId = sortedMembers.first()
            return Shape(
                shapes.size, ShapeKind.SCC_UNIQUE, null,
                totals.map { (s, m) -> Edge(s, m) }.sortedWith { a, b ->
                    a.child.digest.compareTo(b.child.digest)
                },
                memPayload,
                digestOf { tag(4); i64(minId.toLong()); i32(n) },
            ).also { shapes.add(it) }
        }

        if (n > MAX_SCC_MERGE) return unique()

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

        var colors = Array(n) { i -> Color(g.classes[members[i]], extKids[i], emptyList()) }
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
                Color(colors[i].cls, colors[i].ext, intNeigh[i].map { ranks[it] }.sorted())
            }
        }

        if (prevDistinct != n) return unique() // did not discretize → never merged

        // canonical form: members sorted by color; internal neighbors by color rank
        val order = (0 until n).sortedWith { a, b -> colorCmp(colors[a], colors[b]) }
        val canonical = order.map { i ->
            MemLk(
                g.classes[members[i]],
                intNeigh[i].map { ranks[it] }.sorted(),
                extKids[i],
            )
        }
        val lk = SccLk(canonical)
        return consMap.getOrPut(lk) {
            val memPayload = canonical.map { m ->
                SccMember(m.cls, m.internalRanks.size, m.internalRanks,
                    m.ext.map { Edge(shapes[it.childId], it.mult) })
            }
            Shape(
                shapes.size, ShapeKind.SCC, null,
                memPayload.first().external, // identical for every member (part of the color)
                memPayload,
                digestOf {
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
        shapeOfObj: IntArray,
        comps: List<List<Int>>,
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

        for (comp in comps) {
            val sid = shapeOfObj[comp.first()]
            for (o in comp) {
                count[sid]++
                val sh = g.shallow[o]
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
                    ShapeKind.SCC_UNIQUE -> "scc![${s.members!!.joinToString(",") { it.className }}]"
                },
                instanceCount = count[s.internalId],
                shallowTotal = shallowSum[s.internalId],
                shallowMin = shallowMin[s.internalId],
                shallowMax = shallowMax[s.internalId],
                rootCount = rootCount[s.internalId],
                sccInstanceSize = s.members?.size ?: 1,
                internalEdgesPerInstance = s.members?.sumOf { it.internalCount } ?: 0,
                edgesPerMember = s.kind != ShapeKind.SCC_UNIQUE,
            )
        }
        val edges = ArrayList<EdgeEntry>()
        for (s in shapes) for (e in s.edges) {
            edges.add(EdgeEntry(finalId[s.internalId], finalId[e.child.internalId], e.mult.toLong()))
        }
        val rootsHist = HashMap<Int, Long>()
        for (r in roots) {
            rootsHist.merge(finalId[shapeOfObj[r]], 1L, Long::plus)
        }
        return ShapeTable(reachableCount, entries, edges, rootsHist)
    }

    companion object {
        const val MAX_SCC_MERGE = 64 // SHAPES.md: |SCC| > 64 → unique, never merged
    }
}
