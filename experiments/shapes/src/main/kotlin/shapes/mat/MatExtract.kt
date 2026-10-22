package shapes.mat

import org.eclipse.mat.collect.BitField
import org.eclipse.mat.collect.HashMapIntObject
import org.eclipse.mat.parser.index.IndexManager
import org.eclipse.mat.parser.internal.SnapshotImpl
import org.eclipse.mat.parser.model.ClassImpl
import org.eclipse.mat.parser.model.XSnapshotInfo
import org.eclipse.mat.snapshot.ISnapshot
import org.eclipse.mat.util.VoidProgressListener
import java.io.BufferedInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.ObjectInputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.BitSet

/**
 * Heap-dump extraction for the shapes experiment: pulls the reachable object
 * graph (SHAPES.md scope R) for one class out of a MAT snapshot and writes it
 * as GRPH (see Graph.kt). Runs standalone against pre-built MAT indexes —
 * SnapshotFactory (the facade) resolves its implementation via the Eclipse
 * extension registry, so we instantiate SnapshotFactoryImpl directly; with
 * indexes present no parser extension is needed.
 *
 * Object ids are REMAPPED to dense 0-based ids here (GRPH stores dense ids).
 * SHAPES.md's "dense over MAT id space, no remapping" is a memory trade-off
 * for running inside the MAT JVM; in a separate analysis process remapping is
 * a pure win and keeps every structure ∝ |R|.
 */
object MatExtract {

    fun info(hprof: String) {
        val s = open(hprof)
        try {
            val i = s.snapshotInfo
            println("objects        : ${i.numberOfObjects}")
            println("used heap      : ${i.usedHeapSize}")
            println("classes        : ${i.numberOfClasses}")
            println("gc roots       : ${i.numberOfGCRoots}")
        } finally {
            s.dispose()
        }
    }

    fun histogram(hprof: String, top: Int) {
        val s = open(hprof)
        try {
            val h = s.getHistogram(VoidProgressListener())
            h.classHistogramRecords
                .sortedByDescending { it.numberOfObjects }
                .take(top)
                .forEach { r ->
                    println("%-90s n=%-10d shallow=%d".format(r.label.take(90), r.numberOfObjects, r.usedHeapSize))
                }
        } finally {
            s.dispose()
        }
    }

