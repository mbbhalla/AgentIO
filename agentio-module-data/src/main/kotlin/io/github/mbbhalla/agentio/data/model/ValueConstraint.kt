package io.github.mbbhalla.agentio.data.model

import com.google.re2j.PatternSyntaxException
import java.time.Instant
import java.time.format.DateTimeParseException
import java.time.temporal.ChronoUnit
import com.google.re2j.Pattern as Re2Pattern

/**
 * A rule on the values of one column, enforced by the database as a `CHECK` constraint. `NULL` always
 * passes, as in SQL: whether a column may be null is [ColumnDefinition.nullable]'s business.
 *
 * Each kind applies to some column types only ([appliesTo]); [ColumnDefinition] rejects a constraint
 * on a column it cannot apply to, and more than one constraint of the same kind on one column.
 */
sealed interface ValueConstraint {
    fun appliesTo(type: ColumnType): Boolean

    /** Whether a non-null [value] of the column's kind satisfies this constraint. */
    fun admits(value: DataValue): Boolean

    /** The value is one of [values]: distinct, non-null, all of one kind, each of a kind the column type holds. */
    data class OneOf(
        val values: List<DataValue>,
    ) : ValueConstraint {
        init {
            require(values.isNotEmpty()) { "OneOf needs at least one value" }
            require(values.none { it is DataValue.NullValue }) { "OneOf values cannot include NULL; make the column nullable instead" }
            require(values.distinct().size == values.size) { "OneOf values must be distinct: ${values.map { it.text() }}" }
            require(values.map { it::class }.distinct().size == 1) { "OneOf values must all be of one kind: ${values.map { it.text() }}" }
            values.forEach { it.requireRenderable("OneOf") }
        }

        override fun appliesTo(type: ColumnType): Boolean = values.all { it.fits(type) }

        override fun admits(value: DataValue): Boolean = values.any { compare(it, value) == 0 || it == value }
    }

    /** The value is at least [value], or greater than it when [exclusive]. Numbers, dates and timestamps. */
    data class Minimum(
        val value: DataValue,
        val exclusive: Boolean = false,
    ) : ValueConstraint {
        init {
            require(value.isOrdered()) { "Minimum needs a number, date or timestamp, found ${value.text()}" }
            value.requireRenderable("Minimum")
        }

        override fun appliesTo(type: ColumnType): Boolean = value.fits(type)

        override fun admits(value: DataValue): Boolean = compare(value, this.value)?.let { if (exclusive) it > 0 else it >= 0 } ?: true
    }

    /** The value is at most [value], or less than it when [exclusive]. Numbers, dates and timestamps. */
    data class Maximum(
        val value: DataValue,
        val exclusive: Boolean = false,
    ) : ValueConstraint {
        init {
            require(value.isOrdered()) { "Maximum needs a number, date or timestamp, found ${value.text()}" }
            value.requireRenderable("Maximum")
        }

        override fun appliesTo(type: ColumnType): Boolean = value.fits(type)

        override fun admits(value: DataValue): Boolean = compare(value, this.value)?.let { if (exclusive) it < 0 else it <= 0 } ?: true
    }

    /** The string has at least [length] characters. */
    data class MinLength(
        val length: Int,
    ) : ValueConstraint {
        init {
            require(length >= 0) { "MinLength cannot be negative: $length" }
        }

        override fun appliesTo(type: ColumnType): Boolean = type == ColumnType.VARCHAR

        override fun admits(value: DataValue): Boolean = value !is DataValue.StringValue || value.value.characterCount() >= length
    }

    /** The string has at most [length] characters. */
    data class MaxLength(
        val length: Int,
    ) : ValueConstraint {
        init {
            require(length >= 0) { "MaxLength cannot be negative: $length" }
        }

        override fun appliesTo(type: ColumnType): Boolean = type == ColumnType.VARCHAR

        override fun admits(value: DataValue): Boolean = value !is DataValue.StringValue || value.value.characterCount() <= length
    }

    /**
     * The string contains a match of [regex], in RE2 syntax, which is what DuckDB's `regexp_matches`
     * uses; anchor with `^` and `$` to match the whole string. ECMA-262 features RE2 lacks (lookaround,
     * backreferences) are rejected here, so a model that exists has a pattern the database accepts.
     */
    data class Pattern(
        val regex: String,
    ) : ValueConstraint {
        private val compiled: Re2Pattern =
            try {
                requireNoNul(regex, "Pattern")
                Re2Pattern.compile(regex)
            } catch (e: PatternSyntaxException) {
                throw IllegalArgumentException("Pattern '$regex' is not a valid RE2 regular expression: ${e.message}", e)
            }

        override fun appliesTo(type: ColumnType): Boolean = type == ColumnType.VARCHAR

        /** A search, as DuckDB's `regexp_matches` is; anchor the pattern to match the whole value. */
        override fun admits(value: DataValue): Boolean = value !is DataValue.StringValue || compiled.matcher(value.value).find()
    }

