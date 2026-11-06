package shapes

import shapes.models.ClassName

/** Synthetic repetitive heaps for demos/tests (the "3000 modules" shape). */
object SynthGraphs {
    /**
     * N identical modules: module -> deps -> [lib, lib]; module -> config -> [props, shared].
     * One SharedProps object referenced by all configs (in-degree N) to exercise sharing.
     */
    fun modules(n: Int): Pair<ObjectGraph, IntArray> {
        val builder = ObjectGraph.Builder()
        val shared = builder.add(ClassName("synth.SharedProps"), 64U)
        val roots = IntArray(n)
        for (i in 0 until n) {
            val libA = builder.add(ClassName("synth.Lib"), 32U)
            val libB = builder.add(ClassName("synth.Lib"), 32U)
            val props = builder.add(ClassName("synth.Props"), 24U)
            val deps = builder.add(ClassName("synth.Deps"), 48U, intArrayOf(libA, libB))
            val conf = builder.add(ClassName("synth.Config"), 40U, intArrayOf(props, shared))
            roots[i] = builder.add(ClassName("synth.Module"), 120U, intArrayOf(deps, conf))
        }
        return builder.build() to roots
    }
}