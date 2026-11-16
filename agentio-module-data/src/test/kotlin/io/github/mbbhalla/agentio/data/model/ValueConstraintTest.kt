package io.github.mbbhalla.agentio.data.model

import io.github.mbbhalla.agentio.data.model.DataValue.BooleanValue
import io.github.mbbhalla.agentio.data.model.DataValue.DoubleValue
import io.github.mbbhalla.agentio.data.model.DataValue.LongValue
import io.github.mbbhalla.agentio.data.model.DataValue.NullValue
import io.github.mbbhalla.agentio.data.model.DataValue.StringValue
import io.github.mbbhalla.agentio.data.model.DataValue.TimestampValue
import io.github.mbbhalla.agentio.data.model.ValueConstraint.MaxLength
import io.github.mbbhalla.agentio.data.model.ValueConstraint.Maximum
import io.github.mbbhalla.agentio.data.model.ValueConstraint.MinLength
import io.github.mbbhalla.agentio.data.model.ValueConstraint.Minimum
import io.github.mbbhalla.agentio.data.model.ValueConstraint.OneOf
import io.github.mbbhalla.agentio.data.model.ValueConstraint.Pattern
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.assertThrows
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ValueConstraintTest {
    private fun column(
        type: ColumnType,
        vararg constraints: ValueConstraint,
    ) = ColumnDefinition(ColumnName("c"), type, nullable = true, description = "", constraints = constraints.toList())

    private fun rejected(
        type: ColumnType,
        vararg constraints: ValueConstraint,
    ): String = assertThrows<IllegalArgumentException> { column(type, *constraints) }.message.orEmpty()

    @Nested
    inner class OneOfRules {
        @Test
        fun `needs distinct, non-null values of one kind`() {
            assertEquals("OneOf needs at least one value", assertThrows<IllegalArgumentException> { OneOf(emptyList()) }.message)
            assertEquals(
                "OneOf values cannot include NULL; make the column nullable instead",
                assertThrows<IllegalArgumentException> { OneOf(listOf(StringValue("A"), NullValue)) }.message,
            )
            assertEquals(
                "OneOf values must be distinct: ['A', 'A']",
                assertThrows<IllegalArgumentException> { OneOf(listOf(StringValue("A"), StringValue("A"))) }.message,
            )
            assertEquals(
                "OneOf values must all be of one kind: ['A', 1]",
                assertThrows<IllegalArgumentException> { OneOf(listOf(StringValue("A"), LongValue(1))) }.message,
            )
        }

        @Test
        fun `applies to the column type its values fit`() {
            assertTrue(OneOf(listOf(StringValue("A"))).appliesTo(ColumnType.VARCHAR))
            assertTrue(OneOf(listOf(LongValue(1))).appliesTo(ColumnType.INTEGER))
            assertTrue(OneOf(listOf(LongValue(1))).appliesTo(ColumnType.BIGINT))
            assertTrue(OneOf(listOf(LongValue(1))).appliesTo(ColumnType.DOUBLE), "whole numbers fit a DOUBLE")
            assertTrue(OneOf(listOf(DoubleValue(1.5))).appliesTo(ColumnType.DOUBLE))
            assertTrue(OneOf(listOf(BooleanValue(true))).appliesTo(ColumnType.BOOLEAN))
            assertTrue(OneOf(listOf(TimestampValue("2026-01-01T00:00:00Z"))).appliesTo(ColumnType.DATE))
            assertTrue(OneOf(listOf(TimestampValue("2026-01-01T00:00:00Z"))).appliesTo(ColumnType.TIMESTAMP))

            assertFalse(OneOf(listOf(StringValue("A"))).appliesTo(ColumnType.INTEGER))
            assertFalse(OneOf(listOf(LongValue(1))).appliesTo(ColumnType.VARCHAR))
            assertFalse(OneOf(listOf(DoubleValue(1.5))).appliesTo(ColumnType.INTEGER), "a fraction does not fit an INTEGER")
            assertFalse(OneOf(listOf(LongValue(Int.MAX_VALUE.toLong() + 1))).appliesTo(ColumnType.INTEGER), "out of 32-bit range")
            assertTrue(OneOf(listOf(LongValue(Int.MAX_VALUE.toLong() + 1))).appliesTo(ColumnType.BIGINT))
        }
    }

    @Nested
    inner class BoundRules {
        @Test
        fun `bounds need an ordered value`() {
            assertEquals(
                "Minimum needs a number, date or timestamp, found 'x'",
                assertThrows<IllegalArgumentException> {
                    Minimum(StringValue("x"))
                }.message,
            )
            assertEquals(
                "Maximum needs a number, date or timestamp, found true",
                assertThrows<IllegalArgumentException> {
                    Maximum(BooleanValue(true))
                }.message,
            )
        }

        @Test
        fun `bounds apply to numeric, date and timestamp columns matching their value`() {
            assertTrue(Minimum(LongValue(0)).appliesTo(ColumnType.INTEGER))
            assertTrue(Minimum(LongValue(0)).appliesTo(ColumnType.DOUBLE))
            assertTrue(Maximum(DoubleValue(4.0)).appliesTo(ColumnType.DOUBLE))
            assertFalse(Maximum(DoubleValue(4.0)).appliesTo(ColumnType.INTEGER))
            assertTrue(Minimum(TimestampValue("1990-01-01T00:00:00Z")).appliesTo(ColumnType.DATE))
            assertFalse(Minimum(LongValue(0)).appliesTo(ColumnType.VARCHAR))
        }

        @Test
        fun `lengths cannot be negative and apply to strings only`() {
            assertEquals("MinLength cannot be negative: -1", assertThrows<IllegalArgumentException> { MinLength(-1) }.message)
            assertEquals("MaxLength cannot be negative: -1", assertThrows<IllegalArgumentException> { MaxLength(-1) }.message)
            assertTrue(MinLength(0).appliesTo(ColumnType.VARCHAR))
            assertFalse(MaxLength(3).appliesTo(ColumnType.INTEGER))
        }
    }

    @Nested
    inner class PatternRules {
        @Test
        fun `a pattern must be a valid RE2 regular expression`() {
            Pattern("^[A-Z]{3}$")
            Pattern("""^\d{4}(SP|SU|FA)$""")
            Pattern("(?i)^abc$")
            val unbalanced = assertThrows<IllegalArgumentException> { Pattern("(a") }
            assertTrue(unbalanced.message!!.startsWith("Pattern '(a' is not a valid RE2 regular expression: "), unbalanced.message)
        }

        @Test
        fun `ECMA-262 features RE2 lacks are rejected, because DuckDB would reject them`() {
            assertThrows<IllegalArgumentException> { Pattern("a(?=b)") }
            assertThrows<IllegalArgumentException> { Pattern("a(?!b)") }
            assertThrows<IllegalArgumentException> { Pattern("(?<=a)b") }
            assertThrows<IllegalArgumentException> { Pattern("""(a)\1""") }
        }

        @Test
        fun `applies to strings only`() {
            assertTrue(Pattern("x").appliesTo(ColumnType.VARCHAR))
            assertFalse(Pattern("x").appliesTo(ColumnType.INTEGER))
        }
    }

    @Nested
    inner class ColumnRules {
        @Test
        fun `a constraint that cannot apply to the column type is rejected`() {
            assertEquals(
                "Column 'c' of type INTEGER cannot have the constraint Pattern(regex=x)",
                rejected(ColumnType.INTEGER, Pattern("x")),
            )
            assertEquals(
                "Column 'c' of type VARCHAR cannot have the constraint Minimum(value=LongValue(value=0), exclusive=false)",
                rejected(ColumnType.VARCHAR, Minimum(LongValue(0))),
            )
            assertEquals(
                "Column 'c' of type INTEGER cannot have the constraint OneOf(values=[StringValue(value=A)])",
                rejected(ColumnType.INTEGER, OneOf(listOf(StringValue("A")))),
            )
        }

        @Test
        fun `two constraints of one kind are rejected`() {
            assertEquals(
                "Column 'c' has more than one constraint of kind [Minimum]",
                rejected(ColumnType.INTEGER, Minimum(LongValue(0)), Minimum(LongValue(1))),
            )
        }

        @Test
        fun `a minimum above the maximum is rejected, and equal bounds only when both are inclusive`() {
            assertTrue(
                rejected(
                    ColumnType.INTEGER,
                    Minimum(LongValue(5)),
                    Maximum(LongValue(4)),
                ).startsWith("Column 'c' has a minimum above its maximum"),
            )
            column(ColumnType.INTEGER, Minimum(LongValue(4)), Maximum(LongValue(4)))
            assertTrue(
                rejected(
                    ColumnType.INTEGER,
                    Minimum(LongValue(4), exclusive = true),
                    Maximum(LongValue(4)),
                ).contains("minimum above its maximum"),
            )
            column(ColumnType.DOUBLE, Minimum(LongValue(0)), Maximum(DoubleValue(4.0)))
            assertTrue(rejected(ColumnType.DOUBLE, Minimum(DoubleValue(4.5)), Maximum(LongValue(4))).contains("minimum above its maximum"))
            assertTrue(
                rejected(ColumnType.DATE, Minimum(TimestampValue("2026-01-02T00:00:00Z")), Maximum(TimestampValue("2026-01-01T00:00:00Z")))
                    .contains("minimum above its maximum"),
            )
            column(ColumnType.DATE, Minimum(TimestampValue("2026-01-01T00:00:00Z")), Maximum(TimestampValue("2026-01-02T00:00:00Z")))
        }

        @Test
        fun `exclusive bounds with no value between them on a discrete type are rejected`() {
            val none = "has no value between its exclusive bounds"
            assertTrue(
                rejected(
                    ColumnType.INTEGER,
                    Minimum(LongValue(1), exclusive = true),
                    Maximum(LongValue(2), exclusive = true),
                ).contains(none),
            )
            assertTrue(
                rejected(
                    ColumnType.BIGINT,
                    Minimum(LongValue(1), exclusive = true),
                    Maximum(LongValue(2), exclusive = true),
                ).contains(none),
            )
            assertTrue(
                rejected(
                    ColumnType.DATE,
                    Minimum(TimestampValue("2026-01-01T00:00:00Z"), exclusive = true),
                    Maximum(TimestampValue("2026-01-02T00:00:00Z"), exclusive = true),
                ).contains(none),
            )
            // one step apart with one inclusive bound admits exactly one value
            column(ColumnType.INTEGER, Minimum(LongValue(1), exclusive = true), Maximum(LongValue(2)))
            column(ColumnType.INTEGER, Minimum(LongValue(1)), Maximum(LongValue(2), exclusive = true))
            // two steps apart admits one value even with both exclusive
            column(ColumnType.INTEGER, Minimum(LongValue(1), exclusive = true), Maximum(LongValue(3), exclusive = true))
            column(
                ColumnType.DATE,
                Minimum(TimestampValue("2026-01-01T00:00:00Z"), exclusive = true),
                Maximum(TimestampValue("2026-01-03T00:00:00Z"), exclusive = true),
            )
            // continuous types always have something strictly between distinct bounds
            column(ColumnType.DOUBLE, Minimum(LongValue(1), exclusive = true), Maximum(LongValue(2), exclusive = true))
            column(
                ColumnType.TIMESTAMP,
                Minimum(TimestampValue("2026-01-01T00:00:00Z"), exclusive = true),
                Maximum(TimestampValue("2026-01-01T00:00:01Z"), exclusive = true),
            )
        }

        @Test
        fun `a minimum length above the maximum length is rejected`() {
            assertEquals(
                "Column 'c' has a minimum length 4 above its maximum length 3",
                rejected(ColumnType.VARCHAR, MinLength(4), MaxLength(3)),
            )
            column(ColumnType.VARCHAR, MinLength(3), MaxLength(3))
        }

        @Test
        fun `an enumerated value that breaks another constraint of the column is rejected`() {
            assertEquals(
                "Column 'c' allows the value 'LECTURE', which breaks its constraint Pattern(regex=^[A-Z]{4,5}$)",
                rejected(ColumnType.VARCHAR, OneOf(listOf(StringValue("CLASS"), StringValue("LECTURE"))), Pattern("^[A-Z]{4,5}$")),
            )
            assertEquals(
                "Column 'c' allows the value 'EXAMINATION', which breaks its constraint MaxLength(length=5)",
                rejected(ColumnType.VARCHAR, OneOf(listOf(StringValue("EXAMINATION"))), MaxLength(5)),
            )
            assertTrue(
                rejected(
                    ColumnType.INTEGER,
                    OneOf(listOf(LongValue(100), LongValue(500))),
                    Maximum(LongValue(400)),
                ).contains("allows the value 500"),
            )
            assertTrue(
                rejected(
                    ColumnType.DOUBLE,
                    OneOf(listOf(DoubleValue(0.0))),
                    Minimum(LongValue(0), exclusive = true),
                ).contains("allows the value 0.0"),
            )
            assertTrue(
                rejected(
                    ColumnType.DATE,
                    OneOf(listOf(TimestampValue("1980-01-01T00:00:00Z"))),
                    Minimum(TimestampValue("1990-01-01T00:00:00Z")),
                ).contains("allows the value 1980-01-01T00:00:00Z"),
            )
            // consistent combinations are fine
            column(
                ColumnType.VARCHAR,
                OneOf(listOf(StringValue("CLASS"), StringValue("EXAM"))),
                Pattern("^[A-Z]+$"),
                MinLength(4),
                MaxLength(5),
            )
            column(ColumnType.INTEGER, OneOf(listOf(LongValue(100), LongValue(200))), Minimum(LongValue(100)), Maximum(LongValue(400)))
            column(ColumnType.VARCHAR, OneOf(listOf(StringValue("é✓"))), MaxLength(2)) // length counts characters, as DuckDB does
        }

        @Test
        fun `values the DDL cannot render are rejected when the constraint is built`() {
            assertEquals(
                "Minimum value 'yesterday' is not an ISO-8601 instant such as 2026-01-31T00:00:00Z",
                assertThrows<IllegalArgumentException> { Minimum(TimestampValue("yesterday")) }.message,
            )
            assertEquals(
                "OneOf value '2026-01-01' is not an ISO-8601 instant such as 2026-01-31T00:00:00Z",
                assertThrows<IllegalArgumentException> { OneOf(listOf(TimestampValue("2026-01-01"))) }.message,
            )
            assertEquals(
                "Maximum value NaN is not a finite number",
                assertThrows<IllegalArgumentException> {
                    Maximum(DoubleValue(Double.NaN))
                }.message,
            )
            assertEquals(
                "OneOf value Infinity is not a finite number",
                assertThrows<IllegalArgumentException> { OneOf(listOf(DoubleValue(Double.POSITIVE_INFINITY))) }.message,
            )
        }

        @Test
        fun `instants outside years 0001 to 9999 or finer than a microsecond cannot be rendered and are rejected`() {
            assertEquals(
                "Minimum value '+10000-01-01T00:00:00Z' is outside the years 0001 to 9999",
                assertThrows<IllegalArgumentException> { Minimum(TimestampValue("+10000-01-01T00:00:00Z")) }.message,
            )
            assertEquals(
                "Maximum value '0000-12-31T00:00:00Z' is outside the years 0001 to 9999",
                assertThrows<IllegalArgumentException> { Maximum(TimestampValue("0000-12-31T00:00:00Z")) }.message,
            )
            assertEquals(
                "OneOf value '2026-01-01T00:00:00.0000001Z' is finer than the microsecond precision DuckDB stores",
                assertThrows<IllegalArgumentException> { OneOf(listOf(TimestampValue("2026-01-01T00:00:00.0000001Z"))) }.message,
            )
            Minimum(TimestampValue("0001-01-01T00:00:00Z"))
            Maximum(TimestampValue("9999-12-31T23:59:59.999999Z"))
        }

        @Test
        fun `a NUL character cannot appear in text that becomes a SQL literal`() {
            assertEquals(
                "OneOf value must not contain a NUL character",
                assertThrows<IllegalArgumentException> {
                    OneOf(listOf(StringValue("a\u0000b")))
                }.message,
            )
            assertEquals("Pattern must not contain a NUL character", assertThrows<IllegalArgumentException> { Pattern("a\u0000b") }.message)
            assertEquals(
                "Column 'c' description must not contain a NUL character",
                assertThrows<IllegalArgumentException> { ColumnDefinition(ColumnName("c"), ColumnType.VARCHAR, true, "x\u0000y") }.message,
            )
            assertEquals(
                "Table 't' description must not contain a NUL character",
                assertThrows<IllegalArgumentException> {
                    TableDefinition(TableName("t"), "x\u0000y", listOf(ColumnDefinition(ColumnName("c"), ColumnType.VARCHAR, true, "")))
                }.message,
            )
        }

        @Test
        fun `an instant with a time of day does not fit a DATE column`() {
            assertTrue(Minimum(TimestampValue("2026-01-01T00:00:00Z")).appliesTo(ColumnType.DATE))
            assertFalse(
                Minimum(TimestampValue("2026-01-01T08:30:00Z")).appliesTo(ColumnType.DATE),
                "the time would be lost in the DATE literal",
            )
            assertTrue(Minimum(TimestampValue("2026-01-01T08:30:00Z")).appliesTo(ColumnType.TIMESTAMP))
            assertTrue(rejected(ColumnType.DATE, Maximum(TimestampValue("2026-01-01T08:30:00Z"))).contains("cannot have the constraint"))
        }

        @Test
        fun `every kind can sit on one string column, and every kind on a number column`() {
            column(ColumnType.VARCHAR, OneOf(listOf(StringValue("A"), StringValue("B"))), MinLength(1), MaxLength(1), Pattern("^[AB]$"))
            column(
                ColumnType.DOUBLE,
                OneOf(listOf(DoubleValue(0.5), DoubleValue(1.5))),
                Minimum(LongValue(0)),
                Maximum(DoubleValue(2.0), exclusive = true),
            )
        }
    }
}
