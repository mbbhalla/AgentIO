package io.github.mbbhalla.agentio.module.text2sql

import aws.sdk.kotlin.services.bedrockruntime.BedrockRuntimeClient
import aws.sdk.kotlin.services.bedrockruntime.model.ContentBlock
import aws.sdk.kotlin.services.bedrockruntime.model.ConversationRole
import aws.sdk.kotlin.services.bedrockruntime.model.ConverseResponse
import aws.sdk.kotlin.services.bedrockruntime.model.Message
import aws.sdk.kotlin.services.bedrockruntime.model.StopReason
import aws.sdk.kotlin.services.bedrockruntime.model.TokenUsage
import io.github.mbbhalla.agentio.core.lib.tool.EmptyToolsProvider
import io.github.mbbhalla.agentio.core.model.AgentConfiguration
import io.github.mbbhalla.agentio.core.model.LLM
import io.github.mbbhalla.agentio.core.model.LanguageModelParameters
import io.github.mbbhalla.agentio.core.model.Temperature
import io.github.mbbhalla.agentio.data.env.DuckDbDatabaseEnvironment
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The agent's final SQL is validated on the environment it was created with; the model is mocked. */
class Text2SqlAgenticFunctionTest {
    private val env =
        DuckDbDatabaseEnvironment.fromStatements(
            ddl = listOf("CREATE TABLE orders (id VARCHAR, amount DOUBLE)"),
            dml = emptyList(),
            tableMetadata = emptySet(),
        )

    private val input = Text2SqlAgenticFunction.Input(text = "ids of all orders")

    /** An agent whose model immediately answers with [sql] as its final output. */
    private fun agentAnswering(sql: String): Text2SqlAgenticFunction {
        val bedrock = mockk<BedrockRuntimeClient>()
        coEvery { bedrock.converse(any()) } returns finalAnswer("""{"sql": "$sql"}""")
        return Text2SqlAgenticFunction(
            AgentConfiguration(
                agentId = "text2sql-test",
                languageModelParameters =
                    LanguageModelParameters(
                        llm = LLM.ANTHROPIC_CLAUDE_OPUS_4_6_V1_CROSS_REGION_INFERENCE,
                        temperature = Temperature(0.3f),
                    ),
                bedrockRuntimeClient = bedrock,
                toolsProvider = EmptyToolsProvider,
            ),
            env,
        )
    }

    private fun finalAnswer(json: String): ConverseResponse =
        mockk {
            every { output } returns
                mockk {
                    every { asMessageOrNull() } returns
                        Message {
                            role = ConversationRole.Assistant
                            content = listOf(ContentBlock.Text("<JSON>\n$json\n</JSON>"))
                        }
                }
            every { usage } returns
                TokenUsage {
                    inputTokens = 10
                    outputTokens = 5
                    totalTokens = 15
                }
            every { stopReason } returns StopReason.EndTurn
        }

    @Test
    fun `returns SQL that is a valid SELECT on its environment`() =
        runBlocking {
            val result = agentAnswering("SELECT id FROM orders").invoke(input)

            assertEquals("SELECT id FROM orders", result.getOrThrow().output.sql)
        }

    @Test
    fun `fails when the model returns SQL that does not bind on its environment`() =
        runBlocking {
            val result = agentAnswering("SELECT id FROM no_such_table").invoke(input)

            assertTrue(result.isFailure)
            assertContains(result.exceptionOrNull()?.message.orEmpty(), "Invalid SELECT SQL")
        }

    @Test
    fun `fails when the model returns a statement that is not a SELECT`() =
        runBlocking {
            val result = agentAnswering("DELETE FROM orders").invoke(input)

            assertTrue(result.isFailure)
            assertEquals("Not a SELECT statement", result.exceptionOrNull()?.message)
        }

    @Test
    fun `validates on its own environment after another environment is created`() =
        runBlocking {
            val agent = agentAnswering("SELECT id FROM orders")
            DuckDbDatabaseEnvironment.fromStatements(
                ddl = listOf("CREATE TABLE unrelated (id INTEGER)"),
                dml = emptyList(),
                tableMetadata = emptySet(),
            )

            assertTrue(agent.invoke(input).isSuccess)
        }
}
