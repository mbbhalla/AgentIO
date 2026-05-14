package io.github.mbbhalla.agentio.core.lib

import aws.sdk.kotlin.services.bedrockruntime.BedrockRuntimeClient
import aws.sdk.kotlin.services.bedrockruntime.model.ContentBlock
import aws.sdk.kotlin.services.bedrockruntime.model.ConversationRole
import aws.sdk.kotlin.services.bedrockruntime.model.ConverseResponse
import aws.sdk.kotlin.services.bedrockruntime.model.Message
import aws.sdk.kotlin.services.bedrockruntime.model.StopReason
import aws.sdk.kotlin.services.bedrockruntime.model.TokenUsage
import io.github.mbbhalla.agentio.core.lib.ctx.cmm.ContextMemoryManager
import io.github.mbbhalla.agentio.core.lib.ctx.cmm.ContextMemoryManagers
import io.github.mbbhalla.agentio.core.lib.ctx.provider.ContextProviders
import io.github.mbbhalla.agentio.core.lib.ctx.writer.ContextWriters
import io.github.mbbhalla.agentio.core.lib.ctx.provider.EmptyContextProvider
import io.github.mbbhalla.agentio.core.lib.tool.ToolsProvider
import io.github.mbbhalla.agentio.core.model.AgentConfiguration
import io.github.mbbhalla.agentio.core.model.LLM
import io.github.mbbhalla.agentio.core.model.LanguageModelParameters
import io.github.mbbhalla.agentio.core.model.Temperature
import io.github.mbbhalla.agentio.core.model.ThinkingMode
import io.github.mbbhalla.agentio.core.model.TopP
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.reflect.KClass
import kotlin.time.Duration.Companion.milliseconds

internal class AbstractAgenticFunctionTest {

    private lateinit var mockBedrockClient: BedrockRuntimeClient
    private lateinit var mockToolsProvider: ToolsProvider
    private lateinit var mockContextMemoryManagers: ContextMemoryManagers
    private lateinit var agentConfiguration: AgentConfiguration
    private lateinit var testFunction: TestAgenticFunction

    @Serializable
    data class TestInput(
        private val id: String,
        private val instruction: String,
        private val systemInstruction: String? = null,
    ) : Instructible.WithInstruction {
        override fun instructionId(): String = id
        override fun instruction(): String = instruction
        override fun systemInstruction(): String? = systemInstruction
    }

    @Serializable
    data class TestOutput(
        val result: String,
        val success: Boolean,
    )

    private class TestAgenticFunction(
        agentConfiguration: AgentConfiguration,
    ) : AbstractAgenticFunction<TestInput, TestOutput>(agentConfiguration) {

        override fun getInputKClass(): KClass<TestInput> = TestInput::class
        override fun getOutputKClass(): KClass<TestOutput> = TestOutput::class
    }

    @BeforeEach
    fun setup() {
        mockBedrockClient = mockk()
        mockToolsProvider = mockk()

        val mockCmm = mockk<ContextMemoryManager>()
        every { mockCmm.shouldExecuteOnTurn(any()) } returns true
        coEvery { mockCmm.getContext(any()) } answers {
            val input = firstArg<ContextMemoryManager.ContextMemoryManagerInput>()
            input.conversation
        }
        mockContextMemoryManagers = ContextMemoryManagers(value = listOf(mockCmm))

        val languageModelParameters = LanguageModelParameters(
            llm = LLM.ANTHROPIC_CLAUDE_OPUS_4_5_V1_CROSS_REGION_INFERENCE,
            temperature = Temperature(0.7f),
            topP = TopP(0.9f),
            maxOutputTokens = 1000,
            additionalModelRequestFields = emptyMap(),
        )

        agentConfiguration = AgentConfiguration(
            agentId = "test-agent",
            problemDomain = "testing",
            languageModelParameters = languageModelParameters,
            bedrockRuntimeClient = mockBedrockClient,
            toolsProvider = mockToolsProvider,
            systemPrompt = "You are a test agent",
            thinkingMode = ThinkingMode(maxIterations = 1),
            contextMemoryManagers = mockContextMemoryManagers,
            delayBetweenTurns = 10.milliseconds,
            contextProviders = ContextProviders(listOf(EmptyContextProvider)),
            contextWriters = ContextWriters(emptySet()),
            maxTurnLimit = 10,
        )

        testFunction = TestAgenticFunction(agentConfiguration)
    }