    companion object {
        /**
         * Whether a value of this kind can be stored in a column of [type]: a whole number within 32 bits
         * for `INTEGER`, a midnight instant for `DATE` (a time of day would be lost).
         */
        internal fun DataValue.fits(type: ColumnType): Boolean =
            when (this) {
                is DataValue.StringValue -> type == ColumnType.VARCHAR
                is DataValue.LongValue ->
                    type == ColumnType.BIGINT ||
                        type == ColumnType.DOUBLE ||
                        (type == ColumnType.INTEGER && value in Int.MIN_VALUE..Int.MAX_VALUE)
                is DataValue.DoubleValue -> type == ColumnType.DOUBLE
                is DataValue.BooleanValue -> type == ColumnType.BOOLEAN
                is DataValue.TimestampValue ->
                    type == ColumnType.TIMESTAMP ||
                        (type == ColumnType.DATE && instant(value)?.let { it.compareTo(it.truncatedTo(ChronoUnit.DAYS)) == 0 } == true)
                DataValue.NullValue -> false
            }

        /**
         * A value the DDL can render as a literal the database reads back unchanged: a finite number; a
         * string without a NUL byte (which ends a SQL literal early); an instant between years 0001 and
         * 9999 at microsecond precision or coarser (DuckDB stores microseconds, and the literal format
         * carries four-digit years of the common era).
         */
        private fun DataValue.requireRenderable(kind: String) {
            when (this) {
                is DataValue.TimestampValue -> {
                    val instant =
                        requireNotNull(instant(value)) { "$kind value '$value' is not an ISO-8601 instant such as 2026-01-31T00:00:00Z" }
                    require(instant in EARLIEST_INSTANT..LATEST_INSTANT) { "$kind value '$value' is outside the years 0001 to 9999" }
                    require(
                        instant.nano % NANOS_PER_MICRO == 0,
                    ) { "$kind value '$value' is finer than the microsecond precision DuckDB stores" }
                }
                is DataValue.DoubleValue -> require(value.isFinite()) { "$kind value $value is not a finite number" }
                is DataValue.StringValue -> requireNoNul(value, "$kind value")
                else -> Unit
            }
        }

        private val EARLIEST_INSTANT: Instant = Instant.parse("0001-01-01T00:00:00Z")
        private val LATEST_INSTANT: Instant = Instant.parse("9999-12-31T23:59:59.999999Z")
        private const val NANOS_PER_MICRO = 1_000

        /** A NUL byte ends a SQL string literal early, so no text that becomes a literal may contain one. */
        internal fun requireNoNul(
            text: String,
            what: String,
        ) {
            require('\u0000' !in text) { "$what must not contain a NUL character" }
        }

        private fun String.characterCount(): Int = codePointCount(0, length)

        /**
         * Whether some value of [type] lies within both bounds. Equal inclusive bounds admit one value;
         * on a discrete type (`INTEGER`, `BIGINT`, `DATE`) exclusive bounds one step apart admit none.
         */
        internal fun admitsSomething(
            type: ColumnType,
            minimum: Minimum,
            maximum: Maximum,
        ): Boolean {
            val low = minimum.value
            val high = maximum.value
            val stepsApart: Long? =
                when {
                    low is DataValue.LongValue && high is DataValue.LongValue && type != ColumnType.DOUBLE -> high.value - low.value
                    low is DataValue.TimestampValue && high is DataValue.TimestampValue && type == ColumnType.DATE ->
                        instant(low.value)?.let { a -> instant(high.value)?.let { b -> ChronoUnit.DAYS.between(a, b) } }
                    else -> null
                }
            if (stepsApart == null) return (compare(low, high) ?: -1) < 0 || (!minimum.exclusive && !maximum.exclusive)
            val excludedSteps = (if (minimum.exclusive) 1 else 0) + (if (maximum.exclusive) 1 else 0)
            return stepsApart >= excludedSteps
        }

        private fun DataValue.isOrdered(): Boolean =
            this is DataValue.LongValue || this is DataValue.DoubleValue || this is DataValue.TimestampValue

        /** `null` when the two values are of kinds that cannot be compared. */
        internal fun compare(
            a: DataValue,
            b: DataValue,
        ): Int? =
            when (a) {
                is DataValue.LongValue if b is DataValue.LongValue -> a.value.compareTo(b.value)
                is DataValue.LongValue if b is DataValue.DoubleValue -> a.value.toDouble().compareTo(b.value)
                is DataValue.DoubleValue if b is DataValue.LongValue -> a.value.compareTo(b.value.toDouble())
                is DataValue.DoubleValue if b is DataValue.DoubleValue -> a.value.compareTo(b.value)
                is DataValue.TimestampValue if b is DataValue.TimestampValue ->
                    instant(a.value)?.let { x ->
                        instant(b.value)?.let { y -> x.compareTo(y) }
                    }

                else -> null
            }

        private fun instant(iso: String): Instant? =
            try {
                Instant.parse(iso)
            } catch (_: DateTimeParseException) {
                null
            }

        internal fun DataValue.text(): String =
            when (this) {
                is DataValue.StringValue -> "'$value'"
                is DataValue.LongValue -> value.toString()
                is DataValue.DoubleValue -> value.toString()
                is DataValue.BooleanValue -> value.toString()
                is DataValue.TimestampValue -> value
                DataValue.NullValue -> "NULL"
            }
    }
}
