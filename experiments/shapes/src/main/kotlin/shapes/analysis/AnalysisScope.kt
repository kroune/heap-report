package shapes.analysis

/** Objects to analyze and the roots used only for root accounting. */
sealed interface AnalysisScope {
    class ReachableFrom(val roots: IntArray) : AnalysisScope

    class EntireGraph(
        val rootsForHistogram: IntArray = IntArray(0),
    ) : AnalysisScope
}
