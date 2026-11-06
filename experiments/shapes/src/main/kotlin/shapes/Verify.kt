package shapes

import java.util.ArrayDeque
import java.util.BitSet

/**
 * Correctness invariants from SHAPES.md "Correctness invariants (smoke tests)".
 * Returns the list of violations; empty = all invariants hold.
 *
 * 1. Σ shallowTotal over shapes == Σ shallow over R
 * 2. Σ rootCount == instance count of the class (== roots.size)
 * 3. Σ edge multiplicities (expanded) + internal SCC edges == total outbound
 *    ref count within R
 * 4. Every object in R has exactly one shape (Σ instanceCount == |R|)
 *
 * (5 — match vs the sampling payload — needs MAT and is out of scope here;
 *  6 — determinism — is covered by tests running analyze twice.)
 */
fun verifyInvariants(graph: ObjectGraph, roots: IntArray, t: ShapeTable, wholeGraph: Boolean = false): List<String> {
    val problems = mutableListOf<String>()

    // independent re-computation of R and its totals
    val inR = BitSet(graph.size)
    val stack = ArrayDeque<Int>()
    val seeds = if (wholeGraph) 0 until graph.size else roots.asIterable()
    for (r in seeds) if (!inR.get(r)) {
        inR.set(r); stack.add(r)
    }
    var rCount = 0L
    var shallowSum = 0UL
    var refCount = 0L
    while (stack.isNotEmpty()) {
        val v = stack.removeLast()
        rCount++
        shallowSum += graph.shallow[v]
        refCount += graph.referencesList[v].size
        graph.referencesList.forEachReference(graph.referencesList[v]) { w ->
            if (!inR.get(w)) {
                inR.set(w); stack.add(w)
            }
        }
    }

    // inv 1
    val shapeShallow = t.entries.sumOf { it.shallowTotal }
    if (shapeShallow != shallowSum) {
        problems += "inv1: Σ shallowTotal ($shapeShallow) != Σ shallow over R ($shallowSum)"
    }
    // inv 2
    val shapeRoots = t.entries.sumOf { it.rootCount }
    if (shapeRoots != roots.size.toLong()) {
        problems += "inv2: Σ rootCount ($shapeRoots) != roots (${roots.size})"
    }
    // inv 4
    val shapeInstances = t.entries.sumOf { it.instanceCount }
    if (shapeInstances != rCount) {
        problems += "inv4: Σ instanceCount ($shapeInstances) != |R| ($rCount)"
    }
    // inv 3
    val edgesByShape = t.edges.groupBy { it.shapeId }
    var accounted = 0L
    for (e in t.entries) {
        val ext = edgesByShape[e.id].orEmpty().sumOf { it.mult }
        val collapsedInstances = e.instanceCount / e.sccInstanceSize
        accounted += if (e.edgesPerMember) ext * e.instanceCount else ext * collapsedInstances
        accounted += e.internalEdgesPerInstance * collapsedInstances
    }
    if (accounted != refCount) {
        problems += "inv3: expanded edges + internal ($accounted) != Σ refs in R ($refCount)"
    }
    return problems
}

/**
 * Per-shape edge-accounting audit (inv3 debugging): re-derives object ->
 * shape from the analyzer internals and checks EVERY ref in R against the
 * emitted shape-DAG. Full pass over all refs — minutes at 10⁸ objects.
 * Returns up to 20 mismatch lines; empty = exact.
 */
