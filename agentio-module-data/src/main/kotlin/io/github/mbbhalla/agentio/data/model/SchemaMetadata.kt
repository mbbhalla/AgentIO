package io.github.mbbhalla.agentio.data.model

import com.charleskorn.kaml.Yaml
import kotlinx.serialization.Serializable
import java.io.File

@Serializable
data class SchemaMetadata(
    val tables: List<TableMetadataEntry>,
) {
    @Serializable
    data class TableMetadataEntry(
        val name: String,
        val description: String = "",
        val columns: List<ColumnMetadataEntry> = emptyList(),
    )

    @Serializable
    data class ColumnMetadataEntry(
        val name: String,
        val description: String = "",
        val primaryKey: Boolean = false,
        val unique: Boolean = false,
        val notNull: Boolean = false,
        val foreignKey: String? = null,
    )

    fun tableDescription(tableName: TableName): String? =
        tables
            .firstOrNull { it.name == tableName.value }
            ?.description
            ?.ifBlank { null }

    fun columnDescription(
        tableName: TableName,
        columnName: ColumnName,
    ): String? =
        tables
            .firstOrNull { it.name == tableName.value }
            ?.columns
            ?.firstOrNull { it.name == columnName.value }
            ?.description
            ?.ifBlank { null }

    fun columnConstraints(
        tableName: TableName,
        columnName: ColumnName,
    ): ColumnMetadataEntry? =
        tables
            .firstOrNull { it.name == tableName.value }
            ?.columns
            ?.firstOrNull { it.name == columnName.value }

    companion object {
        const val FILENAME = "schemaMetadata.yml"

        fun fromFile(file: File): SchemaMetadata = Yaml.default.decodeFromString(serializer(), file.readText())
    }
}
