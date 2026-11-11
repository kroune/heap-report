package shapes.mat

import java.nio.file.Path
import java.util.TreeMap

object MatReferenceDiagnostics {
    fun referenceTargetHistogram(hprof: Path, className: String, maximumScanCount: Int) =
        withMatSnapshot(hprof, readObjectFields = true) { snapshot ->
            val classDefinitions = snapshot.getClassesByName(className, false)
                ?: error("class not found: $className")
            val histogram = TreeMap<String, Int>()
            var scannedCount = 0
            for (classDefinition in classDefinitions) {
                for (objectId in classDefinition.objectIds) {
                    if (scannedCount >= maximumScanCount) break
                    scannedCount++
                    for (reference in snapshot.getObject(objectId).outboundReferences) {
                        if (snapshot.isClass(reference.objectId)) continue
                        val targetClassName = snapshot.getObject(reference.objectId).clazz.name
                        if (targetClassName.startsWith("java.lang.ref.ReferenceQueue")) continue
                        if (!targetClassName.contains("Reference")) continue
                        histogram.merge("${reference.name} -> $targetClassName", 1, Int::plus)
                    }
                    if (scannedCount % 20_000 == 0) println("  scanned $scannedCount...")
                }
            }
            println("scanned $scannedCount instances of $className")
            histogram.entries.sortedByDescending { it.value }.take(20)
                .forEach { println("  %,8d  %s".format(it.value, it.key)) }
        }

    fun printFields(
        hprof: Path,
        className: String,
        maximumInstanceCount: Int,
        onlyReferenceTargets: Boolean,
    ) = withMatSnapshot(hprof, readObjectFields = true) { snapshot ->
        val classDefinitions = snapshot.getClassesByName(className, false)
            ?: error("class not found: $className")
        var shownCount = 0
        var scannedCount = 0
        for (classDefinition in classDefinitions) {
            for (objectId in classDefinition.objectIds) {
                if (shownCount >= maximumInstanceCount) break
                val heapObject = snapshot.getObject(objectId)
                val outboundReferences = heapObject.outboundReferences.filter {
                    !snapshot.isClass(it.objectId)
                }
                if (onlyReferenceTargets && outboundReferences.none {
                        snapshot.getObject(it.objectId).clazz.name.contains("Reference")
                    }
                ) {
                    scannedCount++
                    continue
                }

                println("0x${heapObject.objectAddress.toString(16)} ${heapObject.clazz.name}")
                for (reference in outboundReferences) {
                    val target = snapshot.getObject(reference.objectId)
                    println(
                        "    .${reference.name} -> ${target.clazz.name} " +
                            "@0x${target.objectAddress.toString(16)}"
                    )
                }
                shownCount++
                scannedCount++
            }
        }
        println("shown $shownCount instances of $className (scanned $scannedCount)")
    }
}