    @Test
    fun `should handle exceptions and return failure when tools provider fails`() = runBlocking {
        // Given
        val testInput = TestInput(
            id = "test-123",
            instruction = "Generate a test output",
            systemInstruction = "Be helpful",
        )

        coEvery { mockToolsProvider.listTools() } throws RuntimeException("Tool provider failed")

        // When
        val result = testFunction.invoke(testInput)

        // Then
        assertTrue(result.isFailure)
        assertTrue(result.cause is RuntimeException)
        assertEquals("Tool provider failed", result.cause.message)
    }

    @Test
    fun `should handle context memory manager failures`() = runBlocking {
        // Given — create a failing CMM and wire it into a fresh config
        val failingCmm = object : ContextMemoryManager {
            override suspend fun getContext(
                input: ContextMemoryManager.ContextMemoryManagerInput,
            ): io.github.mbbhalla.agentio.core.model.Conversation =
                throw RuntimeException("Context manager failed")
        }
        val failingConfig = agentConfiguration.copy(
            contextMemoryManagers = ContextMemoryManagers(value = listOf(failingCmm)),
        )
        val failingFunction = TestAgenticFunction(failingConfig)

        val testInput = TestInput(
            id = "test-456",
            instruction = "This will fail",
        )

        val mockResponse = mockk<ConverseResponse> {
            every { output } returns mockk {
                every { asMessageOrNull() } returns Message {
                    role = ConversationRole.Assistant
                    content = listOf(
                        ContentBlock.Text("Some response"),
                    )
                }
            }
            every { usage } returns TokenUsage {
                inputTokens = 50
                outputTokens = 25
                totalTokens = 75
            }
            every { stopReason } returns StopReason.EndTurn
        }

        coEvery { mockToolsProvider.listTools() } returns emptyList()
        coEvery { mockBedrockClient.converse(any()) } returns mockResponse

        // When
        val result = failingFunction.invoke(testInput)

        // Then
        assertTrue(result.isFailure)
        assertTrue(result.cause is RuntimeException)
        assertEquals("Context manager failed", result.cause.message)
    }

    @Test
    fun `should handle bedrock client failures`() = runBlocking {
        // Given
        val testInput = TestInput(
            id = "test-789",
            instruction = "Test with bedrock failure",
            systemInstruction = "Custom system prompt",
        )

        coEvery { mockToolsProvider.listTools() } returns emptyList()
        coEvery { mockBedrockClient.converse(any()) } throws RuntimeException("Bedrock failed")

        // When
        val result = testFunction.invoke(testInput)

        // Then
        assertTrue(result.isFailure)
        assertTrue(result.cause is RuntimeException)
        assertEquals("Bedrock failed", result.cause.message)
    }

    @Test
    fun `should return correct input and output class types`() {
        // When & Then
        assertEquals(TestInput::class, testFunction.getInputKClass())
        assertEquals(TestOutput::class, testFunction.getOutputKClass())
    }

    @Test
    fun `should handle successful conversation with valid JSON output`() = runBlocking {
        // Given
        val testInput = TestInput(
            id = "test-success",
            instruction = "Generate output",
        )

        val mockResponse = mockk<ConverseResponse> {
            every { output } returns mockk {
                every { asMessageOrNull() } returns Message {
                    role = ConversationRole.Assistant
                    content = listOf(
                        ContentBlock.Text(
                            """
                            <JSON>
                            {"result": "success", "success": true}
                            </JSON>
                            """.trimIndent(),
                        ),
                    )
                }
            }
            every { usage } returns TokenUsage {
                inputTokens = 100
                outputTokens = 50
                totalTokens = 150
            }
            every { stopReason } returns StopReason.EndTurn
        }

        coEvery { mockToolsProvider.listTools() } returns emptyList()
        coEvery { mockBedrockClient.converse(any()) } returns mockResponse

        // When
        val result = testFunction.invoke(testInput)

        // Then
        assertTrue(result.isSuccess)
        val output = result.get()
        assertEquals("test-success", output.instructionId)
        assertEquals("success", output.output.result)
        assertTrue(output.output.success)
    }

