package shapes

import shapes.hprof.ProjectionRules
import shapes.hprof.Selection
import shapes.relaxation.ShapeRelaxationRules
import java.nio.file.Path

/** Fully resolved configuration of one `hprof-analyze` run; no paths to rules, no nulls to interpret. */
class HprofAnalyzeConfig(
    val hprof: Path,
    val projection: ProjectionRules,
    val selection: Selection,
    val shapeRules: ShapeRelaxationRules,
    val artifact: Path?,
    val extractionStats: Path?,
    val relaxationStats: Path?,
    val edgeHistogram: Path?,
    val verifyInvariants: Boolean,
    val verifyEdges: Boolean,
)

class DemoConfig(
    val moduleCount: Int,
    val verifyInvariants: Boolean,
)

/** Thrown by a driver when a requested verification reports violations. */
class VerificationFailed(val check: String) : RuntimeException("$check: violations found")
