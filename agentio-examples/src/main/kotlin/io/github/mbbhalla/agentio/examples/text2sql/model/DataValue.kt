package io.github.mbbhalla.agentio.examples.text2sql.model

import kotlinx.serialization.Serializable
import java.time.Instant

@Serializable
sealed class DataValue {
    @Serializable
    data class StringValue(val value: String) : DataValue()

    @Serializable
    data class LongValue(val value: Long) : DataValue()

    @Serializable
    data class DoubleValue(val value: Double) : DataValue()

    @Serializable
    data class TimestampValue(val value: String) : DataValue()

    @Serializable
    data object NullValue : DataValue()

    companion object {
        fun from(obj: Any?): DataValue = when (obj) {
            null -> NullValue
            is String -> StringValue(obj)
            is Int -> LongValue(obj.toLong())
            is Long -> LongValue(obj)
            is Float -> DoubleValue(obj.toDouble())
            is Double -> DoubleValue(obj)
            is java.sql.Timestamp -> TimestampValue(obj.toInstant().toString())
            is java.time.LocalDateTime -> TimestampValue(obj.toString())
            is Instant -> TimestampValue(obj.toString())
            else -> StringValue(obj.toString())
        }
    }
}
