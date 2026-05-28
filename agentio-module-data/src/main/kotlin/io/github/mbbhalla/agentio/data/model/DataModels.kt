package io.github.mbbhalla.agentio.data.model

import kotlinx.serialization.Serializable
import org.mvel2.MVEL
import java.sql.ResultSet
import java.time.ZoneOffset

@Serializable
data class ColumnName(
    val value: String,
) {
    init {
        require(value.matches(VALID_PATTERN)) { "Invalid column name: '$value'" }
    }

    companion object {
        private val VALID_PATTERN = Regex("[a-z][a-z0-9_]*")
    }
}

@Serializable
enum class ColumnType {
    VARCHAR,
    INTEGER,
    BIGINT,
    DOUBLE,
    BOOLEAN,
    TIMESTAMP,
    DATE,
    ;

    companion object {
        fun fromTypeName(typeName: String): ColumnType =
            when (typeName.uppercase()) {
                "VARCHAR", "TEXT", "STRING" -> VARCHAR
                "INTEGER", "INT", "INT4", "SIGNED" -> INTEGER
                "BIGINT", "INT8", "LONG" -> BIGINT
                "DOUBLE", "FLOAT8", "NUMERIC", "DECIMAL", "FLOAT", "REAL", "FLOAT4" -> DOUBLE
                "BOOLEAN", "BOOL", "LOGICAL" -> BOOLEAN
                "TIMESTAMP", "DATETIME", "TIMESTAMP WITH TIME ZONE" -> TIMESTAMP
                "DATE" -> DATE
                else -> VARCHAR
            }
    }
}

data class TableName(
    val value: String,
) {
    init {
        require(value.matches(VALID_PATTERN)) { "Invalid table name: '$value'" }
    }

    companion object {
        private val VALID_PATTERN = Regex("[a-z][a-z0-9_]*")
    }
}

data class TableInfo(
    val name: TableName,
    val description: String,
    val columns: List<ColumnInfo>,
)

data class ColumnInfo(
    val name: ColumnName,
    val type: ColumnType,
    val nullable: Boolean,
    val primaryKey: Boolean,
    val foreignKey: ForeignKeyRef?,
    val description: String,
)

data class ForeignKeyRef(
    val table: TableName,
    val column: ColumnName,
)

@Serializable
data class Dataset(
    val columns: List<ColumnMeta>,
    val records: List<Record>,
) {
    @Serializable
    data class ColumnMeta(
        val name: ColumnName,
        val type: ColumnType,
    )

    @Serializable
    data class Record(
        val values: Map<String, DataValue>,
    ) {
        operator fun get(columnName: ColumnName): DataValue? = values[columnName.value]
    }

    fun evaluate(expression: MVELExpression): Boolean = MVEL.evalToBoolean(expression.value, this)

    companion object {
        fun from(rs: ResultSet): Dataset {
            val meta = rs.metaData
            val columns =
                (1..meta.columnCount).map {
                    ColumnMeta(
                        name = ColumnName(meta.getColumnName(it).lowercase()),
                        type = ColumnType.fromTypeName(meta.getColumnTypeName(it)),
                    )
                }
            val records = mutableListOf<Record>()
            while (rs.next()) {
                val values =
                    columns.associate { col ->
                        col.name.value to DataValue.from(rs.getObject(col.name.value))
                    }
                records.add(Record(values))
            }
            return Dataset(columns = columns, records = records)
        }
    }
}

@Serializable
sealed class DataValue {
    @Serializable
    data class StringValue(
        val value: String,
    ) : DataValue()

    @Serializable
    data class LongValue(
        val value: Long,
    ) : DataValue()

    @Serializable
    data class DoubleValue(
        val value: Double,
    ) : DataValue()

    @Serializable
    data class TimestampValue(
        val value: String,
    ) : DataValue()

    @Serializable
    data class BooleanValue(
        val value: Boolean,
    ) : DataValue()

    @Serializable
    data object NullValue : DataValue()

    companion object {
        fun from(obj: Any?): DataValue =
            when (obj) {
                null -> NullValue
                is String -> StringValue(obj)
                is Boolean -> BooleanValue(obj)
                is Int -> LongValue(obj.toLong())
                is Long -> LongValue(obj)
                is Float -> DoubleValue(obj.toDouble())
                is Double -> DoubleValue(obj)
                is java.sql.Timestamp -> TimestampValue(obj.toInstant().toString())
                is java.time.LocalDateTime -> TimestampValue(obj.atZone(ZoneOffset.UTC).toInstant().toString())
                is java.time.LocalDate -> TimestampValue(obj.atStartOfDay(ZoneOffset.UTC).toInstant().toString())
                is java.time.Instant -> TimestampValue(obj.toString())
                else -> StringValue(obj.toString())
            }
    }
}
