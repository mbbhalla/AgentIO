package io.github.mbbhalla.agentio.data.model

import kotlinx.serialization.Serializable
import org.mvel2.MVEL
import java.sql.ResultSet

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
