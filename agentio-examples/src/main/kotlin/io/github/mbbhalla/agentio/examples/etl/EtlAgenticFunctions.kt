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

/** When the agents ask the reviewer instead of deciding, and how they treat the reviewer's answers. */
private val REVIEW_RULES =
    """
    QUESTIONS FOR THE REVIEWER:
    - A load can pass every constraint and still be wrong. When a choice changes the loaded data and neither the sources
      nor the target descriptions settle it, raise a question instead of deciding silently. Typical cases: a source code
      or status with no matching target code; a unit or conversion the description does not give; a target column no
      source provides that you would fill with a default rather than leave NULL; rows you would drop or merge to satisfy
      a constraint, such as rows that reference something the sources do not contain.
    - Give two to four options, the evidence (source values with counts, the target description, any DuckDB error) and
      the index of the option your SQL uses meanwhile. Choose the least destructive option and keep going with it, so the
      rest of the load can still be checked.
    - Do not ask what the sources or descriptions settle, about formatting, or anything already decided.
    - DECISIONS in your input are the reviewer's answers to earlier questions, and REVIEWER FEEDBACK is the reviewer's
      own instruction. Both are binding: apply them exactly and do not ask about them again.
    - OPEN QUESTIONS in your input, when there are any, are already with the reviewer. Follow their assumed option and do
      not raise them again, not even in other words.
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
        @field:Description("DECISIONS: the reviewer's answers to earlier questions; binding")
        val decisions: List<DecidedQuestion>,
        @field:Description("REVIEWER FEEDBACK on earlier proposals, oldest first; binding")
        val reviewerFeedback: List<String>,
        @field:Description("Why the previous plan was rejected, if there was one")
        val previousAttemptError: String?,
    ) : Instructible.WithInstruction {
        override fun instructionId() = "etl-plan"

        override fun instruction() =
            "Plan how the ${sourceFiles.size} source CSV files $sourceFiles load into the target database of " +
                "$targetTableCount tables: which tables they feed, in what order, how each table's rows and columns " +
                "derive from them, and which column references between the planned tables the descriptions state." +
                (if (decisions.isEmpty() && reviewerFeedback.isEmpty()) "" else " Apply every decision and all reviewer feedback.") +
                (previousAttemptError?.let { " The previous plan was rejected: $it" } ?: "")

        override fun systemInstruction(): String? = null
    }

    override fun getInputKClass() = Input::class

    override fun getOutputKClass() = LoadPlan::class

    override fun validateOutput(output: LoadPlan): Result<LoadPlan> =
        LoadPlanCheck.validate(output, workspace).mapCatching { plan ->
            val problems = plan.questions.flatMap { it.problems() }
            require(problems.isEmpty()) { "The plan's questions are invalid: ${problems.joinToString("; ")}" }
            plan
        }

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
            7. Raise a question for each choice you can already see while planning: which tables or rows to load, which
               key scheme to use, or how a column is converted. The SQL writers follow your assumed options and raise
               only what they find later.

            $SQL_RULES

            $REVIEW_RULES
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
        @field:Description("DECISIONS: the reviewer's answers to earlier questions; binding")
        val decisions: List<DecidedQuestion>,
        @field:Description("REVIEWER FEEDBACK on earlier proposals, oldest first; binding")
        val reviewerFeedback: List<String>,
        @field:Description("OPEN QUESTIONS: already put to the reviewer this round by the planner or earlier steps")
        val openQuestions: List<RaisedQuestion>,
        @field:Description("Why the previous attempt at this step was rejected, if there was one")
        val previousAttemptError: String?,
    ) : Instructible.WithInstruction {
        override fun instructionId() = "etl-load-${step.targetTable}"

        override fun instruction() =
            "Write the DuckDB SELECT that loads target table '${step.targetTable}' from ${step.sourceFiles}, following " +
                "the step's mapping, and prove it with try_load." +
                (if (decisions.isEmpty() && reviewerFeedback.isEmpty()) "" else " Apply every decision and all reviewer feedback.") +
                (previousAttemptError?.let { " The previous attempt was rejected: $it" } ?: "")

        override fun systemInstruction(): String? = null
    }

    @Serializable
    data class Output(
        @field:Description("The step's target table")
        val targetTable: String,
        @field:Description("The SELECT that try_load last accepted; null only when no SQL can pass without the reviewer's answers")
        val selectSql: String?,
        @field:Description("How the SELECT derives the table, including any rows it merges or drops and why")
        val explanation: String,
        @field:Description("Choices for the reviewer that the sources and descriptions do not settle; empty when there are none")
        val questions: List<RaisedQuestion>,
    )

    override fun getInputKClass() = Input::class

    override fun getOutputKClass() = Output::class

    /**
     * SQL must still load under every constraint, with rows and without orphans, once the conversation has
     * ended; an answer without SQL must say what it needs to know.
     */
    override fun validateOutput(output: Output): Result<Output> =
        runCatching {
            val problems = output.questions.flatMap { it.problems() }
            require(problems.isEmpty()) { "The questions are invalid: ${problems.joinToString("; ")}" }
            val sql = output.selectSql
            if (sql == null) {
                require(output.questions.isNotEmpty()) { "An answer without selectSql must raise the questions that block it" }
            } else {
                val trial = workspace.tryLoad(output.targetTable, sql, references)
                require(trial.insertedRows > 0) { "The SELECT loads no rows into '${trial.table}'" }
                require(trial.orphanedReferences.isEmpty()) { "The SELECT leaves orphaned references: ${trial.orphanedReferences}" }
            }
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
               drop rows only when they are exact duplicates, the mapping says so, or a decision says so. Any other merge
               or drop needs a question, and your explanation says which rows and why.
            5. Answer with the SELECT that try_load last accepted and your questions. Only when no SQL can pass without the
               reviewer's answers, answer with selectSql null and the questions that block you.

            $SQL_RULES

            $REVIEW_RULES
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
