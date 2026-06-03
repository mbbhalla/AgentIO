package io.github.mbbhalla.agentio.module.compass.function

import aws.sdk.kotlin.services.bedrockruntime.BedrockRuntimeClient
import io.github.mbbhalla.agentio.core.common.Description
import io.github.mbbhalla.agentio.core.common.JsonSchemaUtil
import io.github.mbbhalla.agentio.core.lib.AbstractAgenticFunction
import io.github.mbbhalla.agentio.core.lib.Instructible
import io.github.mbbhalla.agentio.core.lib.tool.McpClients
import io.github.mbbhalla.agentio.core.lib.tool.NamedClient
import io.github.mbbhalla.agentio.core.model.AgentConfiguration
import io.github.mbbhalla.agentio.core.model.LLM
import io.github.mbbhalla.agentio.core.model.LanguageModelParameters
import io.github.mbbhalla.agentio.core.model.Temperature
import io.github.mbbhalla.agentio.data.env.DatabaseEnvironment
import io.github.mbbhalla.agentio.module.compass.model.AnalysisResult
import io.github.mbbhalla.agentio.module.compass.server.AnalyzerMcpServer
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlin.time.Duration.Companion.minutes

/**
 * Translates an English problem statement, posed against a tabular dataset, into a grounded
 * [AnalysisResult] — every result item carries the SQL that produces its value from the
 * underlying dataset. Domain-agnostic: the analyzer discovers schema via tools and grounds
 * arithmetic in SQL queries against the active [DatabaseEnvironment].
 */
class AnalyzerAgenticFunction internal constructor(
    agentConfiguration: AgentConfiguration,
) : AbstractAgenticFunction<AnalyzerAgenticFunction.Input, AnalyzerAgenticFunction.Output>(agentConfiguration) {
    @Serializable
    data class Input(
        @field:Description("English problem statement to analyze")
        val objective: String,
        @field:Description("Name of the dataset to analyze against")
        val datasetName: String,
    ) : Instructible.WithInstruction {
        override fun instructionId() = "compass-analyzer"

        override fun systemInstruction(): String? = null

        override fun instruction(): String {
            val outputSchema =
                JsonSchemaUtil.json.encodeToString(
                    JsonObject.serializer(),
                    JsonSchemaUtil.generateJsonSchema(AnalysisResult::class),
                )
            return """
                You are a Domain Analyst Agent grounded in a tabular dataset.

                OBJECTIVE:
                - $objective

                DATA SOURCE:
                - Dataset: $datasetName
                - Use the list_tables and get_tables tools to discover schemas.
                - Use the execute_sql tool to run SELECT queries — never compute aggregates yourself.
                - When data is unavailable, write "unknown" rather than guessing.

                OUTPUT REQUIREMENTS:
                - Produce a JSON AnalysisResult.
                - Schema: $outputSchema
                - Every resultItem MUST include a SQL SELECT against real dataset tables that returns
                  exactly one scalar (one row, one column) equal to resultItem.value.
                - Use the analysis_result_validator tool to verify your AnalysisResult JSON
                  before emitting the final answer. Fix any errors it reports.

                COMPUTATIONAL RULES:
                - Do not perform arithmetic in your head. Push SUM, AVG, MIN, MAX, COUNT, and math
                  expressions into SQL via execute_sql.
                - Identify quantifiable gaps and opportunities — bounds, capacities, lead-times,
                  demand, etc. — that downstream constraint generation can use.

                The smtlib2ConstraintGeneratorExplanation field will be consumed by an SMTLIB2
                generator agent — write it as concise plain text describing variables, constraints, and bounds.
                """.trimIndent()
        }
    }

    @Serializable
    data class Output(
        @field:Description("Grounded analysis result with per-item SQL provenance")
        val analysisResult: AnalysisResult,
    )

    override fun getInputKClass() = Input::class

    override fun getOutputKClass() = Output::class

    companion object {
        private const val TEMPERATURE = 0.2f
        private const val AGENT_ID = "compass-analyzer"

        suspend fun create(
            env: DatabaseEnvironment,
            problemDomain: String,
            llm: LLM = LLM.ANTHROPIC_CLAUDE_OPUS_4_6_V1_CROSS_REGION_INFERENCE,
        ): AnalyzerAgenticFunction {
            DatabaseEnvironment.activate(env)

            val server = AnalyzerMcpServer(env)
            val exchange = server.pipedStreamsExchange()
            val mcpClient = Client(clientInfo = Implementation(name = "compass-analyzer-client", version = "1.0.0"))
            mcpClient.connect(exchange.stdioClientTransport())

            return AnalyzerAgenticFunction(
                AgentConfiguration(
                    agentId = AGENT_ID,
                    problemDomain = problemDomain,
                    languageModelParameters =
                        LanguageModelParameters(
                            llm = llm,
                            temperature = Temperature(TEMPERATURE),
                        ),
                    bedrockRuntimeClient =
                        BedrockRuntimeClient {
                            this.region = "us-west-2"
                            this.httpClient { socketReadTimeout = 15.minutes }
                        },
                    toolsProvider =
                        McpClients(
                            set =
                                setOf(
                                    NamedClient(
                                        name = "compass",
                                        client = mcpClient,
                                        deniedTools = emptySet(),
                                    ),
                                ),
                        ),
                ),
            )
        }
    }
}
