package io.github.mbbhalla.agentio.data.env

import io.github.mbbhalla.agentio.data.authz.CriteriaRenderer
import io.github.mbbhalla.agentio.data.model.ColumnDefinition
import io.github.mbbhalla.agentio.data.model.ColumnName
import io.github.mbbhalla.agentio.data.model.ColumnType
import io.github.mbbhalla.agentio.data.model.DataValue
import io.github.mbbhalla.agentio.data.model.ForeignKey
import io.github.mbbhalla.agentio.data.model.TableDefinition
import io.github.mbbhalla.agentio.data.model.TableName
import io.github.mbbhalla.agentio.data.model.ValueConstraint
import java.time.Instant
import java.time.ZoneOffset

/**
 * Renders a [TableDefinition] as DuckDB DDL. Keys and value constraints are declared inline because
 * DuckDB has no `ALTER TABLE ADD FOREIGN KEY`; identifiers are double-quoted so that a column named
 * like a keyword (`order`, `select`) is still valid.
 */
internal object DomainModelDdl {
    fun createTable(table: TableDefinition): String {
        val clauses =
            buildList {
                table.columns.forEach { add(columnClause(it)) }
                if (table.primaryKey.isNotEmpty()) add("PRIMARY KEY (${table.primaryKey.joinQuoted()})")
                table.uniqueKeys.forEach { add("UNIQUE (${it.joinQuoted()})") }
                table.foreignKeys.forEach { add(foreignKeyClause(it)) }
                table.columns.forEach { column -> column.constraints.forEach { add("CHECK (${checkExpression(column, it)})") } }
            }
        return "CREATE TABLE ${table.name.quoted()} (\n  ${clauses.joinToString(",\n  ")}\n)"
    }

    /** The boolean expression DuckDB evaluates for [constraint] on [column]; `NULL` yields `NULL`, which a `CHECK` lets pass. */
    fun checkExpression(
        column: ColumnDefinition,
        constraint: ValueConstraint,
    ): String {
        val name = column.name.quoted()
        return when (constraint) {
            is ValueConstraint.OneOf -> "$name IN (${constraint.values.joinToString(", ") { literal(it, column.type) }})"
            is ValueConstraint.Minimum -> "$name ${if (constraint.exclusive) ">" else ">="} ${literal(constraint.value, column.type)}"
            is ValueConstraint.Maximum -> "$name ${if (constraint.exclusive) "<" else "<="} ${literal(constraint.value, column.type)}"
            is ValueConstraint.MinLength -> "length($name) >= ${constraint.length}"
            is ValueConstraint.MaxLength -> "length($name) <= ${constraint.length}"
            is ValueConstraint.Pattern -> "regexp_matches($name, ${CriteriaRenderer.literal(DataValue.StringValue(constraint.regex))})"
        }
    }

    /** A literal for a value compared with a column of [type]: a timestamp against a `DATE` column renders as a `DATE`. */
    private fun literal(
        value: DataValue,
        type: ColumnType,
    ): String =
        if (value is DataValue.TimestampValue && type == ColumnType.DATE) {
            "DATE '${Instant.parse(value.value).atOffset(ZoneOffset.UTC).toLocalDate()}'"
        } else {
            CriteriaRenderer.literal(value)
        }

    /** `COMMENT ON` for the table and for every column with a description; blank descriptions are skipped. */
    fun comments(table: TableDefinition): List<String> =
        buildList {
            if (table.description.isNotBlank()) {
                add("COMMENT ON TABLE ${table.name.quoted()} IS ${table.description.literal()}")
            }
            table.columns
                .filter { it.description.isNotBlank() }
                .forEach { add("COMMENT ON COLUMN ${table.name.quoted()}.${it.name.quoted()} IS ${it.description.literal()}") }
        }

    private fun columnClause(column: ColumnDefinition): String =
        "${column.name.quoted()} ${column.type.name}" + if (column.nullable) "" else " NOT NULL"

    private fun foreignKeyClause(fk: ForeignKey): String =
        "FOREIGN KEY (${fk.columns.joinQuoted()}) REFERENCES ${fk.referencedTable.quoted()} (${fk.referencedColumns.joinQuoted()})"

    private fun TableName.quoted(): String = "\"$value\""

    private fun ColumnName.quoted(): String = "\"$value\""

    private fun List<ColumnName>.joinQuoted(): String = joinToString(", ") { it.quoted() }

    private fun String.literal(): String = "'${replace("'", "''")}'"
}
