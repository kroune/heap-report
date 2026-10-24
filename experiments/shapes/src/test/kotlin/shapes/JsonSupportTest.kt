package shapes

import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import shapes.mat.ExtractionFieldRule
import shapes.mat.ExtractionStats
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class JsonSupportTest {
    @Test
    fun `relax stats schema matches backend counters`() {
        val stats = RelaxStats().apply {
            relaxedObjects = 1
            classOnlyHiddenEdges = 2
            setChildrenHiddenEdges = 3
            setChildrenConcealedObjects = 4
        }
        val encoded = ShapesJson.encodeToString(stats)
        val fields = Json.parseToJsonElement(encoded).jsonObject.keys
        assertEquals(setOf("relaxedObjects", "classOnlyHiddenEdges",
            "setChildrenHiddenEdges", "setChildrenConcealedObjects"), fields)
        assertFalse("totalHidden" in fields)
        val emptyFields = Json.parseToJsonElement(ShapesJson.encodeToString(RelaxStats())).jsonObject.keys
        assertEquals(fields, emptyFields, "zero counters are part of the wire schema")
        assertEquals(2, ShapesJson.decodeFromString<RelaxStats>(encoded).classOnlyHiddenEdges)
    }

    @Test
    fun `extraction stats roundtrip preserves field names and escaping`() {
        val stats = ExtractionStats(
            scope = "retained", mapProjection = "raw", objects = 1, roots = 1,
            shallowTotal = 16, sourceEdges = 2, scopeBoundaryEdges = 3,
            projectedEdges = 4, projectedBoundaryEdges = 5, matRetainedObjects = 6,
            referenceFiltered = true, softReachableOnlyDropped = 7,
            softReachableOnlyDroppedShallow = 8, scopeFilteredOutObjects = 7,
            scopeFilteredOutShallow = 8, concealedFieldEdges = 9,
            fieldRules = listOf(ExtractionFieldRule("a.b.C", listOf("lazy", "quote\"field"))),
        )
        val encoded = ShapesJson.encodeToString(stats)
        val json = Json.parseToJsonElement(encoded).jsonObject
        assertEquals(1, json["fieldRules"]!!.jsonArray.size)
        assertEquals(stats, ShapesJson.decodeFromString<ExtractionStats>(encoded))
        assertEquals("a.b.C", stats.fieldRules.single().className)
    }
}
