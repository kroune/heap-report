package shapes.mat

import org.eclipse.mat.snapshot.ISnapshot
import java.nio.file.Path

internal inline fun <T> withMatSnapshot(
    hprof: Path,
    readObjectFields: Boolean = false,
    block: (ISnapshot) -> T,
): T {
    val snapshot = MatSnapshotLoader.open(hprof, readObjectFields)
    return try {
        block(snapshot)
    } finally {
        snapshot.dispose()
    }
}
