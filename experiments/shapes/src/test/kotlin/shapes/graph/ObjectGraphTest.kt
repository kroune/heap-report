package shapes.graph

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ObjectGraphTest {
    @Test
    fun `class table cannot be mutated after graph construction`() {
        val graph = ObjectGraph.of(
            classes = arrayOf(ClassName("A"), ClassName("B")),
            shallow = ulongArrayOf(10U, 20U),
            refs = arrayOf(intArrayOf(), intArrayOf()),
        )

        @Suppress("UNCHECKED_CAST")
        val mutableClassNames = graph.classNames as MutableList<ClassName>
        assertFailsWith<UnsupportedOperationException> {
            mutableClassNames[0] = ClassName("Changed")
        }
        assertEquals(ClassName("A"), graph.className(0))
    }
}