    fun extract(
        hprof: String, className: String, out: Path,
        scope: String = "reachable", normalizeMaps: Boolean = false, statsOut: Path? = null,
        boundaryStubs: Boolean = false,
    ) {
        require(scope == "reachable" || scope == "retained") { "scope must be retained or reachable" }
        // MAT's old marker initializes the Eclipse platform even for index-only
        // reads. The supported new marker works in our standalone snapshot.
        if (scope == "retained") System.setProperty(
            "org.eclipse.mat.parser.internal.snapshot.ObjectMarkerFactory.useOldMarker", "false")
        // withReader: filtering java.lang.ref.Reference.discovered needs field
        // names, and those need the real hprof object reader (standalone
        // subclass — HprofHeapObjectReader.open drags in OSGi preferences)
        val s = open(hprof, withReader = true)
        try {
            val t0 = System.nanoTime()
            val classes = s.getClassesByName(className, false)
                ?: error("class not found: $className")
            check(classes.isNotEmpty()) { "class not found: $className" }
            val roots = classes.flatMap { it.objectIds.asIterable() }.distinct()
            println("class $className: ${classes.size} class object(s), ${roots.size} instances")

            // java.lang.ref.Reference.discovered is the GC's discovered-list
            // linkage — VM bookkeeping captured mid-flight, not program
            // structure. It also poisons the exact collapse: a ~100k-link
            // list makes every list depth a distinct shape (30k layers for
            // 1 MB of actual data). Filtered here, at the source of truth.
            val refClassIds = (s.getClassesByName("java.lang.ref.Reference", true) ?: emptyList())
                .mapTo(HashSet()) { it.objectId }
            println("java.lang.ref.Reference subtypes: ${refClassIds.size} classes — .discovered edges filtered")

            // java.lang.Class instances MISSING from the class table: the 9
            // primitive class objects (int.class..void.class) have no
            // ClassImpl entry, so isClass() misses them and they slip past
            // the class filter via real fields (MethodType.rtype ->
            // int.class). Class objects don't belong to R regardless.
            var leaks = IntArray(0)
            for (cl in s.getClassesByName("java.lang.Class", false) ?: emptyList())
                for (id in cl.objectIds)
                    if (!s.isClass(id)) leaks = leaks.plus(id)
            if (leaks.isNotEmpty())
                println("primitive class objects outside the class table: ${leaks.size} — also filtered")
            fun isExcludedClass(w: Int): Boolean {
                if (s.isClass(w)) return true
                for (id in leaks) if (id == w) return true
                return false
            }

            // phase 1: BFS over outbound refs, visited bitmap over MAT id space
            val n = s.snapshotInfo.numberOfObjects
            val visited = BitSet(n)
            val stack = IntStack()
            val rIds = IntStack() // no boxing: at 100M objects boxed Ints alone OOM a 2g heap
            var retainedCount = 0
            if (scope == "retained") {
                val retained = s.getRetainedSet(roots.toIntArray(), VoidProgressListener())
                retainedCount = retained.size
                for (id in retained) if (!isExcludedClass(id)) visited.set(id)
                var retainedId = visited.nextSetBit(0)
                while (retainedId >= 0) {
                    rIds.push(retainedId)
                    retainedId = visited.nextSetBit(retainedId + 1)
                }
                println("retained: $retainedCount MAT objects, ${visited.cardinality()} non-class objects")
            } else for (root in roots) if (!visited.get(root)) {
                visited.set(root); stack.push(root)
            }
            var progressAt = 1_000_000
            while (stack.sp > 0) {
                val v = stack.pop()
                rIds.push(v)
                if (refClassIds.contains(s.getClassOf(v).objectId)) {
                    for (ref in s.getObject(v).outboundReferences) {
                        if (ref.name == "discovered") continue
                        val w = ref.objectId
                        if (w >= 0 && !visited.get(w) && !isExcludedClass(w)) {
                            visited.set(w); stack.push(w)
                        }
                    }
                } else {
                    for (w in s.getOutboundReferentIds(v)) {
                        // MAT's outbound index includes the pseudo-reference to the
                        // object's java.lang.Class: following it pulls in statics and
                        // (via the class loader) most of the heap. SHAPES.md's
                        // "dependencies in code" view is instance fields/array
                        // elements only, so class objects never enter R here.
                        if (w >= 0 && !visited.get(w) && !isExcludedClass(w)) {
                            visited.set(w); stack.push(w)
                        }
                    }
                }
                if (rIds.sp >= progressAt) {
                    val el = (System.nanoTime() - t0) / 1_000_000_000.0
                    println("  BFS: ${rIds.sp} objects, ${"%.0f".format(rIds.sp / el)}/s")
                    progressAt += 1_000_000
                }
            }
            val t1 = System.nanoTime()
            val nR = visited.cardinality()
            println("scope $scope: $nR objects in ${(t1 - t0) / 1_000_000} ms")
            if (nR == 0) error("empty $scope set")

            // correctness guard: class objects must never enter R (their
            // outbound statics would drag in most of the heap). Cheap:
            // iterate the class table, not the visited bitmap.
            for (cl in s.classes) check(!visited.get(cl.objectId)) {
                "class object leaked into R: ${cl.name} @${cl.objectId}"
            }
            for (id in leaks) check(!visited.get(id)) {
                "primitive class object leaked into R: @$id"
            }

            // dense id := rank in the visited set. remap via popcount-prefix
            // ranks over the visited bitmap: O(1) per ref, no ids array
            // (at 200M+ objects the array alone is ~1 GB and binary search
            // per ref is a cache-miss chain).
            val words = visited.toLongArray()
            val rank = IntArray(words.size + 1)
            for (i in words.indices) rank[i + 1] = rank[i] + java.lang.Long.bitCount(words[i])
            fun remap(w: Int): Int {
                val word = w ushr 6
                return rank[word] + java.lang.Long.bitCount(words[word] and ((1L shl (w and 63)) - 1))
            }
            rIds.a = IntArray(1) // release the BFS buffer before the big allocations

            // Scope is selected BEFORE projection. Raw counts remain available
            // even though projected refs intentionally hide map implementation links.
            fun sourceRefs(id: Int): IntArray =
                if (refClassIds.contains(s.getClassOf(id).objectId))
                    s.getObject(id).outboundReferences.filter { it.name != "discovered" }
                        .map { it.objectId }.toIntArray()
                else s.getOutboundReferentIds(id)
            val projection = if (normalizeMaps) MapProjection(
                { id -> s.getClassOf(id).name }, ::sourceRefs,
                { id -> s.getObject(id).outboundReferences.map { it.name to it.objectId } },
            ) else null
            val structuralClass = if (projection == null) ({ id: Int -> s.getClassOf(id).name })
            else ({ id: Int -> projection.structuralClassName(s.getClassOf(id).name) })

            // pass 1: class table + per-node class index (iterate set bits — no ids array)
            val classNames = ArrayList<String>()
            val classIdxOf = HashMap<String, Int>()
            val nodeClass = IntArray(nR)
            var oi = 0
            var o = visited.nextSetBit(0)
            while (o >= 0) {
                val cl = structuralClass(o)
                val cidx = classIdxOf.getOrPut(cl) {
                    classNames.add(cl)
                    classNames.size - 1
                }
                nodeClass[oi++] = cidx
                if (oi % 5_000_000 == 0) println("  pass1: $oi/$nR")
                o = visited.nextSetBit(o + 1)
            }
            // boundary stubs (experiment): a ref leaving the scope becomes a ref to
            // ONE synthetic leaf per target class (dense id nR + stubIdx, shallow 0,
            // no refs). Without this, the shape of an in-scope object depends on
            // whether its children happen to be shared with something outside
            // the retained set (DefaultModuleIdentifier: 165k LEAF vs 34k NODE).
            val stubOfClass = HashMap<Int, Int>()      // class object id -> stub idx
            val stubClassIdx = ArrayList<Int>()        // stub idx -> class table idx
            var stubRefs = 0L
            if (boundaryStubs) {
                check(scope == "retained") { "--boundary-stubs only makes sense for retained scope" }
                var o0 = visited.nextSetBit(0)
                while (o0 >= 0) {
                    for (w in sourceRefs(o0)) if (w >= 0 && !isExcludedClass(w) && !visited.get(w)) {
                        val clsObj = s.getClassOf(w)
                        stubOfClass.getOrPut(clsObj.objectId) {
                            val cidx = classIdxOf.getOrPut(clsObj.name) {
                                classNames.add(clsObj.name); classNames.size - 1
                            }
                            stubClassIdx.add(cidx)
                            stubClassIdx.size - 1
                        }
                        stubRefs++
                    }
                    o0 = visited.nextSetBit(o0 + 1)
                }
                println("boundary stubs: ${stubClassIdx.size} target classes, $stubRefs boundary refs")
                check(stubOfClass.keys.none { cid -> classes.any { it.objectId == cid } }) {
                    "a boundary target is an instance of the queried class — roots must all be in scope"
                }
            }
            val nStubs = stubClassIdx.size
            val t2 = System.nanoTime()
            println("classes: ${classNames.size}, pass 1 in ${(t2 - t1) / 1_000_000} ms")
            var sourceEdges = 0L
            var boundaryEdges = 0L
            var projectedEdges = 0L
            var shallowTotal = 0L
            var projectedBoundaryEdges = 0L

            // pass 2: emit GRPH (set-bit order; refs remapped O(1) via rank)
            Files.newOutputStream(out).use { raw ->
                val d = DataOutputStream(raw.buffered(1 shl 20))
                d.writeInt(GRPH_MAGIC)
                d.writeLong(n.toLong())
                d.writeInt(classNames.size)
                for (name in classNames) {
                    val b = name.toByteArray(Charsets.UTF_8)
                    d.writeInt(b.size); d.write(b)
                }
                d.writeInt(nR + nStubs)
                oi = 0
                o = visited.nextSetBit(0)
                fun stubId(w: Int): Int = nR + stubOfClass.getValue(s.getClassOf(w).objectId)
                while (o >= 0) {
                    val nc = nodeClass[oi++]
                    d.writeInt(nc)
                    val shallow = s.getHeapSize(o)
                    shallowTotal += shallow
                    d.writeLong(shallow)
                    val original = sourceRefs(o)
                    for (w in original) if (w >= 0 && !isExcludedClass(w)) {
                        if (visited.get(w)) sourceEdges++ else {
                            check(scope == "retained") { "ref $w of $o escaped the reachable set" }
                            boundaryEdges++
                        }
                    }
                    val refs = projection?.refs(o) ?: original
                    var refCount = 0
                    for (w in refs) if (w >= 0 && !isExcludedClass(w)) {
                        if (visited.get(w) || boundaryStubs) refCount++ else projectedBoundaryEdges++
                    }
                    projectedEdges += refCount
                    d.writeInt(refCount)
                    for (w in refs) if (w >= 0 && !isExcludedClass(w)) {
                        if (visited.get(w)) d.writeInt(remap(w))
                        else if (boundaryStubs) d.writeInt(stubId(w))
                    }
                    if (oi % 5_000_000 == 0) println("  pass2: $oi/$nR")
                    o = visited.nextSetBit(o + 1)
                }
                for (cidx in stubClassIdx) {   // stub rows: class, shallow 0, no refs
                    d.writeInt(cidx); d.writeLong(0L); d.writeInt(0)
                }
                d.flush()
            }
            val t3 = System.nanoTime()
            println("written: $out (${Files.size(out) / 1_000_000} MB), pass 2 in ${(t3 - t2) / 1_000_000} ms")
            println("edges: source=$sourceEdges projected=$projectedEdges outside-scope=$boundaryEdges")
            statsOut?.let { path ->
                Files.writeString(path, """{"scope":"$scope","mapProjection":"${if (normalizeMaps) "unordered-entries-v1" else "raw"}","objects":$nR,"roots":${roots.size},"shallowTotal":$shallowTotal,"sourceEdges":$sourceEdges,"scopeBoundaryEdges":$boundaryEdges,"projectedEdges":$projectedEdges,"projectedBoundaryEdges":$projectedBoundaryEdges,"matRetainedObjects":$retainedCount}""")
            }
        } finally {
            s.dispose()
        }
    }

