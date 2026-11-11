package shapes.cli

import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import java.nio.file.Files
import java.nio.file.Path

internal val ShapesJson = Json { encodeDefaults = true }

internal inline fun <reified T> writeJsonReport(path: Path, value: T) {
    Files.writeString(path, ShapesJson.encodeToString(value))
}
