package io.github.mbbhalla.agentio.core.common

import aws.smithy.kotlin.runtime.content.Document
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

// Test data classes
data class AnotherObject(val value: String) {
    override fun toString() = "AnotherObject($value)"
}

data class YetAnotherObject(val id: Int, val name: String) {
    override fun toString() = "YetAnotherObject($id, '$name')"
}

data class DT(
    val x: Set<AnotherObject>,
    val y: YetAnotherObject,
    val z: String,
) {
    override fun toString() = "DT(x=$x, y=$y, z='$z')"
}

data class SimpleData(val name: String, val count: Int)
data class NullableData(val name: String?, val value: Int)

internal class ExtensionsTest {
    // Tests for JsonObject.toDocument() method
    @Test
    @DisplayName("Should convert JsonPrimitive int to Document.Number")
    fun testJsonPrimitiveIntToDocument() {
        val jsonObject = JsonObject(
            mapOf(
                "intValue" to JsonPrimitive(42),
            ),
        )

        val result = jsonObject.toDocument()

        assertTrue(result is Document.Map)
        val mapResult = result as Document.Map
        val intValue = mapResult.value["intValue"]
        assertTrue(intValue is Document.Number)
        assertEquals(42, (intValue as Document.Number).value)
    }

    @Test
    @DisplayName("Should convert JsonPrimitive long to Document.Number")
    fun testJsonPrimitiveLongToDocument() {
        val jsonObject = JsonObject(
            mapOf(
                "longValue" to JsonPrimitive(9223372036854775807L), // Long.MAX_VALUE
            ),
        )

        val result = jsonObject.toDocument()

        assertTrue(result is Document.Map)
        val mapResult = result as Document.Map
        val longValue = mapResult.value["longValue"]
        assertTrue(longValue is Document.Number)
        assertEquals(9223372036854775807L, (longValue as Document.Number).value)
    }

    @Test
    @DisplayName("Should convert JsonPrimitive double to Document.Number")
    fun testJsonPrimitiveDoubleToDocument() {
        val jsonObject = JsonObject(
            mapOf(
                "doubleValue" to JsonPrimitive(3.14159),
            ),
        )

        val result = jsonObject.toDocument()

        assertTrue(result is Document.Map)
        val mapResult = result as Document.Map
        val doubleValue = mapResult.value["doubleValue"]
        assertTrue(doubleValue is Document.Number)
        assertEquals(3.14159, (doubleValue as Document.Number).value)
    }

    @Test
    @DisplayName("Should convert JsonPrimitive float to Document.Number")
    fun testJsonPrimitiveFloatToDocument() {
        val jsonObject = JsonObject(
            mapOf(
                "floatValue" to JsonPrimitive(2.718f),
            ),
        )

        val result = jsonObject.toDocument()

        assertTrue(result is Document.Map)
        val mapResult = result as Document.Map
        val floatValue = mapResult.value["floatValue"]
        assertTrue(floatValue is Document.Number)
        // JsonPrimitive stores floats as doubles internally, so we use delta comparison
        val actualValue = (floatValue as Document.Number).value as Double
        assertEquals(2.718, actualValue, 0.001)
    }

    @Test
    @DisplayName("Should convert JsonPrimitive boolean to Document.Boolean")
    fun testJsonPrimitiveBooleanToDocument() {
        val jsonObject = JsonObject(
            mapOf(
                "boolValue" to JsonPrimitive(true),
            ),
        )

        val result = jsonObject.toDocument()

        assertTrue(result is Document.Map)
        val mapResult = result as Document.Map
        val boolValue = mapResult.value["boolValue"]
        assertTrue(boolValue is Document.Boolean)
        assertEquals(true, (boolValue as Document.Boolean).value)
    }

