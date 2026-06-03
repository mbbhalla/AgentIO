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
import io.github.mbbhalla.agentio.module.compass.model.SMTLIB2Variable
import io.github.mbbhalla.agentio.module.compass.server.ConstraintGeneratorMcpServer
import io.github.mbbhalla.agentio.module.solver.SMTLIBv2Formula
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlin.time.Duration.Companion.minutes

/**
 * Translates a grounded [AnalysisResult] into a syntactically and semantically valid SMTLIB2
 * formula. The formula is verified end-to-end by Z3 via the smtlibv2_syntax_checker tool.
 *
 * The set of canonical [SMTLIB2Variable] kinds is injected at construction — concrete variable
 * kinds live in the consuming application, so this function is domain-agnostic.
 */
class ConstraintGeneratorAgenticFunction internal constructor(
    agentConfiguration: AgentConfiguration,
) : AbstractAgenticFunction<ConstraintGeneratorAgenticFunction.Input, ConstraintGeneratorAgenticFunction.Output>(agentConfiguration) {
    @Serializable
    data class Input(
        @field:Description("Grounded analysis result")
        val analysisResult: AnalysisResult,
        @field:Description("Name of the dataset backing the analysis")
        val datasetName: String,
    ) : Instructible.WithInstruction {
        override fun instructionId() = "compass-constraint-generator"

        override fun systemInstruction(): String? = null

        override fun instruction(): String {
            val analysisJson =
                JsonSchemaUtil.json.encodeToString(AnalysisResult.serializer(), analysisResult)
            val analysisSchema =
                JsonSchemaUtil.json.encodeToString(
                    JsonObject.serializer(),
                    JsonSchemaUtil.generateJsonSchema(AnalysisResult::class),
                )
            return INSTRUCTION_TEMPLATE
                .replace("{{ANALYSIS_JSON}}", analysisJson)
                .replace("{{ANALYSIS_SCHEMA}}", analysisSchema)
                .replace("{{DATASET_NAME}}", datasetName)
        }

        companion object {
            private val INSTRUCTION_TEMPLATE =
                """
                You are an expert Mathematical Optimization Agent specializing in constraint
                programming and SMTLIB2 generation. Your job is to translate a grounded analysis
                into a syntactically AND semantically valid SMTLIB2 formula solvable by Z3.

                OBJECTIVE:
                - Generate an SMTLIB2 formula encoding the decision problem implied by the analysis.
                  All variables MUST reference real dataset entities; bounds and constants MUST come
                  from the analysis data.

                DATA SOURCE:
                - Dataset: {{DATASET_NAME}}
                - Use list_tables, get_tables, execute_sql to query the dataset for any value not
                  already in the analysis. Never invent numbers.

                BUSINESS ANALYSIS:
                - Schema: {{ANALYSIS_SCHEMA}}
                - Data: {{ANALYSIS_JSON}}

                STEPS:
                1. Parse the analysis. Extract entities and quantities relevant to the objective.
                2. Declare SMTLIB2 variables. The canonical variable kinds available to you appear
                   in the system prompt — each one is anchored to a dataset table; surface its
                   key columns in the variable name using ':::' separators.
                3. Wrap each variable name in '|' delimiters in the formula (e.g. '|V_KIND:::k1:::k2|').
                4. Add assertions encoding the constraints. Bounds MUST be backed by data.
                5. Call smtlibv2_syntax_checker on the formula. If it reports errors, FIX them and re-check.

                OUTPUT:
                - Return JSON conforming to the Output schema.
                - smtlibv2Formula MUST be syntactically valid (parseable by Z3) AND semantically valid
                  (no undefined functions, sensible bounds).
                - explanation MUST be Markdown describing the variables, assertions, and the SQL queries
                  used to derive bounds — so a human can verify the formula against the database.
                """.trimIndent()
        }
    }

    @Serializable
    data class Output(
        @field:Description("Generated SMTLIB2 formula encoding the constraint problem")
        val smtlibv2Formula: SMTLIBv2Formula,
        @field:Description("Markdown explanation: variables, assertions, and SQL used to derive each bound")
        val explanation: String,
    )

    override fun getInputKClass() = Input::class

    override fun getOutputKClass() = Output::class

    companion object {
        private const val TEMPERATURE = 0.2f
        private const val AGENT_ID = "compass-constraint-generator"

        suspend fun create(
            env: DatabaseEnvironment,
            variables: Set<SMTLIB2Variable>,
            problemDomain: String,
            llm: LLM = LLM.ANTHROPIC_CLAUDE_OPUS_4_6_V1_CROSS_REGION_INFERENCE,
        ): ConstraintGeneratorAgenticFunction {
            require(variables.isNotEmpty()) { "variables must not be empty" }
            DatabaseEnvironment.activate(env)

            val server = ConstraintGeneratorMcpServer(env)
            val exchange = server.pipedStreamsExchange()
            val mcpClient = Client(clientInfo = Implementation(name = "compass-constraint-generator-client", version = "1.0.0"))
            mcpClient.connect(exchange.stdioClientTransport())

            val variableDefinitions =
                variables.joinToString(separator = "\n                   - ") { it.serialize(env) }

            return ConstraintGeneratorAgenticFunction(
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
                    systemPrompt =
                        """
                        CANONICAL VARIABLE KINDS (each line is a JSON descriptor — kind + nameFormat):
                        - $variableDefinitions
                        """.trimIndent(),
                ),
            )
        }
    }
}
