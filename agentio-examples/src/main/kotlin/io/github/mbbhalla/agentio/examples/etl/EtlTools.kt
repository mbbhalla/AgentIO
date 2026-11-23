package io.github.mbbhalla.agentio.examples.etl

import io.github.mbbhalla.agentio.core.common.Description
import io.github.mbbhalla.agentio.core.lib.server.AbstractMcpServer
import io.github.mbbhalla.agentio.core.lib.tool.AbstractMcpTool
import io.modelcontextprotocol.kotlin.sdk.server.RegisteredTool
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory

private val LOG = LoggerFactory.getLogger("io.github.mbbhalla.agentio.examples.etl.EtlTools")

/** Rows of a query result shown to an agent; enough to inspect values without filling its context. */
private const val QUERY_ROWS = 25

private fun CallToolRequest.string(name: String): String =
    params.arguments
        ?.get(name)
        ?.jsonPrimitive
        ?.content
        ?: throw IllegalArgumentException("'$name' is required")

/** Every target table with its description, so a planner can decide which ones the sources feed. */
internal class ListTargetTablesTool(
    private val workspace: EtlWorkspace,
) : AbstractMcpTool<Unit, ListTargetTablesTool.Output>() {
    @Serializable
    data class Output(
        val tables: List<TargetTableSummary>,
    )

    override fun name() = "list_target_tables"

    override fun description() = "List every table of the target database with its description and column count"

    override fun getInputKClass() = Unit::class

    override fun getOutputKClass() = Output::class

    override fun getToolConfig() = ToolConfig()

    override fun buildInput(callToolRequest: CallToolRequest) = Unit

    override fun invoke(input: Unit) = Output(workspace.targetTables())
}

internal class DescribeTargetTableTool(
    private val workspace: EtlWorkspace,
) : AbstractMcpTool<DescribeTargetTableTool.Input, DescribeTargetTableTool.Output>() {
    @Serializable
    data class Input(
        @field:Description("Target table name")
        val tableName: String,
    )

    @Serializable
    data class Output(
        val table: TargetTableDetail,
    )

    override fun name() = "describe_target_table"

    override fun description() =
        "Describe a target table: its columns with types, nullability, keys and descriptions, the rows loaded so far, " +
            "and the CREATE TABLE statement whose keys and CHECK constraints DuckDB enforces"

    override fun getInputKClass() = Input::class

    override fun getOutputKClass() = Output::class

    override fun getToolConfig() = ToolConfig()

    override fun buildInput(callToolRequest: CallToolRequest) = Input(callToolRequest.string("tableName"))

    override fun invoke(input: Input) = Output(workspace.describe(input.tableName))
}

internal class ProfileSourceTool(
    private val workspace: EtlWorkspace,
) : AbstractMcpTool<ProfileSourceTool.Input, ProfileSourceTool.Output>() {
    @Serializable
    data class Input(
        @field:Description("Source CSV file name")
        val fileName: String,
    )

    @Serializable
    data class Output(
        val profile: SourceProfile,
    )

    override fun name() = "profile_source"

    override fun description() =
        "Profile a source CSV file: its absolute path, first raw lines, the dialect and column types DuckDB's sniffer " +
            "guesses, its row count and its first rows read as text"

    override fun getInputKClass() = Input::class

    override fun getOutputKClass() = Output::class

    override fun getToolConfig() = ToolConfig()

    override fun buildInput(callToolRequest: CallToolRequest) = Input(callToolRequest.string("fileName"))

    override fun invoke(input: Input) = Output(workspace.profile(input.fileName))
}

internal class RunQueryTool(
    private val workspace: EtlWorkspace,
) : AbstractMcpTool<RunQueryTool.Input, QueryResult>() {
    @Serializable
    data class Input(
        @field:Description(
            "One DuckDB SELECT. It may read source files with read_csv('<absolute path>', ...) and query target tables",
        )
        val sql: String,
    )

    override fun name() = "run_query"

    override fun description() =
        "Run one DuckDB SELECT over source files and target tables and return its row count and first $QUERY_ROWS rows; " +
            "use it to check formats, distinct values, duplicates and joins before writing a load"

    override fun getInputKClass() = Input::class

    override fun getOutputKClass() = QueryResult::class

    override fun getToolConfig() = ToolConfig(emitSchemaAndRequiredAttributesForAllToolCalls = false)

    override fun buildInput(callToolRequest: CallToolRequest) = Input(callToolRequest.string("sql"))

    override fun invoke(input: Input) = workspace.query(input.sql, QUERY_ROWS)
}

/** Inserts an agent's SELECT into its target table under every constraint, reports the result, and removes the rows. */
internal class TryLoadTool(
    private val workspace: EtlWorkspace,
    private val references: List<Reference>,
) : AbstractMcpTool<TryLoadTool.Input, LoadTrial>() {
    @Serializable
    data class Input(
        @field:Description("Target table to load")
        val targetTable: String,
        @field:Description("One DuckDB SELECT whose column names are target column names")
        val selectSql: String,
    )

    override fun name() = "try_load"

    override fun description() =
        "Insert the SELECT into the target table as INSERT INTO <table> BY NAME, so DuckDB enforces the contract's types, " +
            "keys and CHECK constraints, then report inserted rows, NULLs per column, orphaned references and sample rows, " +
            "and delete the rows again. An error names the constraint or conversion DuckDB rejected"

    override fun getInputKClass() = Input::class

    override fun getOutputKClass() = LoadTrial::class

    override fun getToolConfig() = ToolConfig(emitSchemaAndRequiredAttributesForAllToolCalls = false)

    override fun buildInput(callToolRequest: CallToolRequest) =
        Input(callToolRequest.string("targetTable"), callToolRequest.string("selectSql"))

    override fun invoke(input: Input): LoadTrial =
        runCatching { workspace.tryLoad(input.targetTable, input.selectSql, references) }
            .onSuccess { LOG.info("try_load {}: {} rows, orphans {}", it.table, it.insertedRows, it.orphanedReferences) }
            .onFailure { LOG.info("try_load {} rejected: {}", input.targetTable, it.message) }
            .getOrThrow()
}

/** An in-process MCP server for one agent's tools. */
internal class EtlMcpServer(
    private val name: String,
    private val tools: List<AbstractMcpTool<*, *>>,
) : AbstractMcpServer() {
    override fun tools(): Set<RegisteredTool> = tools.map { it() }.toSet()

    override fun capabilities() = ServerCapabilities(tools = ServerCapabilities.Tools(listChanged = false))

    override fun name() = name

    override fun version() = "1.0.0"
}
