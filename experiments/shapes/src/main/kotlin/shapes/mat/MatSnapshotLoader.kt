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
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.ObjectInputStream
import java.nio.file.Path

/** Opens indexed MAT snapshots without requiring an OSGi runtime. */
object MatSnapshotLoader {
    /**
     * Deserializes MAT indexes and installs an object reader that does not use
     * Eclipse preferences. With [readObjectFields] disabled, only index-backed
     * queries are available.
     */
    fun open(hprof: Path, readObjectFields: Boolean = true): ISnapshot {
        val heapFile = hprof.toFile()
        val indexPrefix = heapFile.absolutePath.removeSuffix(".hprof") + "."
        ObjectInputStream(
            BufferedInputStream(FileInputStream(indexPrefix + "index"))
        ).use { serializedIndex ->
            val version = serializedIndex.readUTF()
            require(version == "MAT_01") { "unknown MAT index version: $version" }

            val parserId = serializedIndex.readUTF()
            val snapshotInfo = serializedIndex.readObject() as XSnapshotInfo
            snapshotInfo.setProperty("\$heapFormat", parserId)
            snapshotInfo.setProperty("\$reopened", true)

            @Suppress("UNCHECKED_CAST")
            val classes = serializedIndex.readObject() as HashMapIntObject<ClassImpl>
            @Suppress("UNCHECKED_CAST")
            val roots = serializedIndex.readObject() as HashMapIntObject<Any>
            @Suppress("UNCHECKED_CAST")
            val rootsPerThread = serializedIndex.readObject() as HashMapIntObject<Any>
            @Suppress("UNCHECKED_CAST")
            val loaderLabels = serializedIndex.readObject() as HashMapIntObject<String>
            val arrayObjects = serializedIndex.readObject() as BitField

            snapshotInfo.setPrefix(indexPrefix)
            relocateHeapFileIfNecessary(heapFile, snapshotInfo)

            val indexManager = IndexManager()
            indexManager.init(indexPrefix)
            val constructor = SnapshotImpl::class.java.getDeclaredConstructor(
                XSnapshotInfo::class.java,
                IObjectReader::class.java,
                HashMapIntObject::class.java,
                HashMapIntObject::class.java,
                HashMapIntObject::class.java,
                HashMapIntObject::class.java,
                BitField::class.java,
                IndexManager::class.java,
            )
            constructor.isAccessible = true

            val objectReader = if (readObjectFields) HprofReaderWithoutOsgi() else IndexOnlyObjectReader
            val snapshot = constructor.newInstance(
                snapshotInfo,
                objectReader,
                classes,
                roots,
                rootsPerThread,
                loaderLabels,
                arrayObjects,
                indexManager,
            ) as ISnapshot
            if (readObjectFields) objectReader.open(snapshot)
            return snapshot
        }
    }

    private fun relocateHeapFileIfNecessary(heapFile: File, snapshotInfo: XSnapshotInfo) {
        if (heapFile.absoluteFile == File(snapshotInfo.path)) return
        val siblingFile = File(heapFile.parentFile, File(snapshotInfo.path).name)
        snapshotInfo.path = (if (siblingFile.exists()) siblingFile else heapFile).absolutePath
    }

    private class HprofReaderWithoutOsgi : HprofHeapObjectReader() {
        override fun open(snapshot: ISnapshot) {
            val snapshotInfo = snapshot.snapshotInfo
            val version = org.eclipse.mat.hprof.describer.Version.valueOf(
                snapshotInfo.getProperty(VERSION_PROPERTY) as String
            )
            val hprofLength = snapshotInfo.getProperty(HPROF_LENGTH_PROPERTY) as? Long
            val heapDump = HprofRandomAccessParser(
                File(snapshotInfo.path),
                snapshotInfo.prefix,
                version,
                snapshotInfo.identifierSize,
                hprofLength ?: -1,
                HprofPreferences.HprofStrictness.STRICTNESS_STOP,
            )
            val hprofIndex = IndexReader.LongIndexReader(
                File(snapshotInfo.prefix + "o2hprof.index")
            )

            setInheritedField("snapshot", snapshot)
            setInheritedField("hprofDump", heapDump)
            setInheritedField("o2hprof", hprofIndex)
            setInheritedField("enhancers", ArrayList<Any>())
        }

        private fun setInheritedField(name: String, value: Any?) {
            val field = HprofHeapObjectReader::class.java.getDeclaredField(name)
            field.isAccessible = true
            field.set(this, value)
        }
    }

    private object IndexOnlyObjectReader : IObjectReader {
        override fun open(snapshot: ISnapshot?) = Unit

        override fun read(objectId: Int, snapshot: ISnapshot?) =
            throw UnsupportedOperationException("getObject() is not available in index-only mode")

        override fun readPrimitiveArrayContent(
            array: PrimitiveArrayImpl?,
            from: Int,
            to: Int,
        ): Any = throw UnsupportedOperationException()

        override fun readObjectArrayContent(
            array: ObjectArrayImpl?,
            from: Int,
            to: Int,
        ): LongArray = throw UnsupportedOperationException()

        override fun <A> getAddon(addon: Class<A>?): A = throw UnsupportedOperationException()

        override fun close() = Unit
    }
}
