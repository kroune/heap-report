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
fun verifyInvariants(g: ObjectGraph, roots: IntArray, t: ShapeTable): List<String> {
    val problems = mutableListOf<String>()

    // independent re-computation of R and its totals
    val inR = BitSet(g.size)
    val stack = ArrayDeque<Int>()
    for (r in roots) if (!inR.get(r)) {
        inR.set(r); stack.add(r)
    }
    var rCount = 0L
    var shallowSum = 0L
    var refCount = 0L
    while (stack.isNotEmpty()) {
        val v = stack.removeLast()
        rCount++
        shallowSum += g.shallow[v]
        refCount += g.refs[v].size
        for (w in g.refs[v]) if (!inR.get(w)) {
            inR.set(w); stack.add(w)
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
        accounted += if (e.edgesPerMember) ext * e.instanceCount else ext
        accounted += e.internalEdgesPerInstance * collapsedInstances
    }
    if (accounted != refCount) {
        problems += "inv3: expanded edges + internal ($accounted) != Σ refs in R ($refCount)"
    }
    return problems
}
