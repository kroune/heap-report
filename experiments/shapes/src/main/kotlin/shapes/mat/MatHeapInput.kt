package shapes.mat

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
import shapes.input.HeapClass
import shapes.input.HeapInput
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.ObjectInputStream

/** MAT adapter for [HeapInput]. All standalone MAT/OSGi interop is confined here. */
class MatHeapInput private constructor(
    /** Exposed for MAT-specific diagnostics; extraction uses only HeapInput. */
    val snapshot: ISnapshot,
) : HeapInput {
    override val objectCount: Int get() = snapshot.snapshotInfo.numberOfObjects

    override fun classId(objectId: Int): Int = snapshot.getClassOf(objectId).objectId
    override fun className(objectId: Int): String = snapshot.getClassOf(objectId).name
    override fun shallowSize(objectId: Int): Long = snapshot.getHeapSize(objectId)
    override fun indexedReferences(objectId: Int): IntArray = snapshot.getOutboundReferentIds(objectId)
    override fun namedReferences(objectId: Int): List<Pair<String, Int>> =
        snapshot.getObject(objectId).outboundReferences.map { it.name to it.objectId }

    override fun isClassObject(objectId: Int): Boolean = snapshot.isClass(objectId)

    override fun classesNamed(name: String, includeSubclasses: Boolean): List<HeapClass> =
        snapshot.getClassesByName(name, includeSubclasses).orEmpty().map {
            HeapClass(it.objectId, it.name, it.objectIds)
        }

    override fun retainedSet(rootIds: IntArray): IntArray {
        // MAT's old marker initializes the Eclipse platform even for index-only
        // reads. The supported marker works in this standalone snapshot.
        System.setProperty("org.eclipse.mat.parser.internal.snapshot.ObjectMarkerFactory.useOldMarker", "false")
        return snapshot.getRetainedSet(rootIds, VoidProgressListener())
    }

    override fun close() = snapshot.dispose()

    companion object {
        /**
         * SnapshotImpl.readFromFile without OSGi: deserialize daemon.index,
         * install a standalone object reader, and invoke SnapshotImpl directly.
         * With [withReader]=false only index-backed diagnostics are available.
         */
        @JvmStatic
        fun open(hprof: String, withReader: Boolean = true): MatHeapInput {
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
                val reader: IObjectReader = if (withReader) StandaloneReader() else NoopObjectReader
                val snapshot = ctor.newInstance(
                    info, reader, classes, roots, rootsPerThread,
                    loaderLabels, arrayObjects, indexManager,
                ) as ISnapshot
                if (withReader) reader.open(snapshot)
                return MatHeapInput(snapshot)
            }
        }
    }

    /** Hprof reader without Eclipse preferences, OSGi, or enhancers. */
    private class StandaloneReader : HprofHeapObjectReader() {
        override fun open(snapshot: ISnapshot) {
            val info = snapshot.snapshotInfo
            val version = org.eclipse.mat.hprof.describer.Version.valueOf(
                info.getProperty(VERSION_PROPERTY) as String
            )
            val hprofLength = info.getProperty(HPROF_LENGTH_PROPERTY) as? Long
            val dump = HprofRandomAccessParser(
                File(info.path), info.prefix, version, info.identifierSize,
                hprofLength ?: -1, HprofPreferences.HprofStrictness.STRICTNESS_STOP,
            )
            val o2hprof = IndexReader.LongIndexReader(File(info.prefix + "o2hprof.index"))

            fun set(name: String, value: Any?) {
                val field = HprofHeapObjectReader::class.java.getDeclaredField(name)
                field.isAccessible = true
                field.set(this, value)
            }
            set("snapshot", snapshot)
            set("hprofDump", dump)
            set("o2hprof", o2hprof)
            set("enhancers", ArrayList<Any>())
        }
    }

    private object NoopObjectReader : IObjectReader {
        override fun open(snapshot: ISnapshot?) {}
        override fun read(objectId: Int, snapshot: ISnapshot?) =
            throw UnsupportedOperationException("getObject() is not available in index-only mode")

        override fun readPrimitiveArrayContent(array: PrimitiveArrayImpl?, from: Int, to: Int): Any =
            throw UnsupportedOperationException()

        override fun readObjectArrayContent(array: ObjectArrayImpl?, from: Int, to: Int): LongArray =
            throw UnsupportedOperationException()

        override fun <A> getAddon(addon: Class<A>?): A = throw UnsupportedOperationException()
        override fun close() {}
    }
}
