package io.github.mbbhalla.agentio.examples.hackernews.function

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
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.StdioClientTransport
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

internal class HackerNewsAgenticFunction(
    agentConfiguration: AgentConfiguration,
) : AbstractAgenticFunction<
    HackerNewsAgenticFunction.HackerNewsAgenticFunctionInput,
    HackerNewsAgenticFunction.HackerNewsAgenticFunctionOutput,
    >(
    agentConfiguration,
) {
    @Serializable
    data class HackerNewsAgenticFunctionInput(
        @field:Description("News topic")
        val value: String,
    ) : Instructible.WithInstruction {
        override fun instructionId() = "ExampleId"

        override fun instruction() = """
            Find the latest news on this topic: '$value'
        """.trimIndent()

        override fun systemInstruction(): String? = null
    }

    @Serializable
    data class HackerNewsAgenticFunctionOutput(
        @field:Description("News summaries")
        val summaries: Set<String>,
    )

    override fun getInputKClass() = HackerNewsAgenticFunctionInput::class
    override fun getOutputKClass() = HackerNewsAgenticFunctionOutput::class
}

/*
    Object to get an instance of this function
    used in various places
 */
internal object HackerNewsAgenticFunctionProvider {
    private const val TEMPERATURE = 0.5f

    suspend fun get(
        agentId: String,
        mcpServerProcess: String,
    ): HackerNewsAgenticFunction {
        val mcpClient = Client(clientInfo = Implementation(name = "mcp_client", version = "1.0.0"))
        val process = withContext(Dispatchers.IO) {
            ProcessBuilder(mcpServerProcess).start()
        }
        val transport = StdioClientTransport(
            input = process.inputStream.asSource().buffered(),
            output = process.outputStream.asSink().buffered(),
        )
        mcpClient.connect(transport)

        return HackerNewsAgenticFunction(
            AgentConfiguration(
                agentId = agentId,
                languageModelParameters = LanguageModelParameters(
                    llm = LLM.ANTHROPIC_CLAUDE_OPUS_4_5_V1_CROSS_REGION_INFERENCE,
                    temperature = Temperature(TEMPERATURE),
                ),
                bedrockRuntimeClient = BedrockRuntimeClient {
                    this.region = "us-west-2"
                    this.httpClient {
                        // Converse API takes more than default timeout of 60s
                        socketReadTimeout = 15.minutes
                    }
                },
                toolsProvider = McpClients(
                    set = setOf(
                        NamedClient(
                            name = "mcp1",
                            client = mcpClient,
                            deniedTools = emptySet(),
                        ),
                    ),
                ),
                systemPrompt = """
                    Now (in UTC) = '${
                    Instant.now().atZone(ZoneOffset.UTC).format(DateTimeFormatter.ISO_INSTANT)
                }'
                """.trimIndent(),
                contextMemoryManagers = ContextMemoryManagers(
                    value = listOf(NoOperationContextMemoryManager),
                ),
                delayBetweenTurns = 0.seconds,
                problemDomain = "Hacker News",
            ),
        )
    }
}