    @Test
    fun `should handle conversation with StopSequence stop reason`() = runBlocking {
        // Given
        val testInput = TestInput(
            id = "test-stop-seq",
            instruction = "Test stop sequence",
        )

        val mockResponse = mockk<ConverseResponse> {
            every { output } returns mockk {
                every { asMessageOrNull() } returns Message {
                    role = ConversationRole.Assistant
                    content = listOf(
                        ContentBlock.Text(
                            """
                            <JSON>
                            {"result": "stopped", "success": true}
                            </JSON>
                            """.trimIndent(),
                        ),
                    )
                }
            }
            every { usage } returns TokenUsage {
                inputTokens = 80
                outputTokens = 40
                totalTokens = 120
            }
            every { stopReason } returns StopReason.StopSequence
        }

        coEvery { mockToolsProvider.listTools() } returns emptyList()
        coEvery { mockBedrockClient.converse(any()) } returns mockResponse

        // When
        val result = testFunction.invoke(testInput)

        // Then
        assertTrue(result.isSuccess)
        assertEquals("stopped", result.get().output.result)
    }

    @Test
    fun `should handle thinking mode with multiple iterations`() = runBlocking {
        // Given
        val testInput = TestInput(
            id = "test-thinking",
            instruction = "Think deeply",
        )

        val thinkingResponse = mockk<ConverseResponse> {
            every { output } returns mockk {
                every { asMessageOrNull() } returns Message {
                    role = ConversationRole.Assistant
                    content = listOf(
                        ContentBlock.Text("Thinking..."),
                    )
                }
            }
            every { usage } returns TokenUsage {
                inputTokens = 50
                outputTokens = 25
                totalTokens = 75
            }
            every { stopReason } returns StopReason.EndTurn
        }

        val finalResponse = mockk<ConverseResponse> {
            every { output } returns mockk {
                every { asMessageOrNull() } returns Message {
                    role = ConversationRole.Assistant
                    content = listOf(
                        ContentBlock.Text(
                            """
                            <JSON>
                            {"result": "thought through", "success": true}
                            </JSON>
                            """.trimIndent(),
                        ),
                    )
                }
            }
            every { usage } returns TokenUsage {
                inputTokens = 60
                outputTokens = 30
                totalTokens = 90
            }
            every { stopReason } returns StopReason.EndTurn
        }

        coEvery { mockToolsProvider.listTools() } returns emptyList()
        coEvery { mockBedrockClient.converse(any()) } returnsMany listOf(
            thinkingResponse,
            finalResponse,
        )

        // When
        val result = testFunction.invoke(testInput)

        // Then
        assertTrue(result.isSuccess)
        assertEquals("thought through", result.get().output.result)
    }

