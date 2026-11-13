package io.github.mbbhalla.agentio.module.patterns.common

import aws.sdk.kotlin.services.bedrockruntime.BedrockRuntimeClient
import io.github.mbbhalla.agentio.core.lib.event.EventListener
import io.github.mbbhalla.agentio.core.lib.event.EventListeners
import io.github.mbbhalla.agentio.core.lib.server.AbstractMcpServer
import io.github.mbbhalla.agentio.core.lib.tool.EmptyToolsProvider
import io.github.mbbhalla.agentio.core.lib.tool.McpClients
import io.github.mbbhalla.agentio.core.lib.tool.NamedClient
import io.github.mbbhalla.agentio.core.lib.tool.ToolsProvider
import io.github.mbbhalla.agentio.core.model.AgentConfiguration
import io.github.mbbhalla.agentio.core.model.LLM
import io.github.mbbhalla.agentio.core.model.LanguageModelParameters
import io.github.mbbhalla.agentio.core.model.Temperature
import io.github.mbbhalla.agentio.core.model.ThinkingMode
import io.github.mbbhalla.agentio.core.model.event.Event
import io.github.mbbhalla.agentio.core.model.event.EventPayload
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import org.slf4j.LoggerFactory
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.time.Duration.Companion.minutes

/**
 * Shared wiring for the pattern runners: one Bedrock client factory, one way to build an
 * [AgentConfiguration], one way to attach an in-process MCP server. Keeps each pattern package
 * focused on the coordination logic it demonstrates rather than on Bedrock plumbing.
 */
internal object PatternAgents {
    /** Bedrock region used by every pattern runner. */
    const val REGION = "us-west-2"

    /** Default model for pattern agents; cascades override this per tier. */
    val DEFAULT_LLM = LLM.ANTHROPIC_CLAUDE_OPUS_4_5_V1_CROSS_REGION_INFERENCE

    /** Low temperature keeps structured outputs stable across runs. */
    const val DEFAULT_TEMPERATURE = 0.2f

    /** Agent turns can involve several tool calls; allow long reads. */
    private val SOCKET_READ_TIMEOUT = 15.minutes

    fun bedrockRuntimeClient(): BedrockRuntimeClient =
        BedrockRuntimeClient {
            this.region = REGION
            this.httpClient { socketReadTimeout = SOCKET_READ_TIMEOUT }
        }

    fun nowUtc(): String = Instant.now().atZone(ZoneOffset.UTC).format(DateTimeFormatter.ISO_INSTANT)

    /**
     * Builds the language-model parameters honouring whether the model accepts sampling
     * parameters: models that reject temperature get none, every other model gets [temperature].
     */
    fun languageModelParameters(
        llm: LLM = DEFAULT_LLM,
        temperature: Float = DEFAULT_TEMPERATURE,
        maxOutputTokens: Int = llm.maxOutputTokens,
    ): LanguageModelParameters =
        LanguageModelParameters(
            llm = llm,
            temperature = if (llm.supportsSamplingParameters) Temperature(temperature) else null,
            maxOutputTokens = maxOutputTokens,
        )

    fun configuration(
        agentId: String,
        problemDomain: String,
        systemPrompt: String,
        toolsProvider: ToolsProvider = EmptyToolsProvider,
        languageModelParameters: LanguageModelParameters = languageModelParameters(),
        thinkingMode: ThinkingMode = ThinkingMode(maxIterations = 0),
        eventListenerTag: String = problemDomain,
    ): AgentConfiguration =
        AgentConfiguration(
            agentId = agentId,
            problemDomain = problemDomain,
            languageModelParameters = languageModelParameters,
            bedrockRuntimeClient = bedrockRuntimeClient(),
            toolsProvider = toolsProvider,
            systemPrompt =
                """
                $systemPrompt
                Now (UTC) = '${nowUtc()}'
                """.trimIndent(),
            thinkingMode = thinkingMode,
            eventListeners = EventListeners(setOf(LoggingEventListener(eventListenerTag))),
        )

    /** Starts [server] in-process and returns a [ToolsProvider] connected to it over piped streams. */
    suspend fun inProcessTools(
        clientName: String,
        server: AbstractMcpServer,
    ): McpClients {
        val exchange = server.pipedStreamsExchange()
        val client = Client(clientInfo = Implementation(name = "${clientName}_client", version = "1.0.0"))
        client.connect(exchange.stdioClientTransport())
        return McpClients(setOf(NamedClient(name = clientName, client = client, deniedTools = emptySet())))
    }
}

/** Logs every agent lifecycle event under a pattern-specific tag. */
internal class LoggingEventListener(
    private val tag: String,
) : EventListener {
    private val log = LoggerFactory.getLogger(LoggingEventListener::class.java)

    override suspend fun onEvent(event: Event): Result<Unit> =
        runCatching {
            when (val payload = event.payload) {
                is EventPayload.AgentInvocationStart ->
                    log.info("[{}] Agent '{}' started: {}", tag, payload.agentId, payload.instructionId)
                is EventPayload.AgentInvocationEnd ->
                    log.info(
                        "[{}] Agent '{}' finished | turns={} | tokens(in={}, out={}) | success={} | {}ms",
                        tag,
                        payload.agentId,
                        payload.totalTurns,
                        payload.totalInputTokens,
                        payload.totalOutputTokens,
                        payload.success,
                        payload.latency.inWholeMilliseconds,
                    )
                is EventPayload.BeforeToolCall ->
                    log.info("[{}] Tool '{}' called (turn {})", tag, payload.toolName, payload.turnNumber)
                is EventPayload.AfterToolCall ->
                    log.info("[{}] Tool '{}' returned in {}ms", tag, payload.toolName, payload.latency.inWholeMilliseconds)
                is EventPayload.BeforeLlmCall ->
                    log.debug("[{}] LLM call (turn {}, messages={})", tag, payload.turnNumber, payload.messageCount)
                is EventPayload.AfterLlmCall ->
                    log.info(
                        "[{}] LLM '{}' responded in {}ms | tokens(in={}, out={})",
                        tag,
                        payload.modelId,
                        payload.latency.inWholeMilliseconds,
                        payload.inputTokens,
                        payload.outputTokens,
                    )
                is EventPayload.TurnCompleted ->
                    log.debug("[{}] Turn {} completed", tag, payload.turnNumber)
            }
        }
}