    @Test
    @DisplayName("Should handle JsonPrimitive fallback to string")
    fun testJsonPrimitiveFallbackToString() {
        // Create a custom JsonPrimitive that doesn't match any specific type
        // This is tricky since JsonPrimitive is sealed, but we can create one that falls through
        val jsonObject = JsonObject(
            mapOf(
                "fallbackValue" to JsonPrimitive("not-a-number-or-boolean"),
            ),
        )

        val result = jsonObject.toDocument()

        assertTrue(result is Document.Map)
        val mapResult = result as Document.Map
        val fallbackValue = mapResult.value["fallbackValue"]
        assertTrue(fallbackValue is Document.String)
        assertEquals("not-a-number-or-boolean", (fallbackValue as Document.String).value)
    }

    @Test
    @DisplayName("Should convert JsonArray to Document.List recursively")
    fun testJsonArrayToDocumentList() {
        val jsonObject = JsonObject(
            mapOf(
                "arrayValue" to JsonArray(
                    listOf(
                        JsonPrimitive(1),
                        JsonPrimitive("string"),
                        JsonPrimitive(true),
                        JsonObject(
                            mapOf(
                                "nested" to JsonPrimitive("value"),
                            ),
                        ),
                    ),
                ),
            ),
        )

        val result = jsonObject.toDocument()

        assertTrue(result is Document.Map)
        val mapResult = result as Document.Map
        val arrayValue = mapResult.value["arrayValue"]
        assertTrue(arrayValue is Document.List)

        val listResult = arrayValue as Document.List
        assertEquals(4, listResult.value.size)

        // Check each element in the array
        assertTrue(listResult.value[0] is Document.Number)
        assertEquals(1, (listResult.value[0] as Document.Number).value)

        assertTrue(listResult.value[1] is Document.String)
        assertEquals("string", (listResult.value[1] as Document.String).value)

        assertTrue(listResult.value[2] is Document.Boolean)
        assertEquals(true, (listResult.value[2] as Document.Boolean).value)

        assertTrue(listResult.value[3] is Document.Map)
        val nestedMap = listResult.value[3] as Document.Map
        assertTrue(nestedMap.value["nested"] is Document.String)
        assertEquals("value", (nestedMap.value["nested"] as Document.String).value)
    }

    @Test
    @DisplayName("Should handle complex nested structures with arrays and objects")
    fun testComplexNestedStructure() {
        val jsonObject = JsonObject(
            mapOf(
                "numbers" to JsonArray(
                    listOf(
                        JsonPrimitive(1),
                        JsonPrimitive(2L),
                        JsonPrimitive(3.14),
                        JsonPrimitive(4.5f),
                    ),
                ),
                "booleans" to JsonArray(
                    listOf(
                        JsonPrimitive(true),
                        JsonPrimitive(false),
                    ),
                ),
                "mixed" to JsonArray(
                    listOf(
                        JsonPrimitive("text"),
                        JsonPrimitive(42),
                        JsonObject(
                            mapOf(
                                "inner" to JsonPrimitive("value"),
                            ),
                        ),
                    ),
                ),
            ),
        )

        val result = jsonObject.toDocument()

        assertTrue(result is Document.Map)
        val mapResult = result as Document.Map

        // Test numbers array
        val numbersArray = mapResult.value["numbers"] as Document.List
        assertEquals(4, numbersArray.value.size)
        assertTrue(numbersArray.value.all { it is Document.Number })

        // Test booleans array
        val booleansArray = mapResult.value["booleans"] as Document.List
        assertEquals(2, booleansArray.value.size)
        assertTrue(booleansArray.value.all { it is Document.Boolean })

        // Test mixed array
        val mixedArray = mapResult.value["mixed"] as Document.List
        assertEquals(3, mixedArray.value.size)
        assertTrue(mixedArray.value[0] is Document.String)
        assertTrue(mixedArray.value[1] is Document.Number)
        assertTrue(mixedArray.value[2] is Document.Map)
    }

    @Test
    @DisplayName("Should handle empty JsonArray")
    fun testEmptyJsonArray() {
        val jsonObject = JsonObject(
            mapOf(
                "emptyArray" to JsonArray(emptyList()),
            ),
        )

        val result = jsonObject.toDocument()

        assertTrue(result is Document.Map)
        val mapResult = result as Document.Map
        val arrayValue = mapResult.value["emptyArray"]
        assertTrue(arrayValue is Document.List)
        assertEquals(0, (arrayValue as Document.List).value.size)
    }

