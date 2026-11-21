package shapes.report

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
    fun `extraction stats keep the fields the backend validates`() {
        val stats = HprofExtractionStats(
            scope = "whole-heap",
            objects = 10,
            shallowTotal = 160,
            nodes = 4,
            leafClasses = 2,
            edges = 12,
            roots = 3,
            rawNonNullRefs = 20,
            classTargetRefs = 1,
            danglingRefs = 0,
            mechanicsDropped = 2,
            concealedDropped = 1,
            projectionDropped = 3,
            dedupDropped = 1,
            referenceMechanicsFiltered = true,
            foldedLeaves = true,
        )
        val encoded = ShapesJson.encodeToString(stats)
        val json = Json.parseToJsonElement(encoded).jsonObject

        // backend/mat/shapes.py reads these keys for publication + validation.
        assertEquals("whole-heap", json["scope"]!!.jsonPrimitive.content)
        assertEquals(10, json["objects"]!!.jsonPrimitive.content.toLong())
        assertEquals(160, json["shallowTotal"]!!.jsonPrimitive.content.toLong())
        assertEquals(12, json["edges"]!!.jsonPrimitive.content.toLong())
        assertEquals(3, json["roots"]!!.jsonPrimitive.content.toInt())
        assertEquals(20, json["rawNonNullRefs"]!!.jsonPrimitive.content.toLong())
        assertEquals(stats, ShapesJson.decodeFromString<HprofExtractionStats>(encoded))
    }
}
