package io.github.mbbhalla.agentio.module.compass.model

import io.github.mbbhalla.agentio.data.env.DatabaseEnvironment
import io.github.mbbhalla.agentio.data.model.ColumnName
import io.github.mbbhalla.agentio.data.model.TableName

/**
 * Type tag for SMTLIB2 decision variables.
 */
enum class VariableType {
    BOOLEAN,
    LONG,
    DOUBLE,
    STRING,
}

/**
 * Canonical kind of decision variable that the constraint generator may emit in an
 * SMTLIB2 formula. Each kind is anchored to a dataset table and to a fixed list of key
 * columns on that table. The analyzer surfaces values for those keys to identify a
 * specific variable instance (e.g. a particular order-line, site/product pair, …).
 *
 * Concrete variable kinds live in the consuming application and extend this class as
 * `data object` singletons. They are passed into [io.github.mbbhalla.agentio.module.compass.function.ConstraintGeneratorAgenticFunction]
 * via its `create(env, variables, llm)` factory.
 */
abstract class SMTLIB2Variable {
    abstract val variableNamePrefix: String
    abstract val type: VariableType
    abstract val description: String
    abstract val associatedDataTable: TableName
    abstract val keyColumns: List<ColumnName>

    fun nameFormat(env: DatabaseEnvironment): String {
        val info = env.getTableInfo(associatedDataTable)
        val tableColumns = info.columns.map { it.name.value }.toSet()
        val missing = keyColumns.filterNot { it.value in tableColumns }
        require(missing.isEmpty()) {
            "Table '${associatedDataTable.value}' is missing key columns: ${missing.map { it.value }}"
        }
        return keyColumns.joinToString(
            separator = VARIABLE_VALUES_SEP,
            prefix = "$variableNamePrefix$VARIABLE_VALUES_SEP",
        ) { "<${it.value}>" }
    }

    fun serialize(env: DatabaseEnvironment): String =
        """
        {
          "type": "$type",
          "description": "${description.replace("\n", " ").trim()}",
          "nameFormat": "${nameFormat(env)}"
        }
        """.trimIndent()

    companion object {
        const val VARIABLE_VALUES_SEP = ":::"
    }
}
