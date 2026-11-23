package io.github.mbbhalla.agentio.examples.etl

import aws.sdk.kotlin.services.bedrockruntime.BedrockRuntimeClient
import io.github.mbbhalla.agentio.core.common.Description
import io.github.mbbhalla.agentio.core.lib.AbstractAgenticFunction
import io.github.mbbhalla.agentio.core.lib.Instructible
import io.github.mbbhalla.agentio.core.lib.tool.McpClients
import io.github.mbbhalla.agentio.core.lib.tool.NamedClient
import io.github.mbbhalla.agentio.core.model.AgentConfiguration
import io.github.mbbhalla.agentio.core.model.LLM
import io.github.mbbhalla.agentio.core.model.LanguageModelParameters
import io.github.mbbhalla.agentio.core.model.Temperature
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import kotlinx.serialization.Serializable
import kotlin.time.Duration.Companion.minutes

/** Rules both agents follow when they write SQL over the sources. */
private val SQL_RULES =
    """
    SQL RULES:
    - Every SQL you write is one DuckDB SELECT. Read a source only as read_csv('<absolute path from profile_source>', ...);
      no other file or table function is allowed.
    - Never write the character ';' in SQL, not even inside a string: it is rejected. For a semicolon delimiter, let
      read_csv detect it or write delim = chr(59).
    - Always pass all_varchar = true to read_csv and convert each column yourself (strptime, CAST, regexp_extract,
      CASE). The sniffer's types are guesses: they drop leading zeros from identifiers and misread mixed formats.
    - Look before you convert: query distinct values, string lengths, formats and duplicates with run_query. A
      conversion that works on the first rows can fail on a later one; prefer conversions that fail loudly over
      try_ functions that turn a bad value into NULL.
    """.trimIndent()

private fun configuration(
    agentId: String,
    systemPrompt: String,
    client: Client,
    llm: LLM,
): AgentConfiguration =
    AgentConfiguration(
        agentId = agentId,
        problemDomain = "ETL into a database defined by an Open Data Contract",
        languageModelParameters =
            LanguageModelParameters(
                llm = llm,
                temperature = Temperature(TEMPERATURE).takeIf { llm.supportsSamplingParameters },
            ),
        bedrockRuntimeClient =
            BedrockRuntimeClient {
                region = BEDROCK_REGION
                httpClient { socketReadTimeout = 15.minutes }
            },
        toolsProvider = McpClients(set = setOf(NamedClient(name = "etl", client = client, deniedTools = emptySet()))),
        systemPrompt = systemPrompt,
        maxTurnLimit = MAX_TURNS,
    )

private suspend fun connect(server: EtlMcpServer): Client {
    val client = Client(clientInfo = Implementation(name = "${server.name()}-client", version = "1.0.0"))
    client.connect(server.pipedStreamsExchange().stdioClientTransport())
    return client
}

private const val TEMPERATURE = 0.2f
private const val BEDROCK_REGION = "us-west-2"

/** Turns (model calls plus tool calls) one invocation may take before it fails. */
private const val MAX_TURNS = 150

/**
 * Decides which target tables the sources feed, in what order, and how each table's rows derive from
 * them. Its plan is checked by [LoadPlanCheck] before any SQL is written.
 */
internal class EtlPlannerAgenticFunction private constructor(
    configuration: AgentConfiguration,
    private val workspace: EtlWorkspace,
) : AbstractAgenticFunction<EtlPlannerAgenticFunction.Input, LoadPlan>(configuration) {
    @Serializable
    data class Input(
        @field:Description("Source CSV file names")
        val sourceFiles: List<String>,
        @field:Description("Number of tables in the target database")
        val targetTableCount: Int,
        @field:Description("Why the previous plan was rejected, if there was one")
        val previousAttemptError: String?,
    ) : Instructible.WithInstruction {
        override fun instructionId() = "etl-plan"

        override fun instruction() =
            "Plan how the ${sourceFiles.size} source CSV files $sourceFiles load into the target database of " +
                "$targetTableCount tables: which tables they feed, in what order, how each table's rows and columns " +
                "derive from them, and which column references between the planned tables the descriptions state." +
                (previousAttemptError?.let { " The previous plan was rejected: $it" } ?: "")

        override fun systemInstruction(): String? = null
    }

    override fun getInputKClass() = Input::class

    override fun getOutputKClass() = LoadPlan::class

    override fun validateOutput(output: LoadPlan): Result<LoadPlan> = LoadPlanCheck.validate(output, workspace)

    companion object {
        private val SYSTEM_PROMPT =
            """
            You are a data engineer planning an ETL into a target database whose schema is a data contract. The source
            files come from other systems: their names, layouts, keys and codes do not match the target.

            STEPS:
            1. profile_source every source file, then run_query to learn what each row is, which keys identify the same
               thing across files (an employee may be an id in one file, a login in another and a national ID in a
               third), and how values are encoded.
            2. list_target_tables, then describe_target_table every table that may hold this data. Descriptions state
               codes, units and references ("Foreign key to Employee.BusinessEntityID").
            3. Plan every target table the sources can fill. A source row can feed several tables (an employee is also
               a person and a business entity; a column packing several phone numbers feeds a phone table and its type
               lookup) and several source rows can make one target row (events become intervals, repeated pay periods
               become rate changes). Leave tables the sources cannot fill out of the plan.
            4. Order the steps so that each table comes after the tables it references or looks up.
            5. In each step's mapping, state the grain, how each key is derived so that it is identical in every table
               that uses it, how each column is converted, and which rows are merged or dropped and why.
            6. List the column references between planned tables that the descriptions state.

            $SQL_RULES
            """.trimIndent()

        suspend fun create(
            workspace: EtlWorkspace,
            llm: LLM,
        ): EtlPlannerAgenticFunction {
            val server =
                EtlMcpServer(
                    "etl-planner",
                    listOf(
                        ListTargetTablesTool(workspace),
                        DescribeTargetTableTool(workspace),
                        ProfileSourceTool(workspace),
                        RunQueryTool(workspace),
                    ),
                )
            return EtlPlannerAgenticFunction(configuration("etl-planner", SYSTEM_PROMPT, connect(server), llm), workspace)
        }
    }
}

