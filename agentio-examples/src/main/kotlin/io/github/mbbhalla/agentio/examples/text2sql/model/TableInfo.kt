package io.github.mbbhalla.agentio.examples.text2sql.model

data class TableInfo(
    val name: String,
    val description: String,
    val columns: List<ColumnInfo>,
)

data class ColumnInfo(
    val name: String,
    val type: ColumnType,
    val nullable: Boolean,
    val primaryKey: Boolean,
    val foreignKey: String?,
    val description: String,
)

enum class ColumnType {
    VARCHAR,
    INTEGER,
    BIGINT,
    DOUBLE,
    BOOLEAN,
    TIMESTAMP,
}