fun verifyEdgeAccounting(graph: ObjectGraph, dbg: ShapesAnalyzer.DebugResult,
                         relaxLevels: IntArray = IntArray(0)): List<String> {
    val t = dbg.table
    val pubByDigest = HashMap<Digest128, Int>()
    for (e in t.entries) pubByDigest[e.digest] = e.id
    val pubOfInternal = IntArray(dbg.shapes.size) { pubByDigest.getValue(dbg.shapes[it].digest) }
    val shapeOfObj = IntArray(graph.size) { -1 }
    for (c in 0 until dbg.comps.count) {
        val sid = pubOfInternal[dbg.shapeOfComp[c]]
        dbg.comps.forEachMember(c) { o -> shapeOfObj[o] = sid }
    }
    // same shape <=> same component (a cross-comp same-shape ref would be a
    // shape-DAG cycle, which the condensation forbids)
    val extTruth = HashMap<Long, Long>()
    val intTruth = HashMap<Int, Long>()
    val nonExemptShapes = HashSet<Int>()
    var relaxedSources = 0L
    // set-children on any SCC member quotients the WHOLE SCC (Shapes.kt):
    // every member's emitted edges become multiplicity-normalized, so the
    // whole component is exempt from exact edge accounting, not only the
    // relaxed member itself
    val quotientExempt = BooleanArray(graph.size)
    if (relaxLevels.isNotEmpty()) {
        for (c in 0 until dbg.comps.count) {
            var anySet = false
            dbg.comps.forEachMember(c) { o ->
                if (relaxLevels[graph.classIdx[o].code] == Relax.SET_CHILDREN) anySet = true
            }
            if (anySet) dbg.comps.forEachMember(c) { o -> quotientExempt[o] = true }
        }
    }
    for (index in 0 until graph.size) {
        val so = shapeOfObj[index]
        if (so < 0) continue   // outside R (partial-reach text graphs)
        // relaxed objects conceal their children (Relax.kt): their refs are
        // neither in the artifact nor expected here — exactness for them is
        // the analyzer's RelaxStats counters instead
        if (quotientExempt[index] ||
            (relaxLevels.isNotEmpty() && relaxLevels[graph.classIdx[index].code] > 0)) {
            relaxedSources++
            continue
        }
        nonExemptShapes.add(so)
        graph.referencesList.forEachReference(graph.referencesList[index]) { w ->
            val sw = shapeOfObj[w]
            if (sw == so) intTruth.merge(so, 1L, Long::plus)
            else extTruth.merge((so.toLong() shl 32) or (sw.toLong() and 0xffffffffL), 1L, Long::plus)
        }
    }
    if (relaxedSources > 0)
        println("verify-edges: $relaxedSources objects of relaxed classes exempted " +
            "(their concealed edges are accounted by RelaxStats)")
    val problems = mutableListOf<String>()
    val entries = t.entries.associateBy { it.id }
    for (e in t.entries) {
        // shapes whose objects are ALL exempt (relaxed classes, quotiented
        // SCCs) emitted concealed/quotient-normalized edges — no exact truth
        // to compare against; their accounting is RelaxStats
        if (e.id !in nonExemptShapes && relaxLevels.isNotEmpty()) continue
        val collapsed = e.instanceCount / e.sccInstanceSize
        val intEmitted = e.internalEdgesPerInstance * collapsed
        val intTrue = intTruth[e.id] ?: 0L
        if (intEmitted != intTrue) {
            problems += "shape #${e.id} ${e.kind} ${e.label.take(50)}: " +
                "internal emitted $intEmitted != true $intTrue"
        }
    }
    val extEmitted = HashMap<Long, Long>()
    for (edge in t.edges) {
        val src = entries.getValue(edge.shapeId)
        if (src.id !in nonExemptShapes && relaxLevels.isNotEmpty()) continue
        val m = if (src.edgesPerMember) edge.mult * src.instanceCount
            else edge.mult * (src.instanceCount / src.sccInstanceSize)
        extEmitted.merge((edge.shapeId.toLong() shl 32) or
            (edge.childShapeId.toLong() and 0xffffffffL), m, Long::plus)
    }
    for ((k, v) in extEmitted) {
        val tv = extTruth[k] ?: 0L
        if (tv != v) problems += "edge #${k shr 32} -> #${k and 0xffffffff}: emitted $v != true $tv"
    }
    for ((k, v) in extTruth) {
        if (!extEmitted.containsKey(k))
            problems += "edge #${k shr 32} -> #${k and 0xffffffff}: true $v missing from artifact"
    }
    return problems.take(20)
}
