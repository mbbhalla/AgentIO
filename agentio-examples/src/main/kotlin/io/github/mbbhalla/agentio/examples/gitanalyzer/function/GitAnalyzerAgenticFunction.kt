package io.github.mbbhalla.agentio.examples.gitanalyzer.function

import aws.sdk.kotlin.services.bedrockruntime.BedrockRuntimeClient
import io.github.mbbhalla.agentio.core.common.Description
import io.github.mbbhalla.agentio.core.lib.AbstractAgenticFunction
import io.github.mbbhalla.agentio.core.lib.Instructible
import io.github.mbbhalla.agentio.core.lib.ctx.cmm.ContextMemoryManagers
import io.github.mbbhalla.agentio.core.lib.ctx.cmm.NoOperationContextMemoryManager
import io.github.mbbhalla.agentio.core.lib.tool.McpClients
import io.github.mbbhalla.agentio.core.lib.tool.NamedClient
import io.github.mbbhalla.agentio.core.model.AgentConfiguration
import io.github.mbbhalla.agentio.core.model.LLM
import io.github.mbbhalla.agentio.core.model.LanguageModelParameters
import io.github.mbbhalla.agentio.core.model.Temperature
import io.github.mbbhalla.agentio.examples.gitanalyzer.server.GitAnalyzerMcpServer
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

internal class GitAnalyzerAgenticFunction(
    agentConfiguration: AgentConfiguration,
) : AbstractAgenticFunction<
    GitAnalyzerAgenticFunction.Input,
    GitAnalyzerAgenticFunction.Output,
    >(agentConfiguration) {

    @Serializable
    data class Input(
        @field:Description("Path to the git repository to analyze")
        val repoPath: String,

        @field:Description("What to analyze: activity, hotspots, or contributors")
        val analysisType: String = "activity",
    ) : Instructible.WithInstruction {
        override fun instructionId() = "git-analyzer-$analysisType"

        override fun instruction() = """
            Analyze the git repository at '$repoPath' focusing on '$analysisType'.
            Use the available tools to gather commit history, diff statistics, and author information.
            Produce a development activity summary with insights about the codebase health and team dynamics.
        """.trimIndent()

        override fun systemInstruction(): String? = null
    }

    @Serializable
    data class Output(
        @field:Description("Summary of repository development activity")
        val summary: String,

        @field:Description("Key insights about the codebase")
        val insights: List<String>,

        @field:Description("Most active areas of the codebase")
        val hotspots: List<String>,
    )

    override fun getInputKClass() = Input::class
    override fun getOutputKClass() = Output::class
}

internal object GitAnalyzerAgenticFunctionProvider {
    private const val TEMPERATURE = 0.4f

    suspend fun get(
        agentId: String,
        repoPath: String,
    ): GitAnalyzerAgenticFunction {
        val mcpServer = GitAnalyzerMcpServer(repoPath)
        val pipedStreamsExchange = mcpServer.pipedStreamsExchange()

        val mcpClient = Client(clientInfo = Implementation(name = "git_analyzer_client", version = "1.0.0"))
        mcpClient.connect(pipedStreamsExchange.stdioClientTransport())

        return GitAnalyzerAgenticFunction(
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
                            name = "gitops",
                            client = mcpClient,
                            deniedTools = emptySet(),
                        ),
                    ),
                ),
                systemPrompt = """
                    You are a software engineering analyst that examines git repositories
                    to produce insights about development patterns, code health, and team dynamics.
                    Now (in UTC) = '${
                    Instant.now().atZone(ZoneOffset.UTC).format(DateTimeFormatter.ISO_INSTANT)
                }'
                """.trimIndent(),
                contextMemoryManagers = ContextMemoryManagers(
                    value = listOf(NoOperationContextMemoryManager),
                ),
                delayBetweenTurns = 0.seconds,
                problemDomain = "Git Repository Analysis",
            ),
        )
    }
}