    // Tests for Document.toJsonObject() method
    @Test
    @DisplayName("Should convert Document.Number with Float to JsonPrimitive")
    fun testDocumentNumberFloatToJson() {
        val document = Document.Map(
            mapOf(
                "floatValue" to Document.Number(3.14f),
            ),
        )

        val result = document.toJsonObject()

        assertTrue(result["floatValue"] is JsonPrimitive)
        val floatValue = (result["floatValue"] as JsonPrimitive).floatOrNull
        assertEquals(3.14f, floatValue)
    }

    @Test
    @DisplayName("Should convert Document.Number with Double to JsonPrimitive")
    fun testDocumentNumberDoubleToJson() {
        val document = Document.Map(
            mapOf(
                "doubleValue" to Document.Number(2.71828),
            ),
        )

        val result = document.toJsonObject()

        assertTrue(result["doubleValue"] is JsonPrimitive)
        val doubleValue = (result["doubleValue"] as JsonPrimitive).doubleOrNull
        assertEquals(2.71828, doubleValue)
    }

    @Test
    @DisplayName("Should convert Document.Number with Int to JsonPrimitive")
    fun testDocumentNumberIntToJson() {
        val document = Document.Map(
            mapOf(
                "intValue" to Document.Number(42),
            ),
        )

        val result = document.toJsonObject()

        assertTrue(result["intValue"] is JsonPrimitive)
        val intValue = (result["intValue"] as JsonPrimitive).intOrNull
        assertEquals(42, intValue)
    }

    @Test
    @DisplayName("Should convert Document.Number with Long to JsonPrimitive")
    fun testDocumentNumberLongToJson() {
        val document = Document.Map(
            mapOf(
                "longValue" to Document.Number(9223372036854775807L),
            ),
        )

        val result = document.toJsonObject()

        assertTrue(result["longValue"] is JsonPrimitive)
        val longValue = (result["longValue"] as JsonPrimitive).longOrNull
        assertEquals(9223372036854775807L, longValue)
    }

    @Test
    @DisplayName("Should convert Document.List with null elements to JsonArray with JsonNull")
    fun testDocumentListWithNullElements() {
        val document = Document.Map(
            mapOf(
                "listWithNulls" to Document.List(
                    listOf(
                        Document.String("value1"),
                        null,
                        Document.Number(42),
                        null,
                        Document.Boolean(true),
                    ),
                ),
            ),
        )

        val result = document.toJsonObject()

        assertTrue(result["listWithNulls"] is JsonArray)
        val array = result["listWithNulls"] as JsonArray
        assertEquals(5, array.size)

        // Check that null elements are converted to JsonNull
        assertTrue(array[0] is JsonPrimitive)
        assertEquals("value1", (array[0] as JsonPrimitive).content)

        assertTrue(array[1] is JsonNull)

        assertTrue(array[2] is JsonPrimitive)
        assertEquals(42, (array[2] as JsonPrimitive).intOrNull)

        assertTrue(array[3] is JsonNull)

        assertTrue(array[4] is JsonPrimitive)
        assertEquals(true, (array[4] as JsonPrimitive).booleanOrNull)
    }

    @Test
    @DisplayName("Should convert all numeric types in Document.Number correctly")
    fun testAllNumericTypesInDocumentNumber() {
        val document = Document.Map(
            mapOf(
                "float" to Document.Number(1.5f),
                "double" to Document.Number(2.5),
                "int" to Document.Number(100),
                "long" to Document.Number(1000L),
            ),
        )

        val result = document.toJsonObject()

        // Verify all numeric types are converted
        assertEquals(1.5f, (result["float"] as JsonPrimitive).floatOrNull)
        assertEquals(2.5, (result["double"] as JsonPrimitive).doubleOrNull)
        assertEquals(100, (result["int"] as JsonPrimitive).intOrNull)
        assertEquals(1000L, (result["long"] as JsonPrimitive).longOrNull)
    }
}