/**
 * Writes the SELECT that loads one planned table. It iterates with try_load against the real constraints;
 * its answer counts only if the same check passes again after the conversation ([validateOutput]).
 */
internal class EtlSqlWriterAgenticFunction private constructor(
    configuration: AgentConfiguration,
    private val workspace: EtlWorkspace,
    private val references: List<Reference>,
) : AbstractAgenticFunction<EtlSqlWriterAgenticFunction.Input, EtlSqlWriterAgenticFunction.Output>(configuration) {
    @Serializable
    data class Input(
        @field:Description("The step to implement")
        val step: LoadStep,
        @field:Description("The whole plan, so that keys are derived the same way as in the other steps")
        val plan: List<LoadStep>,
        @field:Description("Target tables already loaded, which the SELECT may join to look up keys")
        val loadedTables: List<String>,
        @field:Description("References from this table that must have no orphaned values")
        val references: List<Reference>,
        @field:Description("Why the previous attempt at this step was rejected, if there was one")
        val previousAttemptError: String?,
    ) : Instructible.WithInstruction {
        override fun instructionId() = "etl-load-${step.targetTable}"

        override fun instruction() =
            "Write the DuckDB SELECT that loads target table '${step.targetTable}' from ${step.sourceFiles}, following " +
                "the step's mapping, and prove it with try_load." +
                (previousAttemptError?.let { " The previous attempt was rejected: $it" } ?: "")

        override fun systemInstruction(): String? = null
    }

    @Serializable
    data class Output(
        @field:Description("The step's target table")
        val targetTable: String,
        @field:Description("The SELECT that try_load last accepted")
        val selectSql: String,
        @field:Description("How the SELECT derives the table, including any rows it merges or drops and why")
        val explanation: String,
    )

    override fun getInputKClass() = Input::class

    override fun getOutputKClass() = Output::class

    /** The SQL must still load under every constraint, with rows and without orphans, once the conversation has ended. */
    override fun validateOutput(output: Output): Result<Output> =
        runCatching {
            val trial = workspace.tryLoad(output.targetTable, output.selectSql, references)
            require(trial.insertedRows > 0) { "The SELECT loads no rows into '${trial.table}'" }
            require(trial.orphanedReferences.isEmpty()) { "The SELECT leaves orphaned references: ${trial.orphanedReferences}" }
            output
        }

    companion object {
        private val SYSTEM_PROMPT =
            """
            You are a data engineer writing the SQL for one step of an ETL plan. The target table's schema is a data
            contract that DuckDB enforces: types, primary keys, NOT NULL and CHECK constraints such as maximum lengths.

            STEPS:
            1. describe_target_table the step's table and every loaded table you will join; profile_source each source.
            2. Write one SELECT whose output columns are named exactly like target columns. It is run as
               INSERT INTO <table> BY NAME, so a column you leave out is NULL. Leave a column NULL only when neither the
               sources nor its description give its value.
            3. Look keys up by joining the loaded target tables instead of deriving them again, so that the same thing has
               the same key in every table.
            4. try_load the SELECT. Fix it until try_load reports no error and no orphaned references, and its NULL counts
               and inserted rows are what the sources imply. Every source row that belongs in the table must load; merge or
               drop rows only when they are exact duplicates or the mapping says so, and say why in your explanation.
            5. Answer with the SELECT that try_load last accepted.

            $SQL_RULES
            """.trimIndent()

        suspend fun create(
            workspace: EtlWorkspace,
            references: List<Reference>,
            llm: LLM,
        ): EtlSqlWriterAgenticFunction {
            val server =
                EtlMcpServer(
                    "etl-sql-writer",
                    listOf(
                        DescribeTargetTableTool(workspace),
                        ProfileSourceTool(workspace),
                        RunQueryTool(workspace),
                        TryLoadTool(workspace, references),
                    ),
                )
            return EtlSqlWriterAgenticFunction(
                configuration("etl-sql-writer", SYSTEM_PROMPT, connect(server), llm),
                workspace,
                references,
            )
        }
    }
}
