package shapes.mat

import java.nio.file.Path
import java.util.BitSet

object MatReachabilityDiagnostics {
    fun printReferrersByLevel(
        hprof: Path,
        className: String,
        maximumSeedCount: Int,
        maximumLevel: Int,
        maximumObjectCount: Int,
    ) = withMatSnapshot(hprof) { snapshot ->
        val startedAt = System.nanoTime()
        val classDefinitions = snapshot.getClassesByName(className, false)
            ?: error("class not found: $className")
        check(classDefinitions.isNotEmpty()) { "class not found: $className" }
        val roots = classDefinitions.flatMap { it.objectIds.asIterable() }
            .distinct()
            .take(maximumSeedCount)
        println("reachback from $className: ${roots.size} seed instance(s)")

        val visited = BitSet(snapshot.snapshotInfo.numberOfObjects)
        var frontier = IntArray(roots.size)
        var frontierSize = 0
        for (rootId in roots) {
            if (!visited.get(rootId)) {
                visited.set(rootId)
                frontier[frontierSize++] = rootId
            }
        }

        var nextFrontier = IntArray(1 shl 16)
        var totalCount = frontierSize.toLong()
        var level = 0
        while (frontierSize > 0 && level <= maximumLevel && totalCount < maximumObjectCount) {
            val histogram = classHistogram(snapshot, frontier, frontierSize)
            printLevelSummary(histogram, frontierSize, level, totalCount, startedAt, 8)

            var nextSize = 0
            for (index in 0 until frontierSize) {
                for (referrerId in snapshot.getInboundRefererIds(frontier[index])) {
                    if (referrerId >= 0 && !visited.get(referrerId)) {
                        visited.set(referrerId)
                        if (nextSize == nextFrontier.size) {
                            nextFrontier = nextFrontier.copyOf(nextFrontier.size * 2)
                        }
                        nextFrontier[nextSize++] = referrerId
                    }
                }
            }
            val previousFrontier = frontier
            frontier = nextFrontier
            nextFrontier = previousFrontier
            frontierSize = nextSize
            level++
            totalCount += frontierSize
        }
        println("reachback total: $totalCount objects reach $className instances")
    }

    fun traceOutboundReferences(
        hprof: Path,
        className: String,
        targetClassNames: List<String>,
        maximumLevel: Int,
        maximumObjectCount: Int,
    ) = withMatSnapshot(hprof) { snapshot ->
        val startedAt = System.nanoTime()
        val classDefinitions = snapshot.getClassesByName(className, false)
            ?: error("class not found: $className")
        check(classDefinitions.isNotEmpty()) { "class not found: $className" }
        val roots = classDefinitions.flatMap { it.objectIds.asIterable() }.distinct()
        println("class $className: ${roots.size} instances (roots), targets=$targetClassNames")

        val objectCount = snapshot.snapshotInfo.numberOfObjects
        val visited = BitSet(objectCount)
        val parentIds = IntArray(objectCount) { -1 }
        var frontier = IntArray(roots.size)
        var frontierSize = 0
        for (rootId in roots) {
            if (!visited.get(rootId)) {
                visited.set(rootId)
                frontier[frontierSize++] = rootId
                parentIds[rootId] = -2
            }
        }

        var nextFrontier = IntArray(1 shl 16)
        var totalCount = frontierSize.toLong()
        var firstTargetObjectId = -1
        var firstTargetLevel = -1
        var level = 0
        while (frontierSize > 0 && level <= maximumLevel && totalCount < maximumObjectCount) {
            val histogram = HashMap<String, Int>()
            for (index in 0 until frontierSize) {
                val objectId = frontier[index]
                val objectClassName = snapshot.getClassOf(objectId).name
                histogram.merge(objectClassName, 1, Int::plus)
                if (firstTargetObjectId < 0 && targetClassNames.any { objectClassName.contains(it) }) {
                    firstTargetObjectId = objectId
                    firstTargetLevel = level
                }
            }
            printLevelSummary(histogram, frontierSize, level, totalCount, startedAt, 6)

            var nextSize = 0
            for (index in 0 until frontierSize) {
                val sourceId = frontier[index]
                for (targetId in snapshot.getOutboundReferentIds(sourceId)) {
                    if (targetId >= 0 && !visited.get(targetId) && !snapshot.isClass(targetId)) {
                        visited.set(targetId)
                        parentIds[targetId] = sourceId
                        if (nextSize == nextFrontier.size) {
                            nextFrontier = nextFrontier.copyOf(nextFrontier.size * 2)
                        }
                        nextFrontier[nextSize++] = targetId
                    }
                }
            }
            val previousFrontier = frontier
            frontier = nextFrontier
            nextFrontier = previousFrontier
            frontierSize = nextSize
            level++
            totalCount += frontierSize
        }
        println("stopped: level=$level cumulative=%,d".format(totalCount))

        if (firstTargetObjectId >= 0) {
            printPathToTarget(snapshot, parentIds, firstTargetObjectId, firstTargetLevel)
        } else {
            println("no target hub found within $maximumLevel levels / $maximumObjectCount objects")
        }
    }

    private fun classHistogram(
        snapshot: org.eclipse.mat.snapshot.ISnapshot,
        frontier: IntArray,
        frontierSize: Int,
    ): Map<String, Int> {
        val histogram = HashMap<String, Int>()
        for (index in 0 until frontierSize) {
            histogram.merge(snapshot.getClassOf(frontier[index]).name, 1, Int::plus)
        }
        return histogram
    }

    private fun printLevelSummary(
        histogram: Map<String, Int>,
        frontierSize: Int,
        level: Int,
        totalCount: Long,
        startedAt: Long,
        histogramLimit: Int,
    ) {
        val elapsedSeconds = (System.nanoTime() - startedAt) / 1_000_000_000.0
        println(
            "level %2d: %,12d objects (cumulative %,12d) [%.0fs]"
                .format(level, frontierSize, totalCount, elapsedSeconds)
        )
        histogram.entries.sortedByDescending { it.value }.take(histogramLimit)
            .forEach { (name, count) ->
                println("         %,12d  %s".format(count, name.take(100)))
            }
    }

    private fun printPathToTarget(
        snapshot: org.eclipse.mat.snapshot.ISnapshot,
        parentIds: IntArray,
        targetObjectId: Int,
        targetLevel: Int,
    ) {
        println("\nshortest path root -> hub (hub at level $targetLevel):")
        val path = ArrayList<Int>()
        var objectId = targetObjectId
        while (objectId >= 0) {
            path.add(objectId)
            objectId = parentIds[objectId]
        }
        path.reverse()
        for ((index, pathObjectId) in path.withIndex()) {
            val outboundCount = snapshot.getOutboundReferentIds(pathObjectId).size
            println(
                "  %2d: %-90s out=%d".format(
                    index,
                    snapshot.getClassOf(pathObjectId).name.take(90),
                    outboundCount,
                )
            )
        }
    }
}
