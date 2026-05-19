package io.github.mbbhalla.agentio.examples.adversarial.function

import aws.sdk.kotlin.services.bedrockruntime.BedrockRuntimeClient
import io.github.mbbhalla.agentio.core.common.Description
import io.github.mbbhalla.agentio.core.lib.AbstractAgenticFunction
import io.github.mbbhalla.agentio.core.lib.Instructible
import io.github.mbbhalla.agentio.core.lib.ctx.cmm.ContextMemoryManagers
import io.github.mbbhalla.agentio.core.lib.ctx.cmm.NoOperationContextMemoryManager
import io.github.mbbhalla.agentio.core.lib.event.EventListener
import io.github.mbbhalla.agentio.core.lib.event.EventListeners
import io.github.mbbhalla.agentio.core.lib.tool.McpClients
import io.github.mbbhalla.agentio.core.lib.tool.NamedClient
import io.github.mbbhalla.agentio.core.model.AgentConfiguration
import io.github.mbbhalla.agentio.core.model.LLM
import io.github.mbbhalla.agentio.core.model.LanguageModelParameters
import io.github.mbbhalla.agentio.core.model.Temperature
import io.github.mbbhalla.agentio.core.model.event.Event
import io.github.mbbhalla.agentio.core.model.event.EventPayload
import io.github.mbbhalla.agentio.examples.adversarial.server.ApiDesignMcpServer
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

internal class DesignerAgenticFunction(
    agentConfiguration: AgentConfiguration,
) : AbstractAgenticFunction<
    DesignerAgenticFunction.Input,
    DesignerAgenticFunction.Output,
    >(agentConfiguration) {

    @Serializable
    data class Input(
        @field:Description("API requirements to design for")
        val requirements: String,

        @field:Description("Feedback from the critic agent on a prior design iteration, empty on first pass")
        val criticFeedback: String = "",

        @field:Description("Iteration number in the adversarial refinement loop")
        val iteration: Int = 1,
    ) : Instructible.WithInstruction {
        override fun instructionId() = "api-designer-iter-$iteration"

        override fun instruction(): String {
            val base = """
                Design a REST API schema for the following requirements:
                '$requirements'

                Use the available tools to parse the requirements, validate the schema you produce,
                and check for security patterns.

                Produce a complete API design with endpoints, request/response schemas, and error handling.
            """.trimIndent()

            return if (criticFeedback.isNotBlank()) {
                """
                $base

                IMPORTANT: A critic agent reviewed your previous design and provided this feedback:
                ---
                $criticFeedback
                ---
                Address ALL the critic's concerns in your revised design.
                """.trimIndent()
            } else {
                base
            }
        }

        override fun systemInstruction(): String? = null
    }

    @Serializable
    data class Output(
        @field:Description("List of API endpoints with method, path, and description")
        val endpoints: List<EndpointSpec>,

        @field:Description("Data model schemas used across the API")
        val dataModels: List<DataModelSpec>,

        @field:Description("Error handling strategy")
        val errorHandling: String,

        @field:Description("Authentication approach")
        val authentication: String,
    )

    @Serializable
    data class EndpointSpec(
        val method: String,
        val path: String,
        val description: String,
        val requestBody: String,
        val responseBody: String,
    )

    @Serializable
    data class DataModelSpec(
        val name: String,
        val fields: List<String>,
    )

    override fun getInputKClass() = Input::class
    override fun getOutputKClass() = Output::class
}

internal class CriticAgenticFunction(
    agentConfiguration: AgentConfiguration,
) : AbstractAgenticFunction<
    CriticAgenticFunction.Input,
    CriticAgenticFunction.Output,
    >(agentConfiguration) {

    @Serializable
    data class Input(
        @field:Description("Serialized API design output from the Designer agent to critique")
        val designJson: String,

        @field:Description("Original requirements the design must satisfy")
        val originalRequirements: String,

        @field:Description("Iteration number")
        val iteration: Int = 1,
    ) : Instructible.WithInstruction {
        override fun instructionId() = "api-critic-iter-$iteration"

        override fun instruction() = """
            You are an adversarial reviewer. Critically evaluate this API design against the original requirements.

            Original Requirements:
            '$originalRequirements'

            Proposed Design (JSON):
            $designJson

            Use the available tools to validate schema consistency and check for security anti-patterns.

            Be thorough and adversarial: find gaps, inconsistencies, missing error cases,
            security holes, and violations of REST best practices.
            Rate whether the design is acceptable or needs revision.
        """.trimIndent()

        override fun systemInstruction(): String? = null
    }

    @Serializable
    data class Output(
        @field:Description("Whether the design is acceptable: APPROVED or NEEDS_REVISION")
        val verdict: String,

        @field:Description("Critical issues that must be fixed")
        val criticalIssues: List<String>,

        @field:Description("Suggestions for improvement (non-blocking)")
        val suggestions: List<String>,

        @field:Description("Consolidated feedback message for the designer")
        val feedbackForDesigner: String,
    )

    override fun getInputKClass() = Input::class
    override fun getOutputKClass() = Output::class
}

internal class AdversarialEventListener : EventListener {
    private val log = LoggerFactory.getLogger(AdversarialEventListener::class.java)

