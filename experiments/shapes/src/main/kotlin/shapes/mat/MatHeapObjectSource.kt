package shapes.mat

import org.eclipse.mat.snapshot.ISnapshot
import org.eclipse.mat.util.VoidProgressListener
import shapes.graph.ClassName
import shapes.input.heap.HeapClassDescription
import shapes.input.heap.HeapObjectSource
import shapes.input.heap.NamedReference
import java.nio.file.Path

/** Adapts a MAT snapshot to the source-independent heap contract. */
class MatHeapObjectSource private constructor(
    private val snapshot: ISnapshot,
) : HeapObjectSource {
    override val objectCount: Int
        get() = snapshot.snapshotInfo.numberOfObjects

    override fun classId(objectId: Int): Int = snapshot.getClassOf(objectId).objectId

    override fun className(objectId: Int): ClassName = ClassName(snapshot.getClassOf(objectId).name)

    override fun shallowSize(objectId: Int): ULong = snapshot.getHeapSize(objectId).toULong()

    override fun indexedReferences(objectId: Int): IntArray = snapshot.getOutboundReferentIds(objectId)

    override fun objectAddress(objectId: Int): Long = snapshot.mapIdToAddress(objectId)

    override fun resolveObjectAddress(objectAddress: Long): Int = snapshot.mapAddressToId(objectAddress)

    override fun namedReferences(objectId: Int): List<NamedReference> =
        snapshot.getObject(objectId).outboundReferences.map { reference ->
            NamedReference(reference.name, -1, reference.objectAddress)
        }

    override fun isClassObject(objectId: Int): Boolean = snapshot.isClass(objectId)

    override fun classesNamed(
        name: ClassName,
        includeSubclasses: Boolean,
    ): List<HeapClassDescription> =
        snapshot.getClassesByName(name.name, includeSubclasses).orEmpty().map { heapClass ->
            HeapClassDescription(heapClass.objectId, ClassName(heapClass.name), heapClass.objectIds)
        }

    override fun retainedSet(rootIds: IntArray): IntArray {
        // The legacy marker initializes the Eclipse platform even for index-only reads.
        System.setProperty(
            "org.eclipse.mat.parser.internal.snapshot.ObjectMarkerFactory.useOldMarker",
            "false",
        )
        return snapshot.getRetainedSet(rootIds, VoidProgressListener())
    }

    override fun close() = snapshot.dispose()

    companion object {
        fun open(hprof: Path): MatHeapObjectSource =
            MatHeapObjectSource(MatSnapshotLoader.open(hprof, readObjectFields = true))
    }
}
