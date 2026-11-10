package shapes.mat

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.eclipse.mat.collect.BitField
import org.eclipse.mat.collect.HashMapIntObject
import org.eclipse.mat.hprof.HprofHeapObjectReader
import org.eclipse.mat.hprof.HprofRandomAccessParser
import org.eclipse.mat.hprof.ui.HprofPreferences
import org.eclipse.mat.parser.IObjectReader
import org.eclipse.mat.parser.index.IndexManager
import org.eclipse.mat.parser.index.IndexReader
import org.eclipse.mat.parser.internal.SnapshotImpl
import org.eclipse.mat.parser.model.ClassImpl
import org.eclipse.mat.parser.model.ObjectArrayImpl
import org.eclipse.mat.parser.model.PrimitiveArrayImpl
import org.eclipse.mat.parser.model.XSnapshotInfo
import org.eclipse.mat.snapshot.ISnapshot
import org.eclipse.mat.util.VoidProgressListener
import shapes.GRAPH_MAGIC_NUMBER
import shapes.models.ClassName
import shapes.writeJson
import java.io.*
import java.lang.Long.bitCount
import java.nio.file.Files
import java.nio.file.Path
import java.util.*
import kotlin.contracts.contract

/**
 * Heap-dump extraction for structural shapes: pulls either the complete
 * non-class heap or one class's selected graph (SHAPES.md scope R) out of a
 * MAT snapshot and writes it as GRPH (see Graph.kt). Runs standalone against
 * pre-built MAT indexes —
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

    @Serializable
    sealed interface ExtractionMode {
        @Serializable
        sealed interface PartialExtraction : ExtractionMode {
            val className: ClassName

            @Serializable
            @SerialName("reachable")
            data class Reachable(override val className: ClassName) : PartialExtraction

            @Serializable
            @SerialName("retained")
            data class Retained(override val className: ClassName) : PartialExtraction
        }

        @Serializable
        @SerialName("heap")
        object WholeHeap : ExtractionMode
    }

    fun extract(
        hprof: String,
        out: Path,
        extractionMode: ExtractionMode,
        statsOut: Path? = null,
        boundaryStubs: Boolean = false,
        filterReferences: Boolean = true,
        fieldRules: Map<ClassName, Set<String>> = emptyMap(),
        wholeHeap: Boolean = false,
    ) {
        require(!wholeHeap || !boundaryStubs) { "--boundary-stubs is incompatible with --whole-heap" }
        // MAT's old marker initializes the Eclipse platform even for index-only
        // reads. The supported new marker works in our standalone snapshot.
        if (extractionMode is ExtractionMode.PartialExtraction.Retained)
            System.setProperty("org.eclipse.mat.parser.internal.snapshot.ObjectMarkerFactory.useOldMarker", "false")
        // withReader: filtering java.lang.ref.Reference.discovered needs field
        // names, and those need the real hprof object reader (standalone
        // subclass — HprofHeapObjectReader.open drags in OSGi preferences)
        val reader = open(hprof, withReader = true)
        try {
            val time0 = System.nanoTime()
            val rootIds = getRootIds(extractionMode, reader)

            // java.lang.ref.Reference mechanics are VM/library bookkeeping,
            // not program structure: `discovered` is the GC's discovered-list
            // linkage (already filtered before), and with filterReferences
            // also `referent`/`queue`/`next` — a soft/weak/phantom reference
            // does NOT retain its referent (the GC clears it under pressure),
            // so reference-kind edges must not pull objects into the scope
            // nor split shapes with GC state (observed: the pre-OOM GC had
            // cleared some SoftReferences, splitting otherwise identical
            // configurations). Fields DECLARED BY SUBCLASSES (e.g. a weak map
            // entry's strong `value`) are kept — they are payload, not
            // reference mechanics. Subtyping is exact (MAT class hierarchy).
            val refClassIds = reader.getClassesByName("java.lang.ref.Reference", true)
                .orEmpty()
                .mapTo(HashSet()) { it.objectId }
            val droppedRefFields = if (filterReferences) {
                REF_MECHANICS_FIELDS
            } else {
                setOf("discovered")
            }
            println("java.lang.ref.Reference subtypes: ${refClassIds.size} classes — filtered fields: $droppedRefFields")

            // Relax.kt level 3 (conceal-field:<name>): drop the named fields
            // of a class and all its subclasses — the surgical choice for a
            // lazy/cache field whose presence is state, not structure. The
            // edge never enters the graph, so its target may drop out of the
            // scope entirely. Subtyping is exact (MAT class hierarchy).
            // class object id -> fields to drop
            val concealFields = HashMap<Int, Set<String>>()
            for ((className, fields) in fieldRules) {
                var concealedFieldsCount = 0
                for (iClass in reader.getClassesByName(className.name, true).orEmpty()) {
                    concealFields[iClass.objectId] = concealFields[iClass.objectId].orEmpty() + fields
                    concealedFieldsCount++
                }
                println("conceal-field rule: $className + subclasses = $concealedFieldsCount classes, drop fields $fields")
                if (concealedFieldsCount == 0) {
                    println("conceal-field: WARNING $className matches no class in the dump")
                }
            }
            val referencePolicy = ReferencePolicy(
                indexedRefs = { id -> reader.getOutboundReferentIds(id) },
                rawNamedRefs = { id -> reader.getObject(id).outboundReferences.map { it.name to it.objectId } },
                isReference = { id -> reader.getClassOf(id).objectId in refClassIds },
                concealedFields = { id -> concealFields[reader.getClassOf(id).objectId] },
                filterReferences = filterReferences,
            )

            // java.lang.Class instances MISSING from the class table: the 9
            // primitive class objects (int.class..void.class) have no
            // ClassImpl entry, so isClass() misses them and they slip past
            // the class filter via real fields (MethodType.rtype ->
            // int.class). Class objects don't belong to R regardless.
            var leaks = IntArray(0)
            for (iClass in reader.getClassesByName("java.lang.Class", false).orEmpty())
                for (id in iClass.objectIds) {
                    if (!reader.isClass(id)) {
                        leaks = leaks.plus(id)
                    }
                }
            if (leaks.isNotEmpty())
                println("primitive class objects outside the class table: ${leaks.size} — also filtered")
            fun isExcludedClass(classId: Int): Boolean {
                if (reader.isClass(classId)) {
                    return true
                }
                if (classId in leaks) {
                    return true
                }
                return false
            }

            // phase 1: BFS over outbound refs, visited bitmap over MAT id space
            val n = reader.snapshotInfo.numberOfObjects
            val reachableObjects = BitSet(n)
            // Reachable BFS uses one dense list as both visited order and queue.
            // Retained scope is already selected by MAT and needs no id list at all.
            var retainedCount = 0
            run {
                val rIds = IntStack()
                when (extractionMode) {
                    is ExtractionMode.PartialExtraction.Reachable -> {
                        for (root in rootIds!!) {
                            if (!reachableObjects.get(root) && !isExcludedClass(root)) {
                                reachableObjects.set(root)
                                rIds.push(root)
                            }
                        }

                        var head = 0
                        var progressAt = 1_000_000
                        while (head < rIds.freeArrayPosition) {
                            val v = rIds.array[head++]
                            // MAT's outbound index includes the pseudo-reference to the
                            // object's java.lang.Class. Class objects/statics never enter R.
                            for (w in referencePolicy.refs(v)) {
                                if (w >= 0 && !reachableObjects.get(w) && !isExcludedClass(w)) {
                                    reachableObjects.set(w)
                                    rIds.push(w)
                                }
                            }
                            if (rIds.freeArrayPosition >= progressAt) {
                                val el = (System.nanoTime() - time0) / 1_000_000_000.0
                                println("  BFS: ${rIds.freeArrayPosition} objects, ${"%.0f".format(rIds.freeArrayPosition / el)}/s")
                                progressAt += 1_000_000
                            }
                        }
                    }

                    is ExtractionMode.PartialExtraction.Retained -> {
                        val retained = reader.getRetainedSet(rootIds!!.toIntArray(), VoidProgressListener())
                        retainedCount = retained.size
                        for (id in retained) {
                            if (!isExcludedClass(id)) {
                                reachableObjects.set(id)
                            }
                        }
                        println("retained: ${retained.size} MAT objects, ${reachableObjects.cardinality()} non-class objects")
                    }

                    ExtractionMode.WholeHeap -> {
                        for (id in 0 until n) {
                            if (isExcludedClass(id)) {
                                continue
                            }
                            reachableObjects.set(id)
                        }
                        println("heap: ${reachableObjects.cardinality()} non-class objects")
                    }
                }
                rIds.array = IntArray(1) // release the BFS buffer before the big allocations
            }
            // Scope is selected BEFORE map projection. Raw counts remain
            // available even though projected refs hide implementation links.
            val projection = MapProjection(
                className = { id -> reader.getClassOf(id).name },
                indexedRefs = { referencePolicy.refs(it) },
                namedRefs = referencePolicy::namedRefs,
            )
            val structuralClass = run {
                { id: Int -> ClassName(projection.structuralClassName(reader.getClassOf(id).name)) }
            }

            // GC semantics for the retained scope: an object that every path
            // to crosses a filtered edge is NOT retained by the queried class
            // (the GC would collect it — a soft/weak/phantom edge, or an edge
            // concealed as state by a level-3 field rule). Restrict the scope
            // to objects reachable from the roots over the filtered SOURCE
            // edges (before map projection). MAT's retained set still contains those
            // objects — it counts soft/weak edges as strong — so `visited`
            // must shrink here, and the totals diverge from MAT's retained
            // histogram ON PURPOSE (reported in the stats).
            var droppedSoftOnly = 0L
            var droppedSoftShallow = 0L
            if (extractionMode is ExtractionMode.PartialExtraction.Retained) {
                val strong = BitSet(n)
                val strongStack = IntStack()
                for (rootId in rootIds!!) {
                    if (reachableObjects.get(rootId)) {
                        strong.set(rootId)
                        strongStack.push(rootId)
                    }
                }
                while (strongStack.freeArrayPosition > 0) {
                    val rootId = strongStack.pop()
                    // Restrict MAT's retained set over the filtered SOURCE graph.
                    // Map projection changes shape semantics, never population.
                    for (w in referencePolicy.refs(rootId)) {
                        if (w >= 0 && reachableObjects.get(w) && !strong.get(w)) {
                            strong.set(w)
                            strongStack.push(w)
                        }
                    }
                }
                var reachableClassIndex = reachableObjects.nextSetBit(0)
                while (reachableClassIndex >= 0) {
                    if (!strong.get(reachableClassIndex)) {
                        droppedSoftOnly++
                        droppedSoftShallow += reader.getHeapSize(reachableClassIndex)
                    }
                    reachableClassIndex = reachableObjects.nextSetBit(reachableClassIndex + 1)
                }
                reachableObjects.clear()
                reachableObjects.or(strong)
                println(
                    "scope filter: $droppedSoftOnly objects ($droppedSoftShallow shallow bytes) " +
                            "dropped — unreachable over filtered source references"
                )
            }

            val t1 = System.nanoTime()
            val reachableClassesCount = reachableObjects.cardinality()
            println("scope $extractionMode: $reachableClassesCount objects in ${(t1 - time0) / 1_000_000} ms")
            if (reachableClassesCount == 0) {
                error("empty $extractionMode set")
            }

            // correctness guard: class objects must never enter R (their
            // outbound statics would drag in most of the heap). Cheap:
            // iterate the class table, not the visited bitmap.
            for (cl in reader.classes) {
                check(!reachableObjects.get(cl.objectId)) { "class object leaked into R: ${cl.name} @${cl.objectId}" }
            }
            for (id in leaks) {
                check(!reachableObjects.get(id)) { "primitive class object leaked into R: @$id" }
            }

            // dense id := rank in the visited set. remap via popcount-prefix
            // ranks over the visited bitmap: O(1) per ref, no ids array
            // (at 200M+ objects the array alone is ~1 GB and binary search
            // per ref is a cache-miss chain).
            val words = reachableObjects.toLongArray()
            val rank = IntArray(words.size + 1)
            for (i in words.indices) {
                rank[i + 1] = rank[i] + bitCount(words[i])
            }
            fun remap(w: Int): Int {
                val word = w ushr 6
                return rank[word] + bitCount(words[word] and ((1L shl (w and 63)) - 1))
            }

            // pass 1: class table + per-node class index (iterate set bits — no ids array)
            val classNames = ArrayList<ClassName>()
            val classIdxOf = HashMap<ClassName, Int>()
            val nodeClass = IntArray(reachableClassesCount)
            run {
                var processedClassesCount = 0
                var reachableClassIndex = reachableObjects.nextSetBit(0)
                while (reachableClassIndex >= 0) {
                    val className = structuralClass(reachableClassIndex)
                    val classIndex = classIdxOf.getOrPut(className) {
                        check(classNames.size < 65535) { "class table exceeds 65535 entries" }
                        classNames.add(className)
                        classNames.size - 1
                    }
                    nodeClass[processedClassesCount] = classIndex
                    processedClassesCount++
                    if (processedClassesCount % 5_000_000 == 0) {
                        println("  pass1: $processedClassesCount/$reachableClassesCount")
                    }
                    reachableClassIndex = reachableObjects.nextSetBit(reachableClassIndex + 1)
                }
            }
            // boundary stubs (experiment): a ref leaving the scope becomes a ref to
            // ONE synthetic leaf per targetcidx class (dense id nR + stubIdx, shallow 0,
            // no refs). Without this, the shape of an in-scope object depends on
            // whether its children happen to be shared with something outside
            // the retained set (DefaultModuleIdentifier: 165k LEAF vs 34k NODE).
            val stubOfClass = HashMap<Int, Int>()      // class object id -> stub idx
            val stubClassIdx = ArrayList<Int>()        // stub idx -> class table idx
            var stubRefs = 0L
            if (boundaryStubs) {
                check(extractionMode is ExtractionMode.PartialExtraction.Retained) { "--boundary-stubs only makes sense for retained scope" }
                var reachableClassIndex = reachableObjects.nextSetBit(0)
                while (reachableClassIndex >= 0) {
                    for (classId in referencePolicy.refs(reachableClassIndex)) {
                        if (!isExcludedClass(classId) && !reachableObjects.get(classId)) {
                            val iClass = reader.getClassOf(classId)
                            stubOfClass.getOrPut(iClass.objectId) {
                                val cidx = classIdxOf.getOrPut(ClassName(iClass.name)) {
                                    check(classNames.size < 65535) { "class table exceeds 65535 entries" }
                                    classNames.add(ClassName(iClass.name))
                                    classNames.size - 1
                                }
                                stubClassIdx.add(cidx)
                                stubClassIdx.size - 1
                            }
                            stubRefs++
                        }
                    }
                    reachableClassIndex = reachableObjects.nextSetBit(reachableClassIndex + 1)
                }
                println("boundary stubs: ${stubClassIdx.size} target classes, $stubRefs boundary refs")
                check(stubOfClass.keys.none { cid -> rootIds!!.any { it == cid } }) {
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
            var concealedFieldEdges = 0L

            // pass 2: emit GRPH (set-bit order; refs remapped O(1) via rank)
            Files.newOutputStream(out).use { raw ->
                val output = DataOutputStream(raw.buffered(1 shl 20))
                output.writeInt(GRAPH_MAGIC_NUMBER)
                output.writeLong(n.toLong())
                output.writeInt(classNames.size)
                for (className in classNames) {
                    val b = className.name.toByteArray(Charsets.UTF_8)
                    output.writeInt(b.size)
                    output.write(b)
                }
                output.writeInt(reachableClassesCount + nStubs)
                var oi = 0
                var visitedIndex = reachableObjects.nextSetBit(0)
                fun stubId(w: Int): Int = reachableClassesCount + stubOfClass.getValue(reader.getClassOf(w).objectId)
                while (visitedIndex >= 0) {
                    val nc = nodeClass[oi++]
                    output.writeInt(nc)
                    val shallow = reader.getHeapSize(visitedIndex)
                    shallowTotal += shallow
                    output.writeLong(shallow)
                    val read = referencePolicy.read(visitedIndex)
                    val original = read.refs
                    concealedFieldEdges += read.concealedFieldEdges
                    for (w in original) {
                        if (w >= 0 && !isExcludedClass(w)) {
                            if (reachableObjects.get(w)) {
                                sourceEdges++
                            } else {
                                check(extractionMode is ExtractionMode.PartialExtraction.Retained) { "ref $w of $visitedIndex escaped the reachable set" }
                                boundaryEdges++
                            }
                        }
                    }
                    val refs = projection.refs(visitedIndex) ?: original
                    var refCount = 0
                    for (w in refs) {
                        if (w >= 0 && !isExcludedClass(w)) {
                            if (reachableObjects.get(w) || boundaryStubs) refCount++ else projectedBoundaryEdges++
                        }
                    }
                    projectedEdges += refCount
                    output.writeInt(refCount)
                    for (w in refs) {
                        if (w >= 0 && !isExcludedClass(w)) {
                            if (reachableObjects.get(w)) {
                                output.writeInt(remap(w))
                            } else {
                                if (boundaryStubs)
                                    output.writeInt(stubId(w))
                            }
                        }
                    }
                    if (oi % 5_000_000 == 0) {
                        println("  pass2: $oi/$reachableClassesCount")
                    }
                    visitedIndex = reachableObjects.nextSetBit(visitedIndex + 1)
                }
                for (cidx in stubClassIdx) {   // stub rows: class, shallow 0, no refs
                    output.writeInt(cidx)
                    output.writeLong(0L)
                    output.writeInt(0)
                }
                output.flush()
            }
            val t3 = System.nanoTime()
            println("written: $out (${Files.size(out) / 1_000_000} MB), pass 2 in ${(t3 - t2) / 1_000_000} ms")
            println("edges: source=$sourceEdges projected=$projectedEdges outside-scope=$boundaryEdges")
            statsOut?.let { path ->
                writeJson(
                    path = path,
                    value = ExtractionStats(
                        extractionMode = extractionMode,
                        objects = reachableClassesCount,
                        roots = rootIds!!.size,
                        shallowTotal = shallowTotal,
                        sourceEdges = sourceEdges,
                        scopeBoundaryEdges = boundaryEdges,
                        projectedEdges = projectedEdges,
                        projectedBoundaryEdges = projectedBoundaryEdges,
                        matRetainedObjects = retainedCount,
                        referenceFiltered = filterReferences,
                        scopeFilteredOutObjects = droppedSoftOnly,
                        scopeFilteredOutShallow = droppedSoftShallow,
                        concealedFieldEdges = concealedFieldEdges,
                        fieldRules = fieldRules.entries.sortedBy { it.key.name }.map { (className, fields) ->
                            ExtractionFieldRule(className.name, fields.sorted())
                        },
                    )
                )
            }
        } finally {
            reader.dispose()
        }
    }

    private fun getRootIds(
        extractionMode: ExtractionMode,
        reader: ISnapshot
    ): List<Int>? {
        contract {
            (extractionMode is ExtractionMode.PartialExtraction) implies returnsNotNull()
        }
        return if (extractionMode is ExtractionMode.PartialExtraction) {
            val classesByName = reader.getClassesByName(
                extractionMode.className.name,
                false
            )
            require(classesByName != null && classesByName.isNotEmpty()) { "class not found: ${extractionMode.className}" }
            classesByName.flatMap { it.objectIds.asIterable() }.distinct().also {
                println("class ${extractionMode.className.name}: ${classesByName.size} class object(s), ${it.size} instances")
            }
        } else {
            null
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
                XSnapshotInfo::class.java, IObjectReader::class.java,
                HashMapIntObject::class.java, HashMapIntObject::class.java,
                HashMapIntObject::class.java, HashMapIntObject::class.java,
                BitField::class.java, IndexManager::class.java,
            )
            ctor.isAccessible = true
            val reader: IObjectReader =
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
            for (root in roots) {
                if (!visited.get(root)) {
                    visited.set(root)
                    frontier[fSize++] = root
                }
            }
            var next = IntArray(1 shl 16)
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
                    .forEach { (c, cnt) ->
                        println("         %,12d  %s".format(cnt, c.take(100)))
                    }
                var nSize = 0
                for (i in 0 until fSize) {
                    for (w in s.getInboundRefererIds(frontier[i])) {
                        if (w >= 0 && !visited.get(w)) {
                            visited.set(w)
                            if (nSize == next.size) {
                                next = next.copyOf(next.size * 2)
                            }
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
            val hist = TreeMap<String, Int>()
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
            hist.entries.sortedByDescending { it.value }.take(20)
                .forEach { println("  %,8d  %s".format(it.value, it.key)) }
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
    private class StandaloneReader : HprofHeapObjectReader() {
        override fun open(snapshot: ISnapshot) {
            val info = snapshot.snapshotInfo
            val version = org.eclipse.mat.hprof.describer.Version.valueOf(
                info.getProperty(VERSION_PROPERTY) as String
            )
            val olen = info.getProperty(HPROF_LENGTH_PROPERTY) as? Long
            val dump = HprofRandomAccessParser(
                File(info.path),
                info.prefix,
                version,
                info.identifierSize,
                olen ?: -1,
                HprofPreferences.HprofStrictness.STRICTNESS_STOP
            )
            val o2h = IndexReader.LongIndexReader(
                File(info.prefix + "o2hprof.index")
            )

            fun set(name: String, v: Any?) {
                val f = HprofHeapObjectReader::class.java.getDeclaredField(name)
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
                            s.getObject(it.objectId).clazz.name.contains("Reference")
                        }) {
                        scanned++; continue
                    }
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
    private object NoopObjectReader : IObjectReader {
        override fun open(snapshot: ISnapshot?) {}
        override fun read(objectId: Int, snapshot: ISnapshot?) =
            throw UnsupportedOperationException("getObject() is not available in standalone mode")

        override fun readPrimitiveArrayContent(
            array: PrimitiveArrayImpl?,
            from: Int,
            to: Int
        ): Any =
            throw UnsupportedOperationException()

        override fun readObjectArrayContent(
            array: ObjectArrayImpl?,
            from: Int,
            to: Int
        ): LongArray =
            throw UnsupportedOperationException()

        override fun <A> getAddon(addon: Class<A>?): A = throw UnsupportedOperationException()
        override fun close() {}
    }

    private class IntStack {
        var array = IntArray(1 shl 16)
        var freeArrayPosition = 0

        fun push(v: Int) {
            if (freeArrayPosition == array.size) {
                array = array.copyOf(array.size * 2)
            }
            array[freeArrayPosition] = v
            freeArrayPosition++
        }

        fun pop(): Int {
            val result = array[freeArrayPosition - 1]
            freeArrayPosition--
            return result
        }
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
                while (v >= 0) {
                    path.add(v); v = parent[v]
                }
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

    /** Fields of java.lang.ref.Reference that are VM/library mechanics, never
     *  program structure (see extract). Subclass-declared payload (e.g. a
     *  weak map entry's strong `value`) is NOT in this set and is kept. */
    private val REF_MECHANICS_FIELDS = setOf("referent", "queue", "next", "discovered")
}