    /**
     * SnapshotImpl.readFromFile without the OSGi bits: the factory resolves
     * the object reader via the Eclipse extension registry, which does not
     * exist outside an OSGi runtime, and the hprof reader itself drags in
     * Eclipse preferences. Everything we need is index-backed, so we
     * deserialize daemon.index ourselves, stub the object reader and call
     * the (private) SnapshotImpl constructor by reflection.
     */
    private fun open(hprof: String, withReader: Boolean = false): ISnapshot {
        val heapFile = File(hprof)
        val prefix = heapFile.absolutePath.removeSuffix(".hprof") + "."
        ObjectInputStream(BufferedInputStream(FileInputStream(prefix + "index"))).use { ois ->
            val version = ois.readUTF()
            require(version == "MAT_01") { "unknown MAT index version: $version" }
            val parserId = ois.readUTF()
            val info = ois.readObject() as XSnapshotInfo
            info.setProperty("\$heapFormat", parserId)
            info.setProperty("\$reopened", true)
            @Suppress("UNCHECKED_CAST")
            val classes = ois.readObject() as HashMapIntObject<ClassImpl>
            @Suppress("UNCHECKED_CAST")
            val roots = ois.readObject() as HashMapIntObject<Any>
            @Suppress("UNCHECKED_CAST")
            val rootsPerThread = ois.readObject() as HashMapIntObject<Any>
            @Suppress("UNCHECKED_CAST")
            val loaderLabels = ois.readObject() as HashMapIntObject<String>
            val arrayObjects = ois.readObject() as BitField
            info.setPrefix(prefix)
            if (heapFile.absoluteFile != File(info.path)) {
                val alt = File(heapFile.parentFile, File(info.path).name)
                info.path = (if (alt.exists()) alt else heapFile).absolutePath
            }
            val indexManager = IndexManager()
            indexManager.init(prefix)
            val ctor = SnapshotImpl::class.java.getDeclaredConstructor(
                XSnapshotInfo::class.java, org.eclipse.mat.parser.IObjectReader::class.java,
                HashMapIntObject::class.java, HashMapIntObject::class.java,
                HashMapIntObject::class.java, HashMapIntObject::class.java,
                BitField::class.java, IndexManager::class.java,
            )
            ctor.isAccessible = true
            val reader: org.eclipse.mat.parser.IObjectReader =
                if (withReader) StandaloneReader() else NoopObjectReader
            @Suppress("UNCHECKED_CAST")
            val snap = ctor.newInstance(
                info, reader, classes, roots, rootsPerThread,
                loaderLabels, arrayObjects, indexManager,
            ) as ISnapshot
            if (withReader) reader.open(snap)
            return snap
        }
    }

