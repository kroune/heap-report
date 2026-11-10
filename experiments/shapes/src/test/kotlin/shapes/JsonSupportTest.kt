package shapes

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import shapes.extraction.ExtractionFieldRule
import shapes.extraction.ExtractionMode
import shapes.extraction.ExtractionStats
import shapes.models.ClassName
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
        assertEquals(
            expected = setOf(
                "relaxedObjects", "classOnlyHiddenEdges", "setChildrenHiddenEdges", "setChildrenConcealedObjects"
            ),
            actual = fields,
        )
        assertFalse("totalHidden" in fields)
        val emptyFields = Json.parseToJsonElement(ShapesJson.encodeToString(RelaxStats())).jsonObject.keys
        assertEquals(fields, emptyFields, "zero counters are part of the wire schema")
        assertEquals(2, ShapesJson.decodeFromString<RelaxStats>(encoded).classOnlyHiddenEdges)
    }

    @Test
    fun `extraction stats roundtrip preserves field names and escaping`() {
        val stats = ExtractionStats(
            extractionMode = ExtractionMode.PartialExtraction.Retained(ClassName("a.b.C")),
            objects = 1,
            roots = 1,
            shallowTotal = 16,
            sourceEdges = 2,
            scopeBoundaryEdges = 3,
            projectedEdges = 4,
            projectedBoundaryEdges = 5,
            matRetainedObjects = 6,
            referenceFiltered = true,
            scopeFilteredOutObjects = 7,
            scopeFilteredOutShallow = 8,
            concealedFieldEdges = 9,
            fieldRules = listOf(ExtractionFieldRule("a.b.C", listOf("lazy", "quote\"field"))),
        )
        val encoded = ShapesJson.encodeToString(stats)
        val json = Json.parseToJsonElement(encoded).jsonObject
        val extractionMode = json["extractionMode"]!!.jsonObject
        assertEquals("retained", extractionMode["type"]!!.jsonPrimitive.content)
        assertEquals("a.b.C", extractionMode["className"]!!.jsonPrimitive.content)
        assertEquals(1, json["fieldRules"]!!.jsonArray.size)
        assertEquals(stats, ShapesJson.decodeFromString<ExtractionStats>(encoded))
        assertEquals("a.b.C", stats.fieldRules.single().className)
    }
}
