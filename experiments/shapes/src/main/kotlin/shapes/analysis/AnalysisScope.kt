package shapes.analysis

/** Objects to analyze and the roots used only for root accounting. */
sealed interface AnalysisScope {
    data class ReachableFrom(val roots: IntArray) : AnalysisScope

    data class EntireGraph(
        val rootsForHistogram: IntArray = IntArray(0),
    ) : AnalysisScope
}
