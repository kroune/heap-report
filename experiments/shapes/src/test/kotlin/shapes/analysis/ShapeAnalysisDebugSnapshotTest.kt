package shapes.analysis

import shapes.analysis.internal.AnalysisObjectSet
import shapes.graph.ClassName
import shapes.graph.ObjectGraph
import kotlin.test.Test
import kotlin.test.assertTrue

class ShapeAnalysisDebugSnapshotTest {
    @Test
    fun `debug snapshot does not retain scope traversal working sets`() {
        val graph = ObjectGraph.of(
            classes = arrayOf(ClassName("Root"), ClassName("Unreachable")),
            shallow = ulongArrayOf(10U, 20U),
            refs = arrayOf(intArrayOf(), intArrayOf()),
        )
        val debug = ShapesAnalyzer(graph).analyzeDebug(
            AnalysisScope.ReachableFrom(intArrayOf(0))
        )

        val retainedScopeFields = debug::class.java.declaredFields.filter { field ->
            AnalysisObjectSet::class.java.isAssignableFrom(field.type)
        }
        assertTrue(
            retainedScopeFields.isEmpty(),
            "Debug snapshot retains reachability working sets: $retainedScopeFields",
        )
    }
}
