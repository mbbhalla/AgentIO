package io.github.mbbhalla.agentio.examples.text2sql.server

import io.github.mbbhalla.agentio.core.common.Description
import io.github.mbbhalla.agentio.core.lib.tool.AbstractMcpTool
import io.github.mbbhalla.agentio.examples.text2sql.model.ColumnType
import io.github.mbbhalla.agentio.examples.text2sql.model.DatabaseEnvironment
import io.github.mbbhalla.agentio.examples.text2sql.model.Dataset
import io.github.mbbhalla.agentio.examples.text2sql.model.ExplainResult
import io.github.mbbhalla.agentio.examples.text2sql.model.ForeignKeyRef
import io.github.mbbhalla.agentio.examples.text2sql.model.TableName
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

internal class ListTablesTool(
    private val env: DatabaseEnvironment,
) : AbstractMcpTool<Unit, ListTablesTool.Output>() {

    @Serializable
    data class Output(
        @field:Description("Available table names in the database")
        val tableNames: Set<String>,
    )

    override fun name() = "list_tables"
    override fun description() = "List all tables available in the database"
    override fun getInputKClass() = Unit::class
    override fun getOutputKClass() = Output::class
    override fun getToolConfig() = ToolConfig(emitSchemaAndRequiredAttributesForAllToolCalls = true)

    override fun buildInput(callToolRequest: CallToolRequest) = Unit

    override fun invoke(input: Unit) = Output(
        tableNames = env.listTables().map { it.value }.toSet(),
    )
}

internal class GetTablesTool(
    private val env: DatabaseEnvironment,
) : AbstractMcpTool<GetTablesTool.Input, GetTablesTool.Output>() {

    @Serializable
    data class Input(
        @field:Description("Table names to retrieve schema information for")
        val tableNames: List<String>,
    )

    @Serializable
    data class Output(
        @field:Description("Schema information for requested tables")
        val tables: List<TableSchema>,
    ) {
        @Serializable
        data class TableSchema(
            val name: String,
            val description: String,
            val columns: List<Column>,
        )

        @Serializable
        data class Column(
            val name: String,
            val type: ColumnType,
            val nullable: Boolean,
            val primaryKey: Boolean,
            val foreignKey: String?,
            val description: String,
        )
    }

    override fun name() = "get_tables"
    override fun description() = "Get schema information (columns, types, keys, descriptions) for specified tables"
    override fun getInputKClass() = Input::class
    override fun getOutputKClass() = Output::class
    override fun getToolConfig() = ToolConfig(emitSchemaAndRequiredAttributesForAllToolCalls = false)

    override fun buildInput(callToolRequest: CallToolRequest): Input {
        val tableNames = callToolRequest.params.arguments?.get("tableNames")
            ?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()
        return Input(tableNames = tableNames)
    }

    override fun invoke(input: Input): Output {
        val tables = input.tableNames.map { name ->
            val info = env.getTableInfo(TableName(name))
            Output.TableSchema(
                name = info.name.value,
                description = info.description,
                columns = info.columns.map { col ->
                    Output.Column(
                        name = col.name.value,
                        type = col.type,
                        nullable = col.nullable,
                        primaryKey = col.primaryKey,
                        foreignKey = col.foreignKey?.toDisplayString(),
                        description = col.description,
                    )
                },
            )
        }
        require(tables.isNotEmpty()) { "No table names provided" }
        return Output(tables = tables)
    }

    private fun ForeignKeyRef.toDisplayString(): String = "${table.value}.${column.value}"
}

internal class ExecuteSqlTool(
    private val env: DatabaseEnvironment,
) : AbstractMcpTool<ExecuteSqlTool.Input, ExecuteSqlTool.Output>() {

    @Serializable
    data class Input(
        @field:Description("DuckDB SQL SELECT statement to execute against the database")
        val sql: String,
    )

    @Serializable
    data class Output(
        @field:Description("Query result set")
        val resultSet: Dataset,
    )

    override fun name() = "execute_sql"
    override fun description() = "Execute a SQL SELECT query against the database and return results"
    override fun getInputKClass() = Input::class
    override fun getOutputKClass() = Output::class
    override fun getToolConfig() = ToolConfig(emitSchemaAndRequiredAttributesForAllToolCalls = true)

    override fun buildInput(callToolRequest: CallToolRequest): Input {
        val sql = callToolRequest.params.arguments?.get("sql")?.jsonPrimitive?.content
            ?: throw IllegalArgumentException("'sql' parameter is required")
        return Input(sql = sql)
    }

    override fun invoke(input: Input): Output {
        val explainResult = env.explain(input.sql)
        require(explainResult.isSuccess) {
            "SQL validation failed: ${(explainResult as ExplainResult.Failure).error}"
        }
        return Output(resultSet = env.executeQuery(input.sql))
    }
}
