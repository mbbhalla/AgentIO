package io.github.mbbhalla.agentio.module.text2sql

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
import io.github.mbbhalla.agentio.data.env.DatabaseEnvironment
import io.github.mbbhalla.agentio.data.env.SelectSqlStatement
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class Text2SqlAgenticFunction internal constructor(
    agentConfiguration: AgentConfiguration,
) : AbstractAgenticFunction<
        Text2SqlAgenticFunction.Input,
        Text2SqlAgenticFunction.Output,
    >(agentConfiguration) {
    @Serializable
    data class Input(
        @field:Description("Natural language query to convert to SQL")
        val text: String,
    ) : Instructible.WithInstruction {
        override fun instructionId() = "text2sql"

        override fun instruction() = "Convert the following natural language query into SQL: '$text'"

        override fun systemInstruction(): String? = null
    }

    @Serializable
    data class Output(
        @field:Description("Generated DuckDB SQL SELECT statement")
        val sql: String,
    ) {
        init {
            SelectSqlStatement(sql)
        }
    }

    override fun getInputKClass() = Input::class

    override fun getOutputKClass() = Output::class

    companion object {
        private const val TEMPERATURE = 0.3f

        suspend fun create(
            agentId: String,
            env: DatabaseEnvironment,
            llm: LLM = LLM.ANTHROPIC_CLAUDE_OPUS_4_6_V1_CROSS_REGION_INFERENCE,
        ): Text2SqlAgenticFunction {
            DatabaseEnvironment.activate(env)

            val mcpServer = Text2SqlMcpServer(env)
            val exchange = mcpServer.pipedStreamsExchange()
            val mcpClient = Client(clientInfo = Implementation(name = "text2sql_client", version = "1.0.0"))
            mcpClient.connect(exchange.stdioClientTransport())

            return Text2SqlAgenticFunction(
                AgentConfiguration(
                    agentId = agentId,
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
                                        name = "dbtools",
                                        client = mcpClient,
                                        deniedTools = emptySet(),
                                    ),
                                ),
                        ),
                    systemPrompt =
                        """
                        You are a SQL expert. Convert natural language questions into valid DuckDB SQL.

                        RULES:
                        - Use list_tables to discover available tables
                        - Use get_tables to understand schema, columns, types, and relationships
                        - Use execute_sql to validate your SQL produces correct results
                        - Generate only SELECT statements
                        - Use proper JOINs based on foreign key relationships
                        - Current DateTime (UTC) = '${
                            Instant.now().atZone(ZoneOffset.UTC).format(DateTimeFormatter.ISO_INSTANT)
                        }'
                        """.trimIndent(),
                    contextMemoryManagers =
                        ContextMemoryManagers(
                            value = listOf(NoOperationContextMemoryManager),
                        ),
                    delayBetweenTurns = 0.seconds,
                    problemDomain = "Text-to-SQL",
                ),
            )
        }
    }
}