    @Test
    fun `should handle tool use in conversation flow`() = runBlocking {
        // Given
        val testInput = TestInput(
            id = "test-tool",
            instruction = "Use a tool",
        )

        val mockToolUseBlock = mockk<aws.sdk.kotlin.services.bedrockruntime.model.ToolUseBlock> {
            every { toolUseId } returns "tool-123"
            every { name } returns "test-tool"
            every { input } returns mockk()
        }

        val toolUseResponse = mockk<ConverseResponse> {
            every { output } returns mockk {
                every { asMessageOrNull() } returns Message {
                    role = ConversationRole.Assistant
                    content = listOf(
                        ContentBlock.ToolUse(value = mockToolUseBlock),
                    )
                }
            }
            every { usage } returns TokenUsage {
                inputTokens = 60
                outputTokens = 30
                totalTokens = 90
            }
            every { stopReason } returns StopReason.MaxTokens
        }

        val finalResponse = mockk<ConverseResponse> {
            every { output } returns mockk {
                every { asMessageOrNull() } returns Message {
                    role = ConversationRole.Assistant
                    content = listOf(
                        ContentBlock.Text(
                            """
                            <JSON>
                            {"result": "tool used", "success": true}
                            </JSON>
                            """.trimIndent(),
                        ),
                    )
                }
            }
            every { usage } returns TokenUsage {
                inputTokens = 70
                outputTokens = 35
                totalTokens = 105
            }
            every { stopReason } returns StopReason.EndTurn
        }

        val mockToolResultBlock = mockk<aws.sdk.kotlin.services.bedrockruntime.model.ToolResultBlock> {
            every { toolUseId } returns "tool-123"
            every { content } returns emptyList()
            every { status } returns aws.sdk.kotlin.services.bedrockruntime.model.ToolResultStatus.Success
        }

        coEvery { mockToolsProvider.listTools() } returns emptyList()
        coEvery { mockToolsProvider.callTool(any()) } returns ContentBlock.ToolResult(value = mockToolResultBlock)
        coEvery { mockBedrockClient.converse(any()) } returnsMany listOf(
            toolUseResponse,
            finalResponse,
        )

        // When
        val result = testFunction.invoke(testInput)

        // Then
        assertTrue(result.isSuccess)
        assertEquals("tool used", result.get().output.result)
    }

    @Test
    fun `should handle custom system instruction`() = runBlocking {
        // Given
        val testInput = TestInput(
            id = "test-custom-sys",
            instruction = "Test",
            systemInstruction = "Custom system prompt",
        )

        val mockResponse = mockk<ConverseResponse> {
            every { output } returns mockk {
                every { asMessageOrNull() } returns Message {
                    role = ConversationRole.Assistant
                    content = listOf(
                        ContentBlock.Text(
                            """
                            <JSON>
                            {"result": "custom", "success": true}
                            </JSON>
                            """.trimIndent(),
                        ),
                    )
                }
            }
            every { usage } returns TokenUsage {
                inputTokens = 90
                outputTokens = 45
                totalTokens = 135
            }
            every { stopReason } returns StopReason.EndTurn
        }

        coEvery { mockToolsProvider.listTools() } returns emptyList()
        coEvery { mockBedrockClient.converse(any()) } returns mockResponse

        // When
        val result = testFunction.invoke(testInput)

        // Then
        assertTrue(result.isSuccess)
        assertEquals("custom", result.get().output.result)
    }

    @Test
    fun `should handle null problem domain in agent configuration`() = runBlocking {
        // Given
        val configWithNullDomain = agentConfiguration.copy(problemDomain = null)
        val functionWithNullDomain = TestAgenticFunction(configWithNullDomain)

        val testInput = TestInput(
            id = "test-null-domain",
            instruction = "Test",
        )

        val mockResponse = mockk<ConverseResponse> {
            every { output } returns mockk {
                every { asMessageOrNull() } returns Message {
                    role = ConversationRole.Assistant
                    content = listOf(
                        ContentBlock.Text(
                            """
                            <JSON>
                            {"result": "no domain", "success": true}
                            </JSON>
                            """.trimIndent(),
                        ),
                    )
                }
            }
            every { usage } returns TokenUsage {
                inputTokens = 40
                outputTokens = 20
                totalTokens = 60
            }
            every { stopReason } returns StopReason.EndTurn
        }

        coEvery { mockToolsProvider.listTools() } returns emptyList()
        coEvery { mockBedrockClient.converse(any()) } returns mockResponse

        // When
        val result = functionWithNullDomain.invoke(testInput)

        // Then
        assertTrue(result.isSuccess)
        assertEquals("no domain", result.get().output.result)
    }

