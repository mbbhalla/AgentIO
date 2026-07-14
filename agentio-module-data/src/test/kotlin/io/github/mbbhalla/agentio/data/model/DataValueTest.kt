package io.github.mbbhalla.agentio.data.model

import kotlinx.serialization.json.Json
import java.sql.Timestamp
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class DataValueTest {
    @Test
    fun `null maps to NullValue`() {
        assertEquals(DataValue.NullValue, DataValue.from(null))
    }

    @Test
    fun `String maps to StringValue`() {
        val result = DataValue.from("hello")
        assertIs<DataValue.StringValue>(result)
        assertEquals("hello", result.value)
    }

    @Test
    fun `empty String maps to StringValue`() {
        val result = DataValue.from("")
        assertIs<DataValue.StringValue>(result)
        assertEquals("", result.value)
    }

    @Test
    fun `Boolean true maps to BooleanValue`() {
        val result = DataValue.from(true)
        assertIs<DataValue.BooleanValue>(result)
        assertEquals(true, result.value)
    }

    @Test
    fun `Boolean false maps to BooleanValue`() {
        val result = DataValue.from(false)
        assertIs<DataValue.BooleanValue>(result)
        assertEquals(false, result.value)
    }

    @Test
    fun `Int maps to LongValue`() {
        val result = DataValue.from(42)
        assertIs<DataValue.LongValue>(result)
        assertEquals(42L, result.value)
    }

    @Test
    fun `negative Int maps to LongValue`() {
        val result = DataValue.from(-1)
        assertIs<DataValue.LongValue>(result)
        assertEquals(-1L, result.value)
    }

    @Test
    fun `Long maps to LongValue`() {
        val result = DataValue.from(9999999999L)
        assertIs<DataValue.LongValue>(result)
        assertEquals(9999999999L, result.value)
    }

    @Test
    fun `Float maps to DoubleValue`() {
        val result = DataValue.from(3.14f)
        assertIs<DataValue.DoubleValue>(result)
        assertEquals(3.14f.toDouble(), result.value)
    }

    @Test
    fun `Double maps to DoubleValue`() {
        val result = DataValue.from(2.718281828)
        assertIs<DataValue.DoubleValue>(result)
        assertEquals(2.718281828, result.value)
    }

    @Test
    fun `java sql Timestamp maps to TimestampValue`() {
        val ts = Timestamp.from(Instant.parse("2025-05-20T08:00:00Z"))
        val result = DataValue.from(ts)
        assertIs<DataValue.TimestampValue>(result)
        assertEquals("2025-05-20T08:00:00Z", result.value)
    }

    @Test
    fun `java time Instant maps to TimestampValue`() {
        val instant = Instant.parse("2025-01-15T12:30:00Z")
        val result = DataValue.from(instant)
        assertIs<DataValue.TimestampValue>(result)
        assertEquals("2025-01-15T12:30:00Z", result.value)
    }

    @Test
    fun `java time LocalDateTime maps to TimestampValue in UTC`() {
        val ldt = LocalDateTime.of(2025, 6, 1, 14, 30, 0)
        val result = DataValue.from(ldt)
        assertIs<DataValue.TimestampValue>(result)
        assertEquals("2025-06-01T14:30:00Z", result.value)
    }

    @Test
    fun `java time LocalDate maps to TimestampValue at start of day UTC`() {
        val ld = LocalDate.of(2025, 3, 15)
        val result = DataValue.from(ld)
        assertIs<DataValue.TimestampValue>(result)
        assertEquals("2025-03-15T00:00:00Z", result.value)
    }

    @Test
    fun `unknown type maps to StringValue via toString`() {
        val custom =
            object {
                override fun toString() = "custom-object"
            }
        val result = DataValue.from(custom)
        assertIs<DataValue.StringValue>(result)
        assertEquals("custom-object", result.value)
    }

    // --- Polymorphic serialization contract ---
    // DataValue is a @Serializable sealed class: the wire form carries a "type" discriminator, and
    // each subtype's explicit @SerialName makes that discriminator a short, stable token. These
    // tests pin that contract so a producer (including an LLM emitting AnalysisResult JSON) can rely
    // on e.g. {"type":"LongValue","value":0} round-tripping.

    // Mirrors JsonSchemaUtil.json in agentio-core: default classDiscriminator = "type".
    private val json =
        Json {
            ignoreUnknownKeys = true
            prettyPrint = true
            isLenient = true
        }

    @Test
    fun `encoding a subtype emits its short SerialName as the type discriminator`() {
        val encoded = json.encodeToString(DataValue.serializer(), DataValue.LongValue(0))
        assertEquals(true, encoded.contains("\"type\""), "Encoded form should carry the discriminator: $encoded")
        assertEquals(
            true,
            encoded.contains("\"LongValue\""),
            "Discriminator should be the short SerialName, not the FQN: $encoded",
        )
    }

    @Test
    fun `discriminated forms deserialize to the right subtype`() {
        assertEquals(
            DataValue.LongValue(0),
            json.decodeFromString(DataValue.serializer(), """{"type":"LongValue","value":0}"""),
        )
        assertEquals(
            DataValue.DoubleValue(1.5),
            json.decodeFromString(DataValue.serializer(), """{"type":"DoubleValue","value":1.5}"""),
        )
        assertEquals(
            DataValue.StringValue("x"),
            json.decodeFromString(DataValue.serializer(), """{"type":"StringValue","value":"x"}"""),
        )
        assertEquals(
            DataValue.BooleanValue(true),
            json.decodeFromString(DataValue.serializer(), """{"type":"BooleanValue","value":true}"""),
        )
        assertEquals(
            DataValue.NullValue,
            json.decodeFromString(DataValue.serializer(), """{"type":"NullValue"}"""),
        )
    }

    @Test
    fun `every subtype round-trips through JSON`() {
        val values: List<DataValue> =
            listOf(
                DataValue.StringValue("hello"),
                DataValue.LongValue(42L),
                DataValue.DoubleValue(3.14),
                DataValue.TimestampValue("2025-06-01T14:30:00Z"),
                DataValue.BooleanValue(false),
                DataValue.NullValue,
            )
        values.forEach { original ->
            val encoded = json.encodeToString(DataValue.serializer(), original)
            assertEquals(original, json.decodeFromString(DataValue.serializer(), encoded))
        }
    }

    @Test
    fun `a value object without the type discriminator fails to deserialize`() {
        // Regression guard: the original crash was a bare {"value":0} with no discriminator.
        val failure =
            assertFailsWith<Exception> {
                json.decodeFromString(DataValue.serializer(), """{"value":0}""")
            }
        assertEquals(
            true,
            failure.message?.contains("discriminator", ignoreCase = true),
            "Expected a missing-discriminator error, but was: ${failure.message}",
        )
    }
}
