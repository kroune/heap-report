package shapes.graph

import kotlinx.serialization.Serializable

@Serializable
@JvmInline
value class ClassName(val name: String) {
    override fun toString(): String = name
}