    @Test
    fun `should handle null system instruction`() = runBlocking {
        // Given
        val configWithNullSystemPrompt = agentConfiguration.copy(systemPrompt = null)
        val functionWithNullSystemPrompt = TestAgenticFunction(configWithNullSystemPrompt)

        val testInput = TestInput(
            id = "test-null-sys",
            instruction = "Test",
            systemInstruction = null,
        )

        val mockResponse = mockk<ConverseResponse> {
            every { output } returns mockk {
                every { asMessageOrNull() } returns Message {
                    role = ConversationRole.Assistant
                    content = listOf(
                        ContentBlock.Text(
                            """
                            <JSON>
                            {"result": "no system", "success": true}
                            </JSON>
                            """.trimIndent(),
                        ),
                    )
                }
            }
            every { usage } returns TokenUsage {
                inputTokens = 35
                outputTokens = 18
                totalTokens = 53
            }
            every { stopReason } returns StopReason.EndTurn
        }

        coEvery { mockToolsProvider.listTools() } returns emptyList()
        coEvery { mockBedrockClient.converse(any()) } returns mockResponse

        // When
        val result = functionWithNullSystemPrompt.invoke(testInput)

        // Then
        assertTrue(result.isSuccess)
        assertEquals("no system", result.get().output.result)
    }

    @Test
    fun `should handle thinking mode reaching max iterations`() = runBlocking {
        // Given - set maxIterations to 0 so thinking mode is exhausted immediately
        val configWithNoThinking = agentConfiguration.copy(thinkingMode = ThinkingMode(maxIterations = 0))
        val functionWithNoThinking = TestAgenticFunction(configWithNoThinking)

        val testInput = TestInput(
            id = "test-no-thinking",
            instruction = "Test",
        )

        val mockResponse = mockk<ConverseResponse> {
            every { output } returns mockk {
                every { asMessageOrNull() } returns Message {
                    role = ConversationRole.Assistant
                    content = listOf(
                        ContentBlock.Text(
                            """
                            <JSON>
                            {"result": "no thinking", "success": true}
                            </JSON>
                            """.trimIndent(),
                        ),
                    )
                }
            }
            every { usage } returns TokenUsage {
                inputTokens = 30
                outputTokens = 15
                totalTokens = 45
            }
            every { stopReason } returns StopReason.EndTurn
        }

        coEvery { mockToolsProvider.listTools() } returns emptyList()
        coEvery { mockBedrockClient.converse(any()) } returns mockResponse

        // When
        val result = functionWithNoThinking.invoke(testInput)

        // Then
        assertTrue(result.isSuccess)
        assertEquals("no thinking", result.get().output.result)
    }

    @Test
    fun `should handle MaxTokens stop reason`() = runBlocking {
        // Given
        val testInput = TestInput(
            id = "test-max-tokens",
            instruction = "Test max tokens",
        )

        val mockResponse = mockk<ConverseResponse> {
            every { output } returns mockk {
                every { asMessageOrNull() } returns Message {
                    role = ConversationRole.Assistant
                    content = listOf(
                        ContentBlock.Text(
                            """
                            <JSON>
                            {"result": "max tokens", "success": true}
                            </JSON>
                            """.trimIndent(),
                        ),
                    )
                }
            }
            every { usage } returns TokenUsage {
                inputTokens = 500
                outputTokens = 500
                totalTokens = 1000
            }
            every { stopReason } returns StopReason.MaxTokens
        }

        coEvery { mockToolsProvider.listTools() } returns emptyList()
        coEvery { mockBedrockClient.converse(any()) } returns mockResponse

        // When
        val result = testFunction.invoke(testInput)

        // Then
        assertTrue(result.isSuccess)
        assertEquals("max tokens", result.get().output.result)
    }

    @Test
    fun `should handle ContentGuardRail stop reason`() = runBlocking {
        // Given
        val testInput = TestInput(
            id = "test-guardrail",
            instruction = "Test guardrail",
        )

        val mockResponse = mockk<ConverseResponse> {
            every { output } returns mockk {
                every { asMessageOrNull() } returns Message {
                    role = ConversationRole.Assistant
                    content = listOf(
                        ContentBlock.Text(
                            """
                            <JSON>
                            {"result": "guardrail", "success": true}
                            </JSON>
                            """.trimIndent(),
                        ),
                    )
                }
            }
            every { usage } returns TokenUsage {
                inputTokens = 45
                outputTokens = 22
                totalTokens = 67
            }
            every { stopReason } returns StopReason.ContentFiltered
        }

        coEvery { mockToolsProvider.listTools() } returns emptyList()
        coEvery { mockBedrockClient.converse(any()) } returns mockResponse

        // When
        val result = testFunction.invoke(testInput)

        // Then
        assertTrue(result.isSuccess)
        assertEquals("guardrail", result.get().output.result)
    }

