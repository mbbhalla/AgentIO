package io.github.mbbhalla.agentio.examples.orchestration.function

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
import io.github.mbbhalla.agentio.examples.orchestration.server.DocumentationWorkerMcpServer
import io.github.mbbhalla.agentio.examples.orchestration.server.QualityWorkerMcpServer
import io.github.mbbhalla.agentio.examples.orchestration.server.SecurityWorkerMcpServer
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

// --- Worker: Security Analysis ---

internal class SecurityWorkerAgenticFunction(
    agentConfiguration: AgentConfiguration,
) : AbstractAgenticFunction<
        SecurityWorkerAgenticFunction.Input,
        SecurityWorkerAgenticFunction.Output,
    >(agentConfiguration) {
    @Serializable
    data class Input(
        @field:Description("Project path to scan for security concerns")
        val projectPath: String,
    ) : Instructible.WithInstruction {
        override fun instructionId() = "security-worker"

        override fun instruction() =
            """
            Scan the project at '$projectPath' for security concerns.
            Use the dependency scanning tool to identify all dependencies.
            Report on any dependencies that may have known vulnerability patterns
            (outdated versions, deprecated libraries) and assess the overall
            security posture of the dependency tree.
            """.trimIndent()

        override fun systemInstruction(): String? = null
    }

    @Serializable
    data class Output(
        @field:Description("Overall security risk assessment: LOW, MEDIUM, HIGH")
        val riskLevel: String,
        @field:Description("Security findings and concerns")
        val findings: List<String>,
        @field:Description("Recommended actions to improve security")
        val recommendations: List<String>,
    )

    override fun getInputKClass() = Input::class

    override fun getOutputKClass() = Output::class
}

// --- Worker: Code Quality Analysis ---

internal class QualityWorkerAgenticFunction(
    agentConfiguration: AgentConfiguration,
) : AbstractAgenticFunction<
        QualityWorkerAgenticFunction.Input,
        QualityWorkerAgenticFunction.Output,
    >(agentConfiguration) {
    @Serializable
    data class Input(
        @field:Description("Project path to analyze for code quality")
        val projectPath: String,
        @field:Description("Programming language extension to analyze")
        val language: String = "kt",
    ) : Instructible.WithInstruction {
        override fun instructionId() = "quality-worker-$language"

        override fun instruction() =
            """
            Analyze the project at '$projectPath' for code quality.
            Use the test coverage tool to assess testing practices and the
            complexity measurement tool to find overly complex code.
            Report on testing gaps, complexity hotspots, and overall code health.
            """.trimIndent()

        override fun systemInstruction(): String? = null
    }

    @Serializable
    data class Output(
        @field:Description("Overall quality grade: A, B, C, D, F")
        val grade: String,
        @field:Description("Test coverage assessment")
        val testCoverage: String,
        @field:Description("Complexity assessment")
        val complexityAssessment: String,
        @field:Description("Files that need attention")
        val hotspots: List<String>,
    )

    override fun getInputKClass() = Input::class

    override fun getOutputKClass() = Output::class
}

// --- Worker: Documentation Analysis ---

internal class DocumentationWorkerAgenticFunction(
    agentConfiguration: AgentConfiguration,
) : AbstractAgenticFunction<
        DocumentationWorkerAgenticFunction.Input,
        DocumentationWorkerAgenticFunction.Output,
    >(agentConfiguration) {
    @Serializable
    data class Input(
        @field:Description("Project path to scan for documentation")
        val projectPath: String,
        @field:Description("Source language extension")
        val language: String = "kt",
    ) : Instructible.WithInstruction {
        override fun instructionId() = "documentation-worker-$language"

        override fun instruction() =
            """
            Analyze the project at '$projectPath' for documentation quality.
            Use the documentation scanning tool to assess README coverage,
            inline documentation, and KDoc/Javadoc usage.
            Report on documentation gaps and provide recommendations.
            """.trimIndent()

        override fun systemInstruction(): String? = null
    }

    @Serializable
    data class Output(
        @field:Description("Overall documentation grade: A, B, C, D, F")
        val grade: String,
        @field:Description("Documentation coverage summary")
        val summary: String,
        @field:Description("Files or areas lacking documentation")
        val gaps: List<String>,
        @field:Description("Recommendations for improving documentation")
        val recommendations: List<String>,
    )

    override fun getInputKClass() = Input::class

    override fun getOutputKClass() = Output::class
}

// --- Orchestrator: synthesizes worker results ---

