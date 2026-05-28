package io.github.mbbhalla.agentio.examples.fetch.function

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
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.StdioClientTransport
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered
import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

internal class FetchAgenticFunction(
    agentConfiguration: AgentConfiguration,
) : AbstractAgenticFunction<
        FetchAgenticFunction.Input,
        FetchAgenticFunction.Output,
    >(agentConfiguration) {
    @Serializable
    data class Input(
        @field:Description("URL of a technical blog post or documentation page to analyze")
        val url: String,
        @field:Description("Specific aspect to focus on: architecture, performance, security, or general")
        val focus: String = "general",
    ) : Instructible.WithInstruction {
        override fun instructionId() = "fetch-analyze-${url.hashCode()}"

        override fun instruction() =
            """
            Fetch the content at '$url' and produce a developer-focused technical summary.
            Focus area: '$focus'.
            Extract key technical decisions, patterns used, and actionable takeaways.
            """.trimIndent()

        override fun systemInstruction(): String? = null
    }

    @Serializable
    data class Output(
        @field:Description("Title of the article or page")
        val title: String,
        @field:Description("Key technical points extracted from the content")
        val keyPoints: List<String>,
        @field:Description("Actionable takeaways for developers")
        val takeaways: List<String>,
        @field:Description("Technologies and patterns mentioned")
        val technologies: List<String>,
    )

    override fun getInputKClass() = Input::class

    override fun getOutputKClass() = Output::class
}

internal object FetchAgenticFunctionProvider {
    private const val TEMPERATURE = 0.3f

    suspend fun get(
        agentId: String,
        mcpServerProcess: String,
    ): FetchAgenticFunction {
        val mcpClient = Client(clientInfo = Implementation(name = "fetch_mcp_client", version = "1.0.0"))
        val process =
            withContext(Dispatchers.IO) {
                ProcessBuilder(mcpServerProcess).start()
            }
        val transport =
            StdioClientTransport(
                input = process.inputStream.asSource().buffered(),
                output = process.outputStream.asSink().buffered(),
            )
        mcpClient.connect(transport)

        return FetchAgenticFunction(
            AgentConfiguration(
                agentId = agentId,
                languageModelParameters =
                    LanguageModelParameters(
                        llm = LLM.ANTHROPIC_CLAUDE_OPUS_4_5_V1_CROSS_REGION_INFERENCE,
                        temperature = Temperature(TEMPERATURE),
                    ),
                bedrockRuntimeClient =
                    BedrockRuntimeClient {
                        this.region = "us-west-2"
                        this.httpClient {
                            socketReadTimeout = 15.minutes
                        }
                    },
                toolsProvider =
                    McpClients(
                        set =
                            setOf(
                                NamedClient(
                                    name = "fetch",
                                    client = mcpClient,
                                    deniedTools = emptySet(),
                                ),
                            ),
                    ),
                systemPrompt =
                    """
                    You are a technical content analyst specializing in software engineering articles.
                    Extract structured insights from web content for developer audiences.
                    Now (in UTC) = '${
                        Instant.now().atZone(ZoneOffset.UTC).format(DateTimeFormatter.ISO_INSTANT)
                    }'
                    """.trimIndent(),
                contextMemoryManagers =
                    ContextMemoryManagers(
                        value = listOf(NoOperationContextMemoryManager),
                    ),
                delayBetweenTurns = 0.seconds,
                problemDomain = "Technical Content Analysis",
            ),
        )
    }
}
