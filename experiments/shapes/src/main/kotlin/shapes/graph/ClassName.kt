package shapes.graph

import kotlinx.serialization.Serializable

@Serializable
@JvmInline
value class ClassName(val name: String) {
    // prevent Boxing of [name]
    @Suppress("NOTHING_TO_INLINE")
    inline operator fun compareTo(other: ClassName): Int = name.compareTo(other.name)

    override fun toString(): String = name
}
