package shapes.cli

import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.int
import com.github.ajalt.clikt.parameters.types.path
import shapes.mat.MatReachabilityDiagnostics
import shapes.mat.MatReferenceDiagnostics
import shapes.mat.MatSnapshotDiagnostics

internal abstract class MatDiagnosticCommand(name: String, help: String) : ShapesCommand(name, help) {
    protected val hprof by option("--hprof", help = "Heap dump with pre-built MAT indexes")
        .path(mustExist = true, canBeDir = false)
        .required()
}

internal class MatInfoCommand : MatDiagnosticCommand("mat-info", "Print heap snapshot metadata") {
    override fun run() = MatSnapshotDiagnostics.info(hprof)
}

internal class MatHistogramCommand : MatDiagnosticCommand(
    "mat-histogram",
    "Print the heap class histogram",
) {
    private val maximumClassCount by option("--top", help = "Maximum number of classes to print")
        .int()
        .default(30)

    override fun run() = MatSnapshotDiagnostics.histogram(hprof, maximumClassCount)
}

internal class MatTraceCommand : MatDiagnosticCommand(
    "mat-trace",
    "Trace selected classes in a heap snapshot",
) {
    private val className by option("--class", help = "Class to trace").required()
    private val targets by option("--targets", help = "Comma-separated target class names")
        .default("DefaultGradleBuild,DefaultGradle,BuildState,DefaultProject")
    private val maximumLevel by option("--maxlevel", help = "Maximum trace depth").int().default(15)
    private val maximumObjectCount by option("--maxobjs", help = "Maximum objects to visit")
        .int()
        .default(80_000_000)

    override fun run() = MatReachabilityDiagnostics.traceOutboundReferences(
        hprof,
        className,
        targets.split(","),
        maximumLevel,
        maximumObjectCount,
    )
}

internal class MatFieldsCommand : MatDiagnosticCommand(
    "mat-fields",
    "Print outbound fields for class instances",
) {
    private val className by option("--class", help = "Class to inspect").required()
    private val maximumInstanceCount by option("--limit", help = "Maximum instances to print")
        .int()
        .default(5)
    private val includeAll by option("--all", help = "Include instances without reference targets").flag()

    override fun run() = MatReferenceDiagnostics.printFields(
        hprof,
        className,
        maximumInstanceCount,
        onlyReferenceTargets = !includeAll,
    )
}

internal class MatReferenceTargetsCommand : MatDiagnosticCommand(
    "mat-ref-targets",
    "Print a histogram of reference-typed field targets",
) {
    private val className by option("--class", help = "Class to inspect").required()
    private val maximumScanCount by option("--maxscan", help = "Maximum instances to scan")
        .int()
        .default(1_000_000_000)

    override fun run() = MatReferenceDiagnostics.referenceTargetHistogram(
        hprof,
        className,
        maximumScanCount,
    )
}

internal class MatClassObjectsCommand : MatDiagnosticCommand(
    "mat-isclass",
    "Check java.lang.Class instances against the MAT class table",
) {
    private val maximumScanCount by option("--limit", help = "Maximum instances to scan")
        .int()
        .default(5)

    override fun run() = MatSnapshotDiagnostics.checkClassObjects(hprof, maximumScanCount)
}

internal class MatReachBackCommand : MatDiagnosticCommand(
    "mat-reachback",
    "Run reverse BFS over the MAT inbound-reference index",
) {
    private val className by option("--class", help = "Class to start from").required()
    private val maximumSeedCount by option("--limit", help = "Maximum seed instances")
        .int()
        .default(3)
    private val maximumLevel by option("--maxlevel", help = "Maximum BFS depth")
        .int()
        .default(30)
    private val maximumObjectCount by option("--maxobjs", help = "Maximum objects to visit")
        .int()
        .default(150_000_000)

    override fun run() = MatReachabilityDiagnostics.printReferrersByLevel(
        hprof,
        className,
        maximumSeedCount,
        maximumLevel,
        maximumObjectCount,
    )
}