    /**
     * Sanity probe for the BFS class filter: print isClass() and the class
     * name for the first `limit` java.lang.Class instances (class objects).
     * If isClass is false for them, the extract BFS class filter is broken
     * and class objects (with their statics) leak into R.
     */
    fun isClassCheck(hprof: String, maxScan: Int) {
        val s = open(hprof, withReader = true)
        try {
            val classes = s.getClassesByName("java.lang.Class", false) ?: error("no Class")
            var n = 0
            var bad = 0
            for (clazz in classes) for (id in clazz.objectIds) {
                n++
                if (!s.isClass(id)) {
                    bad++
                    if (bad <= 20) println("  LEAK id=$id isClass=false classOf=${s.getClassOf(id).name}")
                }
            }
            println("checked $n java.lang.Class instances, isClass=false: $bad")
        } finally {
            s.dispose()
        }
    }

    /**
     * Reverse BFS over the inbound-reference index from instances of a
     * class: the set of objects that can REACH the class's instances. For a
     * member of the mega-SCC this must explode toward ~141M objects (every
     * SCC member reaches every member) — a quantitative check of the
     * mega-SCC, and the per-level referrer histogram shows WHICH classes
     * carry the back-paths (the "absorption channels").
     */
    fun reachBack(hprof: String, className: String, limit: Int, maxLevel: Int, maxObjs: Int) {
        val s = open(hprof)
        try {
            val t0 = System.nanoTime()
            val classes = s.getClassesByName(className, false) ?: error("class not found: $className")
            check(classes.isNotEmpty()) { "class not found: $className" }
            val roots = classes.flatMap { it.objectIds.asIterable() }.distinct().take(limit)
            println("reachback from $className: ${roots.size} seed instance(s)")

            val n = s.snapshotInfo.numberOfObjects
            val visited = BitSet(n)
            var frontier = IntArray(roots.size)
            var fSize = 0
            for (r in roots) if (!visited.get(r)) { visited.set(r); frontier[fSize++] = r }
            var next = IntArray(1 shl 16)
            var nSize = 0
            var total = fSize.toLong()
            var level = 0
            while (fSize > 0 && level <= maxLevel && total < maxObjs) {
                val hist = HashMap<String, Int>()
                for (i in 0 until fSize) {
                    hist.merge(s.getClassOf(frontier[i]).name, 1, Int::plus)
                }
                val el = (System.nanoTime() - t0) / 1_000_000_000.0
                println("level %2d: %,12d referrers (cumulative %,12d)  [%.0fs]".format(level, fSize, total, el))
                hist.entries.sortedByDescending { it.value }.take(8)
                    .forEach { (c, cnt) -> println("         %,12d  %s".format(cnt, c.take(100))) }
                nSize = 0
                for (i in 0 until fSize) {
                    for (w in s.getInboundRefererIds(frontier[i])) {
                        if (w >= 0 && !visited.get(w)) {
                            visited.set(w)
                            if (nSize == next.size) next = next.copyOf(next.size * 2)
                            next[nSize++] = w
                        }
                    }
                }
                val tmp = frontier; frontier = next; next = tmp
                fSize = nSize
                level++
                total += fSize
            }
            println("reachback total: $total objects reach $className instances")
        } finally {
            s.dispose()
        }
    }

