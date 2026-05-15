package io.github.mbbhalla.agentio.examples.codemetrics.function

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
import io.github.mbbhalla.agentio.eventlistener.impl.checkpoint.CheckpointTrigger
import io.github.mbbhalla.agentio.eventlistener.impl.checkpoint.CheckpointingEventListener
import io.github.mbbhalla.agentio.eventlistener.impl.checkpoint.FileSystemCheckpointWriter
import io.github.mbbhalla.agentio.examples.codemetrics.server.CodeMetricsMcpServer
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import java.nio.file.Path
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

internal class CodeMetricsAgenticFunction(
    agentConfiguration: AgentConfiguration,
) : AbstractAgenticFunction<
    CodeMetricsAgenticFunction.Input,
    CodeMetricsAgenticFunction.Output,
    >(agentConfiguration) {

    @Serializable
    data class Input(
        @field:Description("Root path of the project to analyze")
        val projectPath: String,

        @field:Description("Programming language file extension to analyze: kt, java, ts, py")
        val language: String = "kt",
    ) : Instructible.WithInstruction {
        override fun instructionId() = "code-metrics-$language"

        override fun instruction() = """
            Analyze the codebase at '$projectPath' for '$language' files.
            Use the available tools to list source files, check complexity metrics, and map dependencies.
            Produce a codebase health report identifying the most complex files,
            the overall dependency structure, and recommendations for improvement.
        """.trimIndent()

        override fun systemInstruction(): String? = null
    }

    @Serializable
    data class Output(
        @field:Description("Overall health assessment of the codebase")
        val healthSummary: String,

        @field:Description("Most complex files that may need refactoring")
        val complexFiles: List<String>,

        @field:Description("Dependency structure observations")
        val dependencyInsights: List<String>,

        @field:Description("Actionable recommendations for improvement")
        val recommendations: List<String>,
    )

    override fun getInputKClass() = Input::class
    override fun getOutputKClass() = Output::class
}

internal class MetricsEventListener : EventListener {
    private val log = LoggerFactory.getLogger(MetricsEventListener::class.java)

    override suspend fun onEvent(event: Event): Result<Unit> = runCatching {
        when (val payload = event.payload) {
            is EventPayload.AgentInvocationStart -> {
                log.info("[EVENT] Agent '{}' started: {}", payload.agentId, payload.instructionId)
            }
            is EventPayload.AgentInvocationEnd -> {
                log.info(
                    "[EVENT] Agent '{}' finished in {}ms | turns={} | tokens(in={}, out={}) | success={}",
                    payload.agentId,
                    payload.latency.inWholeMilliseconds,
                    payload.totalTurns,
                    payload.totalInputTokens,
                    payload.totalOutputTokens,
                    payload.success,
                )
            }
            is EventPayload.BeforeToolCall -> {
                log.info("[EVENT] Calling tool '{}' (turn {})", payload.toolName, payload.turnNumber)
            }
            is EventPayload.AfterToolCall -> {
                val errorMsg = payload.error?.message
                log.info(
                    "[EVENT] Tool '{}' returned in {}ms{}",
                    payload.toolName,
                    payload.latency.inWholeMilliseconds,
                    if (errorMsg != null) " [ERROR: $errorMsg]" else "",
                )
            }
            is EventPayload.BeforeLlmCall -> {
                log.info("[EVENT] LLM call (turn {}, messages={})", payload.turnNumber, payload.messageCount)
            }
            is EventPayload.AfterLlmCall -> {
                log.info(
                    "[EVENT] LLM responded in {}ms | tokens(in={}, out={}) | stop={}",
                    payload.latency.inWholeMilliseconds,
                    payload.inputTokens,
                    payload.outputTokens,
                    payload.stopReason,
                )
            }
            is EventPayload.TurnCompleted -> {
                log.info("[EVENT] Turn {} completed", payload.turnNumber)
            }
        }
    }
}

internal object CodeMetricsAgenticFunctionProvider {
    private const val TEMPERATURE = 0.3f

    suspend fun get(
        agentId: String,
        projectPath: String,
        checkpointDir: Path,
    ): CodeMetricsAgenticFunction {
        val mcpServer = CodeMetricsMcpServer(projectPath)
        val pipedStreamsExchange = mcpServer.pipedStreamsExchange()

        val mcpClient = Client(clientInfo = Implementation(name = "code_metrics_client", version = "1.0.0"))
        mcpClient.connect(pipedStreamsExchange.stdioClientTransport())

        return CodeMetricsAgenticFunction(
            AgentConfiguration(
                agentId = agentId,
                languageModelParameters = LanguageModelParameters(
                    llm = LLM.ANTHROPIC_CLAUDE_OPUS_4_5_V1_CROSS_REGION_INFERENCE,
                    temperature = Temperature(TEMPERATURE),
                ),
                bedrockRuntimeClient = BedrockRuntimeClient {
                    this.region = "us-west-2"
                    this.httpClient {
                        socketReadTimeout = 15.minutes
                    }
                },
                toolsProvider = McpClients(
                    set = setOf(
                        NamedClient(
                            name = "metrics",
                            client = mcpClient,
                            deniedTools = emptySet(),
                        ),
                    ),
                ),
                systemPrompt = """
                    You are a code quality analyst that examines source code metrics to assess
                    codebase health. Identify complexity hotspots, coupling issues, and provide
                    actionable refactoring recommendations.
                    Now (in UTC) = '${
                    Instant.now().atZone(ZoneOffset.UTC).format(DateTimeFormatter.ISO_INSTANT)
                }'
                """.trimIndent(),
                contextMemoryManagers = ContextMemoryManagers(
                    value = listOf(NoOperationContextMemoryManager),
                ),
                delayBetweenTurns = 0.seconds,
                problemDomain = "Code Quality Analysis",
                eventListeners = EventListeners(
                    setOf(
                        MetricsEventListener(),
                        CheckpointingEventListener(
                            trigger = CheckpointTrigger.EveryNTurns(n = 1),
                            writer = FileSystemCheckpointWriter(directory = checkpointDir),
                        ),
                    ),
                ),
            ),
        )
    }
}
