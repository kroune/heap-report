package shapes.cli

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import shapes.extraction.ConcealedFieldRuleStats
import shapes.extraction.ExtractionStats
import shapes.extraction.HeapExtractionScope
import shapes.graph.ClassName
import shapes.relaxation.ShapeRelaxationStats
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class JsonReportWriterTest {
    @Test
    fun `relax stats schema matches backend counters`() {
        val stats = ShapeRelaxationStats().apply {
            relaxedObjects = 1
            classOnlyHiddenEdges = 2
            setChildrenHiddenEdges = 3
            setChildrenConcealedObjects = 4
        }
        val encoded = ShapesJson.encodeToString(stats)
        val fields = Json.parseToJsonElement(encoded).jsonObject.keys
        assertEquals(
            expected = setOf(
                "relaxedObjects",
                "classOnlyHiddenEdges",
                "setChildrenHiddenEdges",
                "setChildrenConcealedObjects",
            ),
            actual = fields,
        )
        assertFalse("totalHidden" in fields)

        val emptyFields = Json.parseToJsonElement(
            ShapesJson.encodeToString(ShapeRelaxationStats())
        ).jsonObject.keys
        assertEquals(fields, emptyFields)
        assertEquals(
            2,
            ShapesJson.decodeFromString<ShapeRelaxationStats>(encoded).classOnlyHiddenEdges,
        )
    }

    @Test
    fun `extraction stats roundtrip preserves field names and escaping`() {
        val stats = ExtractionStats(
            scope = HeapExtractionScope.RetainedByClass(ClassName("a.b.C")),
            selectedObjectCount = 1,
            rootCount = 1,
            totalShallowSize = 16,
            sourceReferenceCount = 2,
            scopeBoundaryReferenceCount = 3,
            projectedReferenceCount = 4,
            projectedBoundaryReferenceCount = 5,
            sourceRetainedObjectCount = 6,
            referenceMechanicsFiltered = true,
            scopeFilteredOutObjectCount = 7,
            scopeFilteredOutShallowSize = 8,
            concealedFieldReferenceCount = 9,
            concealedFieldRules = listOf(
                ConcealedFieldRuleStats("a.b.C", listOf("lazy", "quote\"field"))
            ),
        )
        val encoded = ShapesJson.encodeToString(stats)
        val json = Json.parseToJsonElement(encoded).jsonObject

        val extractionMode = json["extractionMode"]!!.jsonObject
        assertEquals("retained", extractionMode["type"]!!.jsonPrimitive.content)
        assertEquals("a.b.C", extractionMode["className"]!!.jsonPrimitive.content)
        assertEquals(1, json["fieldRules"]!!.jsonArray.size)
        assertEquals(stats, ShapesJson.decodeFromString<ExtractionStats>(encoded))
        assertEquals("a.b.C", stats.concealedFieldRules.single().className)
    }
}
