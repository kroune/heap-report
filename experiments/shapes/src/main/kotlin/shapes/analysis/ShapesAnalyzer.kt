package shapes.analysis

import shapes.analysis.internal.AnalysisScopeResolver
import shapes.analysis.internal.ShapeAnalysisResultBuilder
import shapes.analysis.internal.ShapeCollapsePhase
import shapes.analysis.internal.ShapeCollapseResult
import shapes.analysis.internal.StronglyConnectedComponents
import shapes.analysis.internal.StronglyConnectedComponentsFinder
import shapes.graph.ObjectGraph
import shapes.relaxation.ShapeRelaxationPolicy
import shapes.relaxation.ShapeRelaxationRules
import shapes.relaxation.ShapeRelaxationStats

internal class ShapeAnalysisDebugSnapshot(
    val result: ShapeAnalysisResult,
    val components: StronglyConnectedComponents,
    val collapse: ShapeCollapseResult,
)

/** Coordinates reachability, SCC construction, structural collapse and aggregation. */
class ShapesAnalyzer @JvmOverloads constructor(
    private val graph: ObjectGraph,
    relaxationRules: ShapeRelaxationRules = ShapeRelaxationRules(),
    private val setSemantics: Boolean = DEFAULT_SET_SEMANTICS,
) {
    var progress: (String) -> Unit = {}

    val relaxationStats = ShapeRelaxationStats()

    internal val relaxationPolicy: ShapeRelaxationPolicy by lazy(LazyThreadSafetyMode.NONE) {
        ShapeRelaxationPolicy(relaxationRules, graph.classNames) { className ->
            progress("relax: WARNING rule for $className matches no class in this graph")
        }
    }

    fun analyze(scope: AnalysisScope): ShapeAnalysisResult = runAnalysis(scope).result

    internal fun analyzeDebug(scope: AnalysisScope): ShapeAnalysisDebugSnapshot = runAnalysis(scope)

    private fun runAnalysis(scope: AnalysisScope): ShapeAnalysisDebugSnapshot {
        relaxationStats.reset()

        var phaseStartedAt = System.nanoTime()
        val objects = AnalysisScopeResolver(graph).resolve(scope)
        progress(
            "reach: ${objects.objectCount} objects in " +
                "${elapsedMillis(phaseStartedAt)} ms"
        )

        phaseStartedAt = System.nanoTime()
        val components = StronglyConnectedComponentsFinder(graph).find(objects)
        progress(
            "scc: ${components.members.count} components in " +
                "${elapsedMillis(phaseStartedAt)} ms"
        )

        phaseStartedAt = System.nanoTime()
        val collapse = ShapeCollapsePhase(
            graph,
            relaxationPolicy,
            relaxationStats,
            setSemantics,
            progress,
        ).collapse(components)
        progress(
            "cons: ${collapse.shapes.size} shapes in ${elapsedMillis(phaseStartedAt)} ms"
        )

        phaseStartedAt = System.nanoTime()
        val result = ShapeAnalysisResultBuilder(graph).build(
            collapse,
            components,
            objects.rootIds,
            objects.objectCount,
        )
        progress("aggregate: ${elapsedMillis(phaseStartedAt)} ms")
        return ShapeAnalysisDebugSnapshot(result, components, collapse)
    }

    private fun elapsedMillis(startedAt: Long): Long = (System.nanoTime() - startedAt) / 1_000_000

    companion object {
        val DEFAULT_SET_SEMANTICS: Boolean = System.getenv("SHAPES_SET_SEMANTICS") == "1"
    }
}
