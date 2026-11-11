package shapes.mat

import org.eclipse.mat.util.VoidProgressListener
import java.nio.file.Path

object MatSnapshotDiagnostics {
    fun info(hprof: Path) = withMatSnapshot(hprof) { snapshot ->
        val info = snapshot.snapshotInfo
        println("objects        : ${info.numberOfObjects}")
        println("used heap      : ${info.usedHeapSize}")
        println("classes        : ${info.numberOfClasses}")
        println("gc roots       : ${info.numberOfGCRoots}")
    }

    fun histogram(hprof: Path, maximumClassCount: Int) = withMatSnapshot(hprof) { snapshot ->
        snapshot.getHistogram(VoidProgressListener()).classHistogramRecords
            .sortedByDescending { it.numberOfObjects }
            .take(maximumClassCount)
            .forEach { record ->
                println(
                    "%-90s n=%-10d shallow=%d".format(
                        record.label.take(90),
                        record.numberOfObjects,
                        record.usedHeapSize,
                    )
                )
            }
    }

    fun checkClassObjects(hprof: Path, maximumScanCount: Int) =
        withMatSnapshot(hprof, readObjectFields = true) { snapshot ->
            val classDefinitions = snapshot.getClassesByName("java.lang.Class", false)
                ?: error("no Class")
            var scannedCount = 0
            var unrecognizedCount = 0
            for (classDefinition in classDefinitions) {
                for (objectId in classDefinition.objectIds) {
                    if (scannedCount >= maximumScanCount) break
                    scannedCount++
                    if (!snapshot.isClass(objectId)) {
                        unrecognizedCount++
                        if (unrecognizedCount <= 20) {
                            println(
                                "  LEAK id=$objectId isClass=false " +
                                    "classOf=${snapshot.getClassOf(objectId).name}"
                            )
                        }
                    }
                }
            }
            println(
                "checked $scannedCount java.lang.Class instances, " +
                    "isClass=false: $unrecognizedCount"
            )
        }
}
