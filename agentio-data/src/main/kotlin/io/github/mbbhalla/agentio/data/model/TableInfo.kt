package io.github.mbbhalla.agentio.data.model

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
