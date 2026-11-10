package shapes.mat

import org.eclipse.mat.snapshot.ISnapshot
import java.util.BitSet
import java.util.TreeMap

/** Standalone diagnostic queries. Graph extraction lives in shapes.extraction. */
object MatDiagnostics {
    fun info(hprof: String) = withSnapshot(hprof) { snapshot ->
        val info = snapshot.snapshotInfo
        println("objects        : ${info.numberOfObjects}")
        println("used heap      : ${info.usedHeapSize}")
        println("classes        : ${info.numberOfClasses}")
        println("gc roots       : ${info.numberOfGCRoots}")
    }

    fun histogram(hprof: String, top: Int) = withSnapshot(hprof) { snapshot ->
        snapshot.getHistogram(org.eclipse.mat.util.VoidProgressListener()).classHistogramRecords
            .sortedByDescending { it.numberOfObjects }
            .take(top)
            .forEach { record ->
                println(
                    "%-90s n=%-10d shallow=%d".format(
                        record.label.take(90), record.numberOfObjects, record.usedHeapSize
                    )
                )
            }
    }

    fun isClassCheck(hprof: String, maxScan: Int) = withSnapshot(hprof, withReader = true) { snapshot ->
        val classes = snapshot.getClassesByName("java.lang.Class", false) ?: error("no Class")
        var count = 0
        var bad = 0
        for (clazz in classes) {
            for (id in clazz.objectIds) {
                if (count >= maxScan) break
                count++
                if (!snapshot.isClass(id)) {
                    bad++
                    if (bad <= 20) {
                        println("  LEAK id=$id isClass=false classOf=${snapshot.getClassOf(id).name}")
                    }
                }
            }
        }
        println("checked $count java.lang.Class instances, isClass=false: $bad")
    }

    /** Reverse BFS over MAT's inbound-reference index. */
    fun reachBack(hprof: String, className: String, limit: Int, maxLevel: Int, maxObjs: Int) =
        withSnapshot(hprof) { snapshot ->
            val start = System.nanoTime()
            val classes = snapshot.getClassesByName(className, false) ?: error("class not found: $className")
            check(classes.isNotEmpty()) { "class not found: $className" }
            val roots = classes.flatMap { it.objectIds.asIterable() }.distinct().take(limit)
            println("reachback from $className: ${roots.size} seed instance(s)")

            val visited = BitSet(snapshot.snapshotInfo.numberOfObjects)
            var frontier = IntArray(roots.size)
            var frontierSize = 0
            for (root in roots) {
                if (!visited.get(root)) {
                    visited.set(root)
                    frontier[frontierSize++] = root
                }
            }
            var next = IntArray(1 shl 16)
            var total = frontierSize.toLong()
            var level = 0
            while (frontierSize > 0 && level <= maxLevel && total < maxObjs) {
                val histogram = HashMap<String, Int>()
                for (i in 0 until frontierSize) {
                    histogram.merge(snapshot.getClassOf(frontier[i]).name, 1, Int::plus)
                }
                val elapsed = (System.nanoTime() - start) / 1_000_000_000.0
                println(
                    "level %2d: %,12d referrers (cumulative %,12d)  [%.0fs]"
                        .format(level, frontierSize, total, elapsed)
                )
                histogram.entries.sortedByDescending { it.value }.take(8).forEach { (name, count) ->
                    println("         %,12d  %s".format(count, name.take(100)))
                }
                var nextSize = 0
                for (i in 0 until frontierSize) {
                    for (target in snapshot.getInboundRefererIds(frontier[i])) {
                        if (target >= 0 && !visited.get(target)) {
                            visited.set(target)
                            if (nextSize == next.size) next = next.copyOf(next.size * 2)
                            next[nextSize++] = target
                        }
                    }
                }
                val previous = frontier
                frontier = next
                next = previous
                frontierSize = nextSize
                level++
                total += frontierSize
            }
            println("reachback total: $total objects reach $className instances")
        }

    /** Histogram of field name to reference-typed target class. */
    fun refTargets(hprof: String, className: String, maxScan: Int) =
        withSnapshot(hprof, withReader = true) { snapshot ->
            val classes = snapshot.getClassesByName(className, false) ?: error("class not found: $className")
            val histogram = TreeMap<String, Int>()
            var scanned = 0
            for (clazz in classes) {
                for (id in clazz.objectIds) {
                    if (scanned >= maxScan) break
                    scanned++
                    for (ref in snapshot.getObject(id).outboundReferences) {
                        if (snapshot.isClass(ref.objectId)) continue
                        val targetClass = snapshot.getObject(ref.objectId).clazz.name
                        if (targetClass.startsWith("java.lang.ref.ReferenceQueue")) continue
                        if (!targetClass.contains("Reference")) continue
                        histogram.merge("${ref.name} -> $targetClass", 1, Int::plus)
                    }
                    if (scanned % 20000 == 0) println("  scanned $scanned...")
                }
            }
            println("scanned $scanned instances of $className")
            histogram.entries.sortedByDescending { it.value }.take(20)
                .forEach { println("  %,8d  %s".format(it.value, it.key)) }
        }

