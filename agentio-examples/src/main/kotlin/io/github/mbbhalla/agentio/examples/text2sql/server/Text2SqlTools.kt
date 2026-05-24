package io.github.mbbhalla.agentio.examples.text2sql.server

import io.github.mbbhalla.agentio.core.common.Description
import io.github.mbbhalla.agentio.core.lib.tool.AbstractMcpTool
import io.github.mbbhalla.agentio.examples.text2sql.data.RetailDatabase
import io.github.mbbhalla.agentio.examples.text2sql.model.ColumnType
import io.github.mbbhalla.agentio.examples.text2sql.model.Dataset
import io.github.mbbhalla.agentio.examples.text2sql.model.ExplainResult
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

internal class ListTablesTool : AbstractMcpTool<Unit, ListTablesTool.Output>() {

    @Serializable
    data class Output(
        @field:Description("Available table names in the database")
        val tableNames: Set<String>,
    )

    override fun name() = "list_tables"
    override fun description() = "List all tables available in the retail database"
    override fun getInputKClass() = Unit::class
    override fun getOutputKClass() = Output::class
    override fun getToolConfig() = ToolConfig(emitSchemaAndRequiredAttributesForAllToolCalls = true)

    override fun buildInput(callToolRequest: CallToolRequest) = Unit

    override fun invoke(input: Unit) = Output(tableNames = RetailDatabase.listTables())
}

internal class GetTablesTool : AbstractMcpTool<GetTablesTool.Input, GetTablesTool.Output>() {

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
        val tables = input.tableNames.mapNotNull { name ->
            RetailDatabase.getTableInfo(name)?.let { info ->
                Output.TableSchema(
                    name = info.name,
                    description = info.description,
                    columns = info.columns.map { col ->
                        Output.Column(
                            name = col.name,
                            type = col.type,
                            nullable = col.nullable,
                            primaryKey = col.primaryKey,
                            foreignKey = col.foreignKey,
                            description = col.description,
                        )
                    },
                )
            }
        }
        require(tables.isNotEmpty()) { "No matching tables found for: ${input.tableNames}" }
        return Output(tables = tables)
    }
}

internal class ExecuteSqlTool : AbstractMcpTool<ExecuteSqlTool.Input, ExecuteSqlTool.Output>() {

    @Serializable
    data class Input(
        @field:Description("DuckDB SQL SELECT statement to execute against the retail database")
        val sql: String,
    )

    @Serializable
    data class Output(
        @field:Description("Query result set")
        val resultSet: Dataset,
    )

    override fun name() = "execute_sql"
    override fun description() = "Execute a SQL SELECT query against the retail database and return results"
    override fun getInputKClass() = Input::class
    override fun getOutputKClass() = Output::class
    override fun getToolConfig() = ToolConfig(emitSchemaAndRequiredAttributesForAllToolCalls = true)

    override fun buildInput(callToolRequest: CallToolRequest): Input {
        val sql = callToolRequest.params.arguments?.get("sql")?.jsonPrimitive?.content
            ?: throw IllegalArgumentException("'sql' parameter is required")
        return Input(sql = sql)
    }

    override fun invoke(input: Input): Output {
        val explainResult = RetailDatabase.explain(input.sql)
        require(explainResult.isSuccess) {
            "SQL validation failed: ${(explainResult as ExplainResult.Failure).error}"
        }
        return Output(resultSet = RetailDatabase.executeQuery(input.sql))
    }
}