    override suspend fun onEvent(event: Event): Result<Unit> = runCatching {
        when (val payload = event.payload) {
            is EventPayload.AgentInvocationStart -> {
                log.info("[ADVERSARIAL] Agent '{}' started: {}", payload.agentId, payload.instructionId)
            }
            is EventPayload.AgentInvocationEnd -> {
                log.info(
                    "[ADVERSARIAL] Agent '{}' finished | turns={} | tokens(in={}, out={}) | success={}",
                    payload.agentId,
                    payload.totalTurns,
                    payload.totalInputTokens,
                    payload.totalOutputTokens,
                    payload.success,
                )
            }
            is EventPayload.BeforeToolCall -> {
                log.info("[ADVERSARIAL] Tool '{}' called (turn {})", payload.toolName, payload.turnNumber)
            }
            is EventPayload.AfterToolCall -> {
                log.info("[ADVERSARIAL] Tool '{}' returned in {}ms", payload.toolName, payload.latency.inWholeMilliseconds)
            }
            is EventPayload.BeforeLlmCall -> {
                log.info("[ADVERSARIAL] LLM call (turn {}, messages={})", payload.turnNumber, payload.messageCount)
            }
            is EventPayload.AfterLlmCall -> {
                log.info(
                    "[ADVERSARIAL] LLM responded in {}ms | tokens(in={}, out={})",
                    payload.latency.inWholeMilliseconds,
                    payload.inputTokens,
                    payload.outputTokens,
                )
            }
            is EventPayload.TurnCompleted -> {
                log.info("[ADVERSARIAL] Turn {} completed", payload.turnNumber)
            }
        }
    }
}

internal object AdversarialAgenticFunctionProvider {
    private const val DESIGNER_TEMPERATURE = 0.5f
    private const val CRITIC_TEMPERATURE = 0.2f

    suspend fun getDesigner(agentId: String): DesignerAgenticFunction {
        val mcpServer = ApiDesignMcpServer()
        val pipedStreamsExchange = mcpServer.pipedStreamsExchange()

        val mcpClient = Client(clientInfo = Implementation(name = "designer_client", version = "1.0.0"))
        mcpClient.connect(pipedStreamsExchange.stdioClientTransport())

        return DesignerAgenticFunction(
            AgentConfiguration(
                agentId = agentId,
                languageModelParameters = LanguageModelParameters(
                    llm = LLM.ANTHROPIC_CLAUDE_OPUS_4_5_V1_CROSS_REGION_INFERENCE,
                    temperature = Temperature(DESIGNER_TEMPERATURE),
                ),
                bedrockRuntimeClient = BedrockRuntimeClient {
                    this.region = "us-west-2"
                    this.httpClient { socketReadTimeout = 15.minutes }
                },
                toolsProvider = McpClients(
                    set = setOf(
                        NamedClient(name = "design", client = mcpClient, deniedTools = emptySet()),
                    ),
                ),
                systemPrompt = """
                    You are an expert API architect. Design clean, RESTful APIs that follow
                    industry best practices for naming, versioning, error handling, and security.
                    Now (UTC) = '${Instant.now().atZone(ZoneOffset.UTC).format(DateTimeFormatter.ISO_INSTANT)}'
                """.trimIndent(),
                contextMemoryManagers = ContextMemoryManagers(listOf(NoOperationContextMemoryManager)),
                delayBetweenTurns = 0.seconds,
                problemDomain = "API Design",
                eventListeners = EventListeners(setOf(AdversarialEventListener())),
            ),
        )
    }

    suspend fun getCritic(agentId: String): CriticAgenticFunction {
        val mcpServer = ApiDesignMcpServer()
        val pipedStreamsExchange = mcpServer.pipedStreamsExchange()

        val mcpClient = Client(clientInfo = Implementation(name = "critic_client", version = "1.0.0"))
        mcpClient.connect(pipedStreamsExchange.stdioClientTransport())

        return CriticAgenticFunction(
            AgentConfiguration(
                agentId = agentId,
                languageModelParameters = LanguageModelParameters(
                    llm = LLM.ANTHROPIC_CLAUDE_OPUS_4_5_V1_CROSS_REGION_INFERENCE,
                    temperature = Temperature(CRITIC_TEMPERATURE),
                ),
                bedrockRuntimeClient = BedrockRuntimeClient {
                    this.region = "us-west-2"
                    this.httpClient { socketReadTimeout = 15.minutes }
                },
                toolsProvider = McpClients(
                    set = setOf(
                        NamedClient(name = "critic", client = mcpClient, deniedTools = emptySet()),
                    ),
                ),
                systemPrompt = """
                    You are an adversarial API security and design reviewer. Your job is to find flaws.
                    Be thorough, critical, and specific. Only approve designs that meet high standards.
                    Now (UTC) = '${Instant.now().atZone(ZoneOffset.UTC).format(DateTimeFormatter.ISO_INSTANT)}'
                """.trimIndent(),
                contextMemoryManagers = ContextMemoryManagers(listOf(NoOperationContextMemoryManager)),
                delayBetweenTurns = 0.seconds,
                problemDomain = "API Security Review",
                eventListeners = EventListeners(setOf(AdversarialEventListener())),
            ),
        )
    }
}