    /** Field-level view of a few instances, including MAT field names. */
    fun fields(hprof: String, className: String, limit: Int, onlyRefTargets: Boolean) =
        withSnapshot(hprof, withReader = true) { snapshot ->
            val classes = snapshot.getClassesByName(className, false) ?: error("class not found: $className")
            var shown = 0
            var scanned = 0
            for (clazz in classes) {
                for (id in clazz.objectIds) {
                    if (shown >= limit) break
                    val obj = snapshot.getObject(id)
                    val outbound = obj.outboundReferences.filter { !snapshot.isClass(it.objectId) }
                    if (onlyRefTargets && outbound.none {
                            snapshot.getObject(it.objectId).clazz.name.contains("Reference")
                        }) {
                        scanned++
                        continue
                    }
                    println("0x${obj.objectAddress.toString(16)} ${obj.clazz.name}")
                    for (ref in outbound) {
                        val target = snapshot.getObject(ref.objectId)
                        println("    .${ref.name} -> ${target.clazz.name} @0x${target.objectAddress.toString(16)}")
                    }
                    shown++
                    scanned++
                }
            }
            println("shown $shown instances of $className (scanned $scanned)")
        }

    /** BFS levels and shortest path to the first class matching a target substring. */
    fun trace(hprof: String, className: String, targets: List<String>, maxLevel: Int, maxObjs: Int) =
        withSnapshot(hprof) { snapshot ->
            val start = System.nanoTime()
            val classes = snapshot.getClassesByName(className, false) ?: error("class not found: $className")
            check(classes.isNotEmpty()) { "class not found: $className" }
            val roots = classes.flatMap { it.objectIds.asIterable() }.distinct()
            println("class $className: ${roots.size} instances (roots), targets=$targets")

            val n = snapshot.snapshotInfo.numberOfObjects
            val visited = BitSet(n)
            val parent = IntArray(n) { -1 }
            var frontier = IntArray(roots.size)
            var frontierSize = 0
            for (root in roots) {
                if (!visited.get(root)) {
                    visited.set(root)
                    frontier[frontierSize++] = root
                    parent[root] = -2
                }
            }
            var next = IntArray(1 shl 16)
            var nextSize = 0
            var total = frontierSize.toLong()
            var hub = -1
            var hubLevel = -1
            var level = 0
            while (frontierSize > 0 && level <= maxLevel && total < maxObjs) {
                val histogram = HashMap<String, Int>()
                for (i in 0 until frontierSize) {
                    val id = frontier[i]
                    val name = snapshot.getClassOf(id).name
                    histogram.merge(name, 1, Int::plus)
                    if (hub < 0 && targets.any { name.contains(it) }) {
                        hub = id
                        hubLevel = level
                    }
                }
                val elapsed = (System.nanoTime() - start) / 1_000_000_000.0
                println(
                    "level %2d: %,12d objects (cumulative %,12d)  [%.0fs]"
                        .format(level, frontierSize, total, elapsed)
                )
                histogram.entries.sortedByDescending { it.value }.take(6)
                    .forEach { (name, count) -> println("         %,12d  %s".format(count, name.take(100))) }
                nextSize = 0
                for (i in 0 until frontierSize) {
                    val source = frontier[i]
                    for (target in snapshot.getOutboundReferentIds(source)) {
                        if (target >= 0 && !visited.get(target) && !snapshot.isClass(target)) {
                            visited.set(target)
                            parent[target] = source
                            if (nextSize == next.size) next = next.copyOf(next.size * 2)
                            next[nextSize++] = target
                        }
                    }
                }
                val previous = frontier
                frontier = next
                next = previous
                frontierSize = nextSize
                level++
                total += frontierSize
            }
            println("stopped: level=$level cumulative=%,d".format(total))
            if (hub >= 0) {
                println("\nshortest path root -> hub (hub at level $hubLevel):")
                val path = ArrayList<Int>()
                var id = hub
                while (id >= 0) {
                    path.add(id)
                    id = parent[id]
                }
                path.reverse()
                for ((index, objectId) in path.withIndex()) {
                    val outbound = snapshot.getOutboundReferentIds(objectId).size
                    println("  %2d: %-90s out=%d".format(index, snapshot.getClassOf(objectId).name.take(90), outbound))
                }
            } else {
                println("\nno target hub found within $maxLevel levels / $maxObjs objects")
            }
        }

    private inline fun <T> withSnapshot(
        hprof: String,
        withReader: Boolean = false,
        block: (ISnapshot) -> T,
    ): T = MatHeapInput.open(hprof, withReader).use { block(it.snapshot) }
}
