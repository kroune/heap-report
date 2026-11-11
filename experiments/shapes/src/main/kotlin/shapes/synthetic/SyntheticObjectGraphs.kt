package shapes.synthetic

import shapes.graph.ClassName
import shapes.graph.ObjectGraph

/** Synthetic repetitive heaps for demos and tests. */
object SyntheticObjectGraphs {
    /**
     * N identical modules: module -> dependencies -> [library, library];
     * module -> configuration -> [properties, sharedProperties].
     */
    fun modules(moduleCount: Int): Pair<ObjectGraph, IntArray> {
        val builder = ObjectGraph.Builder()
        val sharedProperties = builder.addObject(ClassName("synth.SharedProps"), 64U)
        val moduleRoots = IntArray(moduleCount)

        repeat(moduleCount) { moduleIndex ->
            val firstLibrary = builder.addObject(ClassName("synth.Lib"), 32U)
            val secondLibrary = builder.addObject(ClassName("synth.Lib"), 32U)
            val properties = builder.addObject(ClassName("synth.Props"), 24U)
            val dependencies = builder.addObject(
                ClassName("synth.Deps"),
                48U,
                intArrayOf(firstLibrary, secondLibrary),
            )
            val configuration = builder.addObject(
                ClassName("synth.Config"),
                40U,
                intArrayOf(properties, sharedProperties),
            )
            moduleRoots[moduleIndex] = builder.addObject(
                ClassName("synth.Module"),
                120U,
                intArrayOf(dependencies, configuration),
            )
        }
        return builder.build() to moduleRoots
    }
}
