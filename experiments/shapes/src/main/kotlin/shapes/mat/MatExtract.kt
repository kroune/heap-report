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

    fun extract(hprof: String, className: String, out: Path) {
        val s = open(hprof)
        try {
            val t0 = System.nanoTime()
            val classes = s.getClassesByName(className, false)
                ?: error("class not found: $className")
            check(classes.isNotEmpty()) { "class not found: $className" }
            val roots = classes.flatMap { it.objectIds.asIterable() }.distinct()
            println("class $className: ${classes.size} class object(s), ${roots.size} instances")

            // phase 1: BFS over outbound refs, visited bitmap over MAT id space
            val n = s.snapshotInfo.numberOfObjects
            val visited = BitSet(n)
            val stack = IntStack()
            val rIds = IntStack() // no boxing: at 100M objects boxed Ints alone OOM a 2g heap
            for (root in roots) if (!visited.get(root)) {
                visited.set(root); stack.push(root)
            }
            var progressAt = 1_000_000
            while (stack.sp > 0) {
                val v = stack.pop()
                rIds.push(v)
                for (w in s.getOutboundReferentIds(v)) {
                    // MAT's outbound index includes the pseudo-reference to the
                    // object's java.lang.Class: following it pulls in statics and
                    // (via the class loader) most of the heap. SHAPES.md's
                    // "dependencies in code" view is instance fields/array
                    // elements only, so class objects never enter R here.
                    if (w >= 0 && !visited.get(w) && !s.isClass(w)) {
                        visited.set(w); stack.push(w)
                    }
                }
                if (rIds.sp >= progressAt) {
                    val el = (System.nanoTime() - t0) / 1_000_000_000.0
                    println("  BFS: ${rIds.sp} objects, ${"%.0f".format(rIds.sp / el)}/s")
                    progressAt += 1_000_000
                }
            }
            val t1 = System.nanoTime()
            println("reachable: ${rIds.sp} objects, BFS in ${(t1 - t0) / 1_000_000} ms")
            if (rIds.sp == 0) error("empty reachable set")

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
            val nR = rIds.sp
            rIds.a = IntArray(1) // release the BFS buffer before the big allocations

            // pass 1: class table + per-node class index (iterate set bits — no ids array)
            val classNames = ArrayList<String>()
            val classIdxOf = HashMap<String, Int>()
            val nodeClass = IntArray(nR)
            var oi = 0
            var o = visited.nextSetBit(0)
            while (o >= 0) {
                val name = s.getClassOf(o).name
                nodeClass[oi++] = classIdxOf.getOrPut(name) {
                    classNames.add(name)
                    classNames.size - 1
                }
                if (oi % 5_000_000 == 0) println("  pass1: $oi/$nR")
                o = visited.nextSetBit(o + 1)
            }
            val t2 = System.nanoTime()
            println("classes: ${classNames.size}, pass 1 in ${(t2 - t1) / 1_000_000} ms")

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
                d.writeInt(nR)
                oi = 0
                o = visited.nextSetBit(0)
                while (o >= 0) {
                    d.writeInt(nodeClass[oi++])
                    d.writeLong(s.getHeapSize(o))
                    val refs = s.getOutboundReferentIds(o)
                    var refCount = 0
                    for (w in refs) if (!s.isClass(w)) refCount++
                    d.writeInt(refCount)
                    for (w in refs) {
                        if (s.isClass(w)) continue
                        check(visited.get(w)) { "ref $w of $o escaped the reachable set" }
                        d.writeInt(remap(w))
                    }
                    if (oi % 5_000_000 == 0) println("  pass2: $oi/$nR")
                    o = visited.nextSetBit(o + 1)
                }
                d.flush()
            }
            val t3 = System.nanoTime()
            println("written: $out (${Files.size(out) / 1_000_000} MB), pass 2 in ${(t3 - t2) / 1_000_000} ms")
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
    private fun open(hprof: String): ISnapshot {
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
            @Suppress("UNCHECKED_CAST")
            return ctor.newInstance(
                info, NoopObjectReader, classes, roots, rootsPerThread,
                loaderLabels, arrayObjects, indexManager,
            ) as ISnapshot
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

    // ids.size used below as rIds.sp equivalent

    private const val GRPH_MAGIC = 0x47525048 // "GRPH"
}
