package io.github.mbbhalla.agentio.examples.text2sql.model

import kotlinx.serialization.Serializable
import java.sql.ResultSet

@Serializable
data class Dataset(
    val columns: List<ColumnMeta>,
    val records: List<Record>,
) {
    @Serializable
    data class ColumnMeta(val name: String, val type: String)

    @Serializable
    data class Record(val values: Map<String, DataValue>)

    companion object {
        fun from(rs: ResultSet): Dataset {
            val meta = rs.metaData
            val columns = (1..meta.columnCount).map {
                ColumnMeta(name = meta.getColumnName(it), type = meta.getColumnTypeName(it))
            }
            val records = mutableListOf<Record>()
            while (rs.next()) {
                val values = columns.associate { col ->
                    col.name to DataValue.from(rs.getObject(col.name))
                }
                records.add(Record(values))
            }
            return Dataset(columns = columns, records = records)
        }
    }
}
