package shapes.models

import kotlinx.serialization.Serializable

@Serializable
@JvmInline
value class ClassName(
    val name: String
)