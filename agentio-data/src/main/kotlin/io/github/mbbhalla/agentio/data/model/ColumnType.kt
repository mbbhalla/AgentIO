package io.github.mbbhalla.agentio.data.model

import kotlinx.serialization.Serializable

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
        fun fromTypeName(typeName: String): ColumnType = when (typeName.uppercase()) {
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