    /**
     * Histogram of (field name -> target class) over all instance refs that
     * point at java.lang.ref.* objects (excluding the .queue ->
     * ReferenceQueue$Null noise). Answers "which field forms reference
     * chains" without dumping every instance.
     */
    fun refTargets(hprof: String, className: String, maxScan: Int) {
        val s = open(hprof, withReader = true)
        try {
            val classes = s.getClassesByName(className, false) ?: error("class not found: $className")
            val hist = java.util.TreeMap<String, Int>()
            var scanned = 0
            for (clazz in classes) for (id in clazz.objectIds) {
                if (scanned >= maxScan) break
                scanned++
                val obj = s.getObject(id)
                for (ref in obj.outboundReferences) {
                    if (s.isClass(ref.objectId)) continue
                    val tc = s.getObject(ref.objectId).clazz.name
                    if (tc.startsWith("java.lang.ref.ReferenceQueue")) continue
                    if (!tc.contains("Reference")) continue
                    hist.merge("${ref.name} -> $tc", 1, Int::plus)
                }
                if (scanned % 20000 == 0) println("  scanned $scanned...")
            }
            println("scanned $scanned instances of $className")
            hist.entries.sortedByDescending { it.value }.take(20).forEach { println("  %,8d  %s".format(it.value, it.key)) }
        } finally {
            s.dispose()
        }
    }