internal class OrchestratorAgenticFunction(
    agentConfiguration: AgentConfiguration,
) : AbstractAgenticFunction<
        OrchestratorAgenticFunction.Input,
        OrchestratorAgenticFunction.Output,
    >(agentConfiguration) {
    @Serializable
    data class Input(
        @field:Description("Security worker output JSON")
        val securityReport: String,
        @field:Description("Quality worker output JSON")
        val qualityReport: String,
        @field:Description("Documentation worker output JSON")
        val documentationReport: String,
        @field:Description("Project name for the report")
        val projectName: String,
    ) : Instructible.WithInstruction {
        override fun instructionId() = "orchestrator-synthesis"

        override fun instruction() =
            """
            You are the orchestrator synthesizing reports from three specialized worker agents
            that analyzed the project '$projectName'.

            Security Worker Report:
            $securityReport

            Quality Worker Report:
            $qualityReport

            Documentation Worker Report:
            $documentationReport

            Synthesize these into a unified project health report. Identify cross-cutting
            concerns (e.g., untested complex code is higher risk), prioritize findings,
            and produce an executive summary with the top 5 action items.
            """.trimIndent()

        override fun systemInstruction(): String? = null
    }

    @Serializable
    data class Output(
        @field:Description("Executive summary of project health")
        val executiveSummary: String,
        @field:Description("Overall project health score from 1-10")
        val healthScore: Int,
        @field:Description("Top prioritized action items")
        val actionItems: List<ActionItem>,
        @field:Description("Cross-cutting risks identified from correlating worker reports")
        val crossCuttingRisks: List<String>,
    )

    @Serializable
    data class ActionItem(
        val priority: Int,
        val area: String,
        val action: String,
    )

    override fun getInputKClass() = Input::class

    override fun getOutputKClass() = Output::class
}

// --- Event Listener ---

internal class OrchestrationEventListener : EventListener {
    private val log = LoggerFactory.getLogger(OrchestrationEventListener::class.java)

    override suspend fun onEvent(event: Event): Result<Unit> =
        runCatching {
            when (val payload = event.payload) {
                is EventPayload.AgentInvocationStart -> {
                    log.info("[ORCH] Agent '{}' started: {}", payload.agentId, payload.instructionId)
                }
                is EventPayload.AgentInvocationEnd -> {
                    log.info(
                        "[ORCH] Agent '{}' finished | turns={} | tokens(in={}, out={}) | success={}",
                        payload.agentId,
                        payload.totalTurns,
                        payload.totalInputTokens,
                        payload.totalOutputTokens,
                        payload.success,
                    )
                }
                is EventPayload.BeforeToolCall -> {
                    log.info("[ORCH] Tool '{}' (turn {})", payload.toolName, payload.turnNumber)
                }
                is EventPayload.AfterToolCall -> {
                    log.info("[ORCH] Tool '{}' done in {}ms", payload.toolName, payload.latency.inWholeMilliseconds)
                }
                is EventPayload.BeforeLlmCall -> {
                    log.info("[ORCH] LLM call (turn {})", payload.turnNumber)
                }
                is EventPayload.AfterLlmCall -> {
                    log.info("[ORCH] LLM responded in {}ms", payload.latency.inWholeMilliseconds)
                }
                is EventPayload.TurnCompleted -> {
                    log.info("[ORCH] Turn {} done", payload.turnNumber)
                }
            }
        }
}

// --- Provider ---

internal object OrchestrationAgenticFunctionProvider {
    private const val WORKER_TEMPERATURE = 0.2f
    private const val ORCHESTRATOR_TEMPERATURE = 0.4f

    private fun eventListeners() = EventListeners(setOf(OrchestrationEventListener()))

    suspend fun getSecurityWorker(
        agentId: String,
        projectPath: String,
    ): SecurityWorkerAgenticFunction {
        val mcpServer = SecurityWorkerMcpServer(projectPath)
        val exchange = mcpServer.pipedStreamsExchange()
        val client = Client(clientInfo = Implementation(name = "security_client", version = "1.0.0"))
        client.connect(exchange.stdioClientTransport())

        return SecurityWorkerAgenticFunction(
            AgentConfiguration(
                agentId = agentId,
                languageModelParameters =
                    LanguageModelParameters(
                        llm = LLM.ANTHROPIC_CLAUDE_OPUS_4_5_V1_CROSS_REGION_INFERENCE,
                        temperature = Temperature(WORKER_TEMPERATURE),
                    ),
                bedrockRuntimeClient =
                    BedrockRuntimeClient {
                        this.region = "us-west-2"
                        this.httpClient { socketReadTimeout = 15.minutes }
                    },
                toolsProvider =
                    McpClients(
                        set = setOf(NamedClient(name = "secwrkr", client = client, deniedTools = emptySet())),
                    ),
                systemPrompt =
                    """
                    You are a security analyst worker agent. Scan dependencies and report security risks.
                    Be specific and factual. Do not orchestrate other agents.
                    Now (UTC) = '${Instant.now().atZone(ZoneOffset.UTC).format(DateTimeFormatter.ISO_INSTANT)}'
                    """.trimIndent(),
                contextMemoryManagers = ContextMemoryManagers(listOf(NoOperationContextMemoryManager)),
                delayBetweenTurns = 0.seconds,
                problemDomain = "Security Analysis",
                eventListeners = eventListeners(),
            ),
        )
    }

