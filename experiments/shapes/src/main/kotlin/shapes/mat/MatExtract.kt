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
            val rIds = ArrayList<Int>(1 shl 20)
            for (root in roots) if (!visited.get(root)) {
                visited.set(root); stack.push(root)
            }
            var progressAt = 1_000_000
            while (stack.sp > 0) {
                val v = stack.pop()
                rIds.add(v)
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
                if (rIds.size >= progressAt) {
                    val el = (System.nanoTime() - t0) / 1_000_000_000.0
                    println("  BFS: ${rIds.size} objects, ${"%.0f".format(rIds.size / el)}/s")
                    progressAt += 1_000_000
                }
            }
            val t1 = System.nanoTime()
            println("reachable: ${rIds.size} objects, BFS in ${(t1 - t0) / 1_000_000} ms")
            if (rIds.isEmpty()) error("empty reachable set")

            // dense id := position in the sorted id list; remap = binary search
            val ids = rIds.toIntArray()
            ids.sort()
            rIds.clear()

            // pass 1: class table + per-node class index
            val classNames = ArrayList<String>()
            val classIdxOf = HashMap<String, Int>()
            val nodeClass = IntArray(ids.size)
            for (i in ids.indices) {
                val name = s.getClassOf(ids[i]).name
                nodeClass[i] = classIdxOf.getOrPut(name) {
                    classNames.add(name)
                    classNames.size - 1
                }
                if (i > 0 && i % 5_000_000 == 0) println("  pass1: $i/${ids.size}")
            }
            val t2 = System.nanoTime()
            println("classes: ${classNames.size}, pass 1 in ${(t2 - t1) / 1_000_000} ms")

            // pass 2: emit GRPH (ids ascending; refs remapped via binary search)
            Files.newOutputStream(out).use { raw ->
                val d = DataOutputStream(raw.buffered(1 shl 20))
                d.writeInt(GRPH_MAGIC)
                d.writeLong(n.toLong())
                d.writeInt(classNames.size)
                for (name in classNames) {
                    val b = name.toByteArray(Charsets.UTF_8)
                    d.writeInt(b.size); d.write(b)
                }
                d.writeInt(ids.size)
                for (i in ids.indices) {
                    d.writeInt(nodeClass[i])
                    d.writeLong(s.getHeapSize(ids[i]))
                    val refs = s.getOutboundReferentIds(ids[i])
                    var refCount = 0
                    for (w in refs) if (!s.isClass(w)) refCount++
                    d.writeInt(refCount)
                    for (w in refs) {
                        if (s.isClass(w)) continue
                        val di = java.util.Arrays.binarySearch(ids, w)
                        check(di >= 0) { "ref $w of ${ids[i]} escaped the reachable set" }
                        d.writeInt(di)
                    }
                    if (i > 0 && i % 5_000_000 == 0) println("  pass2: $i/${ids.size}")
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

    private const val GRPH_MAGIC = 0x47525048 // "GRPH"
}