    /**
     * HprofHeapObjectReader.open() drags in Eclipse preferences + the OSGi
     * extension registry (HprofPlugin.getDefault() NPEs standalone). This
     * subclass re-implements open() with the default strictness and no
     * enhancers — everything it needs is the hprof file + o2hprof index.
     */
    private class StandaloneReader : org.eclipse.mat.hprof.HprofHeapObjectReader() {
        override fun open(snapshot: ISnapshot) {
            val info = snapshot.snapshotInfo
            val version = org.eclipse.mat.hprof.describer.Version.valueOf(
                info.getProperty(VERSION_PROPERTY) as String)
            val olen = info.getProperty(HPROF_LENGTH_PROPERTY) as? Long
            val dump = org.eclipse.mat.hprof.HprofRandomAccessParser(
                java.io.File(info.path), info.prefix, version, info.identifierSize,
                olen ?: -1,
                org.eclipse.mat.hprof.ui.HprofPreferences.HprofStrictness.STRICTNESS_STOP)
            val o2h = org.eclipse.mat.parser.index.IndexReader.LongIndexReader(
                java.io.File(info.prefix + "o2hprof.index"))
            fun set(name: String, v: Any?) {
                val f = org.eclipse.mat.hprof.HprofHeapObjectReader::class.java.getDeclaredField(name)
                f.isAccessible = true
                f.set(this, v)
            }
            set("snapshot", snapshot)
            set("hprofDump", dump)
            set("o2hprof", o2h)
            set("enhancers", ArrayList<Any>())
        }
    }

    /**
     * Field-level view of a few instances: outbound references WITH field
     * names (ObjectReference.getName). Needs the real HprofHeapObjectReader
     * (getObject reads the heap file); open() otherwise stays index-only.
     */
    fun fields(hprof: String, className: String, limit: Int, onlyRefTargets: Boolean) {
        val s = open(hprof, withReader = true)
        try {
            val classes = s.getClassesByName(className, false) ?: error("class not found: $className")
            var shown = 0
            var scanned = 0
            for (clazz in classes) {
                for (id in clazz.objectIds) {
                    if (shown >= limit) break
                    val obj = s.getObject(id)
                    val outs = obj.outboundReferences.filter { !s.isClass(it.objectId) }
                    if (onlyRefTargets && outs.none {
                            s.getObject(it.objectId).clazz.name.contains("Reference") }) { scanned++; continue }
                    println("0x${obj.objectAddress.toString(16)} ${obj.clazz.name}")
                    for (ref in outs) {
                        val t = s.getObject(ref.objectId)
                        println("    .${ref.name} -> ${t.clazz.name} @0x${t.objectAddress.toString(16)}")
                    }
                    shown++; scanned++
                }
            }
            println("shown $shown instances of $className (scanned $scanned)")
        } finally {
            s.dispose()
        }
    }

    /**
     * HprofHeapObjectReader.open() drags in Eclipse preferences (OSGi). Every
     * query this extractor uses is index-backed (outbound refs, shallow size,
     * class-of, histogram) and never touches the object reader, so a stub is
     * enough; only getObject()/array content reads would need the real one.
     */
    private object NoopObjectReader : org.eclipse.mat.parser.IObjectReader {
        override fun open(snapshot: ISnapshot?) {}
        override fun read(objectId: Int, snapshot: ISnapshot?) =
            throw UnsupportedOperationException("getObject() is not available in standalone mode")
        override fun readPrimitiveArrayContent(array: org.eclipse.mat.parser.model.PrimitiveArrayImpl?, from: Int, to: Int): Any =
            throw UnsupportedOperationException()
        override fun readObjectArrayContent(array: org.eclipse.mat.parser.model.ObjectArrayImpl?, from: Int, to: Int): LongArray =
            throw UnsupportedOperationException()
        override fun <A : Any?> getAddon(addon: Class<A>?): A = throw UnsupportedOperationException()
        override fun close() {}
    }