    suspend fun getQualityWorker(
        agentId: String,
        projectPath: String,
    ): QualityWorkerAgenticFunction {
        val mcpServer = QualityWorkerMcpServer(projectPath)
        val exchange = mcpServer.pipedStreamsExchange()
        val client = Client(clientInfo = Implementation(name = "quality_client", version = "1.0.0"))
        client.connect(exchange.stdioClientTransport())

        return QualityWorkerAgenticFunction(
            AgentConfiguration(
                agentId = agentId,
                languageModelParameters =
                    LanguageModelParameters(
                        llm = LLM.ANTHROPIC_CLAUDE_OPUS_4_5_V1_CROSS_REGION_INFERENCE,
                        temperature = Temperature(WORKER_TEMPERATURE),
                    ),
                bedrockRuntimeClient =
                    BedrockRuntimeClient {
                        this.region = "us-west-2"
                        this.httpClient { socketReadTimeout = 15.minutes }
                    },
                toolsProvider =
                    McpClients(
                        set = setOf(NamedClient(name = "qualwkr", client = client, deniedTools = emptySet())),
                    ),
                systemPrompt =
                    """
                    You are a code quality analyst worker agent. Assess test coverage and complexity.
                    Be specific and factual. Do not orchestrate other agents.
                    Now (UTC) = '${Instant.now().atZone(ZoneOffset.UTC).format(DateTimeFormatter.ISO_INSTANT)}'
                    """.trimIndent(),
                contextMemoryManagers = ContextMemoryManagers(listOf(NoOperationContextMemoryManager)),
                delayBetweenTurns = 0.seconds,
                problemDomain = "Code Quality Analysis",
                eventListeners = eventListeners(),
            ),
        )
    }

    suspend fun getDocumentationWorker(
        agentId: String,
        projectPath: String,
    ): DocumentationWorkerAgenticFunction {
        val mcpServer = DocumentationWorkerMcpServer(projectPath)
        val exchange = mcpServer.pipedStreamsExchange()
        val client = Client(clientInfo = Implementation(name = "docs_client", version = "1.0.0"))
        client.connect(exchange.stdioClientTransport())

        return DocumentationWorkerAgenticFunction(
            AgentConfiguration(
                agentId = agentId,
                languageModelParameters =
                    LanguageModelParameters(
                        llm = LLM.ANTHROPIC_CLAUDE_OPUS_4_5_V1_CROSS_REGION_INFERENCE,
                        temperature = Temperature(WORKER_TEMPERATURE),
                    ),
                bedrockRuntimeClient =
                    BedrockRuntimeClient {
                        this.region = "us-west-2"
                        this.httpClient { socketReadTimeout = 15.minutes }
                    },
                toolsProvider =
                    McpClients(
                        set = setOf(NamedClient(name = "docwrkr", client = client, deniedTools = emptySet())),
                    ),
                systemPrompt =
                    """
                    You are a documentation analyst worker agent. Assess documentation coverage.
                    Be specific and factual. Do not orchestrate other agents.
                    Now (UTC) = '${Instant.now().atZone(ZoneOffset.UTC).format(DateTimeFormatter.ISO_INSTANT)}'
                    """.trimIndent(),
                contextMemoryManagers = ContextMemoryManagers(listOf(NoOperationContextMemoryManager)),
                delayBetweenTurns = 0.seconds,
                problemDomain = "Documentation Analysis",
                eventListeners = eventListeners(),
            ),
        )
    }

    fun getOrchestrator(agentId: String): OrchestratorAgenticFunction =
        OrchestratorAgenticFunction(
            AgentConfiguration(
                agentId = agentId,
                languageModelParameters =
                    LanguageModelParameters(
                        llm = LLM.ANTHROPIC_CLAUDE_OPUS_4_5_V1_CROSS_REGION_INFERENCE,
                        temperature = Temperature(ORCHESTRATOR_TEMPERATURE),
                    ),
                bedrockRuntimeClient =
                    BedrockRuntimeClient {
                        this.region = "us-west-2"
                        this.httpClient { socketReadTimeout = 15.minutes }
                    },
                toolsProvider = McpClients(set = emptySet()),
                systemPrompt =
                    """
                    You are a project health orchestrator. You synthesize reports from specialized
                    worker agents into a unified executive summary. Identify cross-cutting concerns,
                    prioritize findings, and produce actionable recommendations.
                    Now (UTC) = '${Instant.now().atZone(ZoneOffset.UTC).format(DateTimeFormatter.ISO_INSTANT)}'
                    """.trimIndent(),
                contextMemoryManagers = ContextMemoryManagers(listOf(NoOperationContextMemoryManager)),
                delayBetweenTurns = 0.seconds,
                problemDomain = "Project Health Orchestration",
                eventListeners = eventListeners(),
            ),
        )
}