    @Test
    fun `should handle ToolUse stop reason`() = runBlocking {
        // Given
        val testInput = TestInput(
            id = "test-tool-stop",
            instruction = "Test tool stop",
        )

        val mockResponse = mockk<ConverseResponse> {
            every { output } returns mockk {
                every { asMessageOrNull() } returns Message {
                    role = ConversationRole.Assistant
                    content = listOf(
                        ContentBlock.Text(
                            """
                            <JSON>
                            {"result": "tool stop", "success": true}
                            </JSON>
                            """.trimIndent(),
                        ),
                    )
                }
            }
            every { usage } returns TokenUsage {
                inputTokens = 42
                outputTokens = 21
                totalTokens = 63
            }
            every { stopReason } returns StopReason.ToolUse
        }

        coEvery { mockToolsProvider.listTools() } returns emptyList()
        coEvery { mockBedrockClient.converse(any()) } returns mockResponse

        // When
        val result = testFunction.invoke(testInput)

        // Then
        assertTrue(result.isSuccess)
        assertEquals("tool stop", result.get().output.result)
    }

    @Test
    fun `should handle additional model request fields`() = runBlocking {
        // Given
        val configWithAdditionalFields = agentConfiguration.copy(
            languageModelParameters = agentConfiguration.languageModelParameters.copy(
                additionalModelRequestFields = mapOf(
                    "thinking" to kotlinx.serialization.json.JsonObject(
                        mapOf(
                            "type" to kotlinx.serialization.json.JsonPrimitive("enabled"),
                        ),
                    ),
                ),
            ),
        )
        val functionWithAdditionalFields = TestAgenticFunction(configWithAdditionalFields)

        val testInput = TestInput(
            id = "test-additional-fields",
            instruction = "Test",
        )

        val mockResponse = mockk<ConverseResponse> {
            every { output } returns mockk {
                every { asMessageOrNull() } returns Message {
                    role = ConversationRole.Assistant
                    content = listOf(
                        ContentBlock.Text(
                            """
                            <JSON>
                            {"result": "with fields", "success": true}
                            </JSON>
                            """.trimIndent(),
                        ),
                    )
                }
            }
            every { usage } returns TokenUsage {
                inputTokens = 38
                outputTokens = 19
                totalTokens = 57
            }
            every { stopReason } returns StopReason.EndTurn
        }

        coEvery { mockToolsProvider.listTools() } returns emptyList()
        coEvery { mockBedrockClient.converse(any()) } returns mockResponse

        // When
        val result = functionWithAdditionalFields.invoke(testInput)

        // Then
        assertTrue(result.isSuccess)
        assertEquals("with fields", result.get().output.result)
    }

    @Test
    fun `should handle empty usage in response`() = runBlocking {
        // Given
        val testInput = TestInput(
            id = "test-empty-usage",
            instruction = "Test",
        )

        val mockResponse = mockk<ConverseResponse> {
            every { output } returns mockk {
                every { asMessageOrNull() } returns Message {
                    role = ConversationRole.Assistant
                    content = listOf(
                        ContentBlock.Text(
                            """
                            <JSON>
                            {"result": "no usage", "success": true}
                            </JSON>
                            """.trimIndent(),
                        ),
                    )
                }
            }
            every { usage } returns null
            every { stopReason } returns StopReason.EndTurn
        }

        coEvery { mockToolsProvider.listTools() } returns emptyList()
        coEvery { mockBedrockClient.converse(any()) } returns mockResponse

        // When
        val result = testFunction.invoke(testInput)

        // Then
        assertTrue(result.isSuccess)
        assertEquals("no usage", result.get().output.result)
    }
}
