package shapes

import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import java.nio.file.Files
import java.nio.file.Path

/** Stable machine-readable JSON emitted by CLI pipeline stages. */
internal val ShapesJson = Json { encodeDefaults = true }

internal inline fun <reified T> writeJson(path: Path, value: T) {
    Files.writeString(path, ShapesJson.encodeToString(value))
}