    private class IntStack {
        var a = IntArray(1 shl 16)
        var sp = 0
        fun push(v: Int) {
            if (sp == a.size) a = a.copyOf(a.size * 2)
            a[sp++] = v
        }
        fun pop(): Int = a[--sp]
    }

    /**
     * BFS level-by-level from all instances of [className]; prints per-level
     * size + top classes (shows where the reachable set explodes) and the
     * shortest path from a root to the first object matching any [targets]
     * substring (the "hub" — build-root-ish class). Answers "why does
     * reachable-from-X become the whole heap" with actual heap data.
     */
    fun trace(hprof: String, className: String, targets: List<String>, maxLevel: Int, maxObjs: Int) {
        val s = open(hprof)
        try {
            val t0 = System.nanoTime()
            val classes = s.getClassesByName(className, false) ?: error("class not found: $className")
            check(classes.isNotEmpty()) { "class not found: $className" }
            val roots = classes.flatMap { it.objectIds.asIterable() }.distinct()
            println("class $className: ${roots.size} instances (roots), targets=$targets")

            val n = s.snapshotInfo.numberOfObjects
            val visited = BitSet(n)
            val parent = IntArray(n) { -1 } // ~1 GB at 260M objects; -2 = root sentinel
            var frontier = IntArray(roots.size)
            var fSize = 0
            for (r in roots) if (!visited.get(r)) {
                visited.set(r); frontier[fSize++] = r; parent[r] = -2
            }
            var next = IntArray(1 shl 16)
            var nSize = 0
            var total = fSize.toLong()
            var hub = -1
            var hubLevel = -1
            var level = 0
            while (fSize > 0 && level <= maxLevel && total < maxObjs) {
                val hist = HashMap<String, Int>()
                for (i in 0 until fSize) {
                    val v = frontier[i]
                    val cn = s.getClassOf(v).name
                    hist.merge(cn, 1, Int::plus)
                    if (hub < 0 && targets.any { cn.contains(it) }) {
                        hub = v; hubLevel = level
                    }
                }
                val el = (System.nanoTime() - t0) / 1_000_000_000.0
                println("level %2d: %,12d objects (cumulative %,12d)  [%.0fs]".format(level, fSize, total, el))
                hist.entries.sortedByDescending { it.value }.take(6)
                    .forEach { (c, cnt) -> println("         %,12d  %s".format(cnt, c.take(100))) }
                nSize = 0
                for (i in 0 until fSize) {
                    val v = frontier[i]
                    for (w in s.getOutboundReferentIds(v)) {
                        if (w >= 0 && !visited.get(w) && !s.isClass(w)) {
                            visited.set(w); parent[w] = v
                            if (nSize == next.size) next = next.copyOf(next.size * 2)
                            next[nSize++] = w
                        }
                    }
                }
                val tmp = frontier; frontier = next; next = tmp
                fSize = nSize
                total += fSize
                level++
            }
            println("stopped: level=$level cumulative=%,d".format(total))
            if (hub >= 0) {
                println("\nshortest path root -> hub (hub at level $hubLevel):")
                val path = ArrayList<Int>()
                var v = hub
                while (v >= 0) { path.add(v); v = parent[v] }
                path.reverse()
                for ((i, id) in path.withIndex()) {
                    val out = s.getOutboundReferentIds(id).size
                    println("  %2d: %-90s out=%d".format(i, s.getClassOf(id).name.take(90), out))
                }
            } else {
                println("\nno target hub found within $maxLevel levels / $maxObjs objects")
            }
        } finally {
            s.dispose()
        }
    }

    // ids.size used below as rIds.sp equivalent

    private const val GRPH_MAGIC = 0x47525048 // "GRPH"
}
