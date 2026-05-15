package io.github.mbbhalla.agentio.core.lib.event

import aws.sdk.kotlin.services.bedrockruntime.BedrockRuntimeClient
import aws.sdk.kotlin.services.bedrockruntime.model.ContentBlock
import aws.sdk.kotlin.services.bedrockruntime.model.ConversationRole
import aws.sdk.kotlin.services.bedrockruntime.model.ConverseResponse
import aws.sdk.kotlin.services.bedrockruntime.model.Message
import aws.sdk.kotlin.services.bedrockruntime.model.StopReason
import aws.sdk.kotlin.services.bedrockruntime.model.TokenUsage
import aws.sdk.kotlin.services.bedrockruntime.model.ToolResultStatus
import io.github.mbbhalla.agentio.core.lib.AbstractAgenticFunction
import io.github.mbbhalla.agentio.core.lib.Instructible
import io.github.mbbhalla.agentio.core.lib.ctx.cmm.ContextMemoryManager
import io.github.mbbhalla.agentio.core.lib.ctx.cmm.ContextMemoryManagers
import io.github.mbbhalla.agentio.core.lib.ctx.provider.ContextProviders
import io.github.mbbhalla.agentio.core.lib.ctx.provider.EmptyContextProvider
import io.github.mbbhalla.agentio.core.lib.ctx.writer.ContextWriters
import io.github.mbbhalla.agentio.core.lib.tool.ToolsProvider
import io.github.mbbhalla.agentio.core.model.AgentConfiguration
import io.github.mbbhalla.agentio.core.model.LLM
import io.github.mbbhalla.agentio.core.model.LanguageModelParameters
import io.github.mbbhalla.agentio.core.model.Temperature
import io.github.mbbhalla.agentio.core.model.ThinkingMode
import io.github.mbbhalla.agentio.core.model.TopP
import io.github.mbbhalla.agentio.core.model.event.Event
import io.github.mbbhalla.agentio.core.model.event.EventPayload
import io.mockk.coEvery
import java.util.Collections
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.reflect.KClass
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

internal class EventListenerTest {

    private lateinit var mockBedrockClient: BedrockRuntimeClient
    private lateinit var mockToolsProvider: ToolsProvider
    private lateinit var capturedEvents: MutableList<Event>

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

    private fun buildConfig(
        eventListeners: EventListeners = EventListeners(),
    ): AgentConfiguration {
        val mockCmm = mockk<ContextMemoryManager>()
        every { mockCmm.shouldExecuteOnTurn(any()) } returns true
        coEvery { mockCmm.getContext(any()) } answers {
            val input = firstArg<ContextMemoryManager.ContextMemoryManagerInput>()
            input.conversation
        }

        return AgentConfiguration(
            agentId = "test-agent",
            problemDomain = "testing",
            languageModelParameters = LanguageModelParameters(
                llm = LLM.ANTHROPIC_CLAUDE_OPUS_4_5_V1_CROSS_REGION_INFERENCE,
                temperature = Temperature(0.7f),
                topP = TopP(0.9f),
                maxOutputTokens = 1000,
            ),
            bedrockRuntimeClient = mockBedrockClient,
            toolsProvider = mockToolsProvider,
            systemPrompt = "You are a test agent",
            thinkingMode = ThinkingMode(maxIterations = 0),
            contextMemoryManagers = ContextMemoryManagers(value = listOf(mockCmm)),
            delayBetweenTurns = 0.milliseconds,
            contextProviders = ContextProviders(listOf(EmptyContextProvider)),
            contextWriters = ContextWriters(emptySet()),
            eventListeners = eventListeners,
            maxTurnLimit = 10,
        )
    }

    @BeforeEach
    fun setup() {
        mockBedrockClient = mockk()
        mockToolsProvider = mockk()
        capturedEvents = Collections.synchronizedList(mutableListOf())
    }

    private fun capturingListeners(): EventListeners = EventListeners(
        setOf(
            EventListener { event ->
                capturedEvents.add(event)
                Result.success(Unit)
            },
        ),
    )

    private fun mockSuccessResponse(
        jsonOutput: String = """{"result": "done", "success": true}""",
        inputTokens: Int = 100,
        outputTokens: Int = 50,
    ): ConverseResponse = mockk {
        every { output } returns mockk {
            every { asMessageOrNull() } returns Message {
                role = ConversationRole.Assistant
                content = listOf(
                    ContentBlock.Text("<JSON>\n$jsonOutput\n</JSON>"),
                )
            }
        }
        every { usage } returns TokenUsage {
            this.inputTokens = inputTokens
            this.outputTokens = outputTokens
            this.totalTokens = inputTokens + outputTokens
        }
        every { stopReason } returns StopReason.EndTurn
    }

    // --- AGENT_INVOCATION_START / AGENT_INVOCATION_END ---

    @Test
    fun `should emit AgentInvocationStart and AgentInvocationEnd on successful invocation`() = runBlocking {
        // Given
        val config = buildConfig(eventListeners = capturingListeners())
        val function = TestAgenticFunction(config)
        val testInput = TestInput(id = "inv-1", instruction = "Do something")

        coEvery { mockToolsProvider.listTools() } returns emptyList()
        coEvery { mockBedrockClient.converse(any()) } returns mockSuccessResponse()

        // When
        val result = function.invoke(testInput)

        // Then
        assertTrue(result.isSuccess)

        val startEvent = capturedEvents.first().payload
        assertTrue(startEvent is EventPayload.AgentInvocationStart)
        startEvent as EventPayload.AgentInvocationStart
        assertEquals("test-agent", startEvent.agentId)
        assertEquals("inv-1", startEvent.instructionId)
        assertEquals("Do something", startEvent.instruction)

        val endEvent = capturedEvents.last().payload
        assertTrue(endEvent is EventPayload.AgentInvocationEnd)
        endEvent as EventPayload.AgentInvocationEnd
        assertEquals("test-agent", endEvent.agentId)
        assertEquals("inv-1", endEvent.instructionId)
        assertTrue(endEvent.success)
        assertNull(endEvent.error)
        assertTrue(endEvent.totalTurns > 0)
        assertTrue(endEvent.totalInputTokens > 0)
        assertTrue(endEvent.totalOutputTokens > 0)
        assertTrue(endEvent.latency > Duration.ZERO)
    }

    @Test
    fun `should emit AgentInvocationEnd with error on failure`() = runBlocking {
        // Given
        val config = buildConfig(eventListeners = capturingListeners())
        val function = TestAgenticFunction(config)
        val testInput = TestInput(id = "inv-fail", instruction = "Fail")

        coEvery { mockToolsProvider.listTools() } throws RuntimeException("Boom")

        // When
        val result = function.invoke(testInput)

        // Then
        assertTrue(result.isFailure)

        val endEvent = capturedEvents.last().payload
        assertTrue(endEvent is EventPayload.AgentInvocationEnd)
        endEvent as EventPayload.AgentInvocationEnd
        assertEquals(false, endEvent.success)
        assertNotNull(endEvent.error)
        assertEquals("Boom", endEvent.error?.message)
        assertTrue(endEvent.latency > Duration.ZERO)
    }

    // --- BEFORE_LLM_CALL / AFTER_LLM_CALL ---

    @Test
    fun `should emit BeforeLlmCall and AfterLlmCall around bedrock call`() = runBlocking {
        // Given
        val config = buildConfig(eventListeners = capturingListeners())
        val function = TestAgenticFunction(config)
        val testInput = TestInput(id = "llm-1", instruction = "Call LLM")

        coEvery { mockToolsProvider.listTools() } returns emptyList()
        coEvery { mockBedrockClient.converse(any()) } returns mockSuccessResponse(
            inputTokens = 200,
            outputTokens = 80,
        )

        // When
        function.invoke(testInput)

        // Then
        val beforeLlm = capturedEvents.map { it.payload }.filterIsInstance<EventPayload.BeforeLlmCall>()
        val afterLlm = capturedEvents.map { it.payload }.filterIsInstance<EventPayload.AfterLlmCall>()

        assertTrue(beforeLlm.isNotEmpty())
        assertTrue(afterLlm.isNotEmpty())

        val before = beforeLlm.first()
        assertEquals(LLM.ANTHROPIC_CLAUDE_OPUS_4_5_V1_CROSS_REGION_INFERENCE.id, before.modelId)
        assertTrue(before.messageCount > 0)
        assertEquals(0, before.turnNumber)

        val after = afterLlm.first()
        assertEquals(LLM.ANTHROPIC_CLAUDE_OPUS_4_5_V1_CROSS_REGION_INFERENCE.id, after.modelId)
        assertEquals(StopReason.EndTurn, after.stopReason)
        assertEquals(200, after.inputTokens)
        assertEquals(80, after.outputTokens)
        assertNull(after.error)
        assertTrue(after.latency > Duration.ZERO)
    }

    @Test
    fun `should emit AfterLlmCall with error when bedrock throws`() = runBlocking {
        // Given
        val config = buildConfig(eventListeners = capturingListeners())
        val function = TestAgenticFunction(config)
        val testInput = TestInput(id = "llm-fail", instruction = "LLM will fail")

        coEvery { mockToolsProvider.listTools() } returns emptyList()
        coEvery { mockBedrockClient.converse(any()) } throws RuntimeException("Bedrock timeout")

        // When
        val result = function.invoke(testInput)

        // Then
        assertTrue(result.isFailure)

        val afterLlm = capturedEvents.map { it.payload }.filterIsInstance<EventPayload.AfterLlmCall>()
        assertTrue(afterLlm.isNotEmpty())

        val after = afterLlm.first()
        assertNotNull(after.error)
        assertEquals("Bedrock timeout", after.error?.message)
        assertNull(after.stopReason)
        assertEquals(0, after.inputTokens)
        assertEquals(0, after.outputTokens)
        assertTrue(after.latency > Duration.ZERO)
    }

    // --- BEFORE_TOOL_CALL / AFTER_TOOL_CALL ---

    @Test
    fun `should emit BeforeToolCall and AfterToolCall around tool execution`() = runBlocking {
        // Given
        val config = buildConfig(eventListeners = capturingListeners())
        val function = TestAgenticFunction(config)
        val testInput = TestInput(id = "tool-1", instruction = "Use tool")

        val mockToolUseBlock = mockk<aws.sdk.kotlin.services.bedrockruntime.model.ToolUseBlock> {
            every { toolUseId } returns "tool-use-123"
            every { name } returns "my-tool"
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
            every { stopReason } returns StopReason.ToolUse
        }

        val mockToolResultBlock = mockk<aws.sdk.kotlin.services.bedrockruntime.model.ToolResultBlock> {
            every { toolUseId } returns "tool-use-123"
            every { content } returns emptyList()
            every { status } returns ToolResultStatus.Success
        }

        coEvery { mockToolsProvider.listTools() } returns emptyList()
        coEvery { mockToolsProvider.callTool(any()) } returns ContentBlock.ToolResult(value = mockToolResultBlock)
        coEvery { mockBedrockClient.converse(any()) } returnsMany listOf(
            toolUseResponse,
            mockSuccessResponse(),
        )

        // When
        val result = function.invoke(testInput)

        // Then
        assertTrue(result.isSuccess)

        val beforeTool = capturedEvents.map { it.payload }.filterIsInstance<EventPayload.BeforeToolCall>()
        val afterTool = capturedEvents.map { it.payload }.filterIsInstance<EventPayload.AfterToolCall>()

        assertEquals(1, beforeTool.size)
        assertEquals(1, afterTool.size)

        val before = beforeTool.first()
        assertEquals("my-tool", before.toolName)
        assertTrue(before.turnNumber >= 0)

        val after = afterTool.first()
        assertEquals("my-tool", after.toolName)
        assertNull(after.error)
        assertTrue(after.latency > Duration.ZERO)
    }

    @Test
    fun `should emit AfterToolCall with error when tool throws`() = runBlocking {
        // Given
        val config = buildConfig(eventListeners = capturingListeners())
        val function = TestAgenticFunction(config)
        val testInput = TestInput(id = "tool-fail", instruction = "Tool will fail")

        val mockToolUseBlock = mockk<aws.sdk.kotlin.services.bedrockruntime.model.ToolUseBlock> {
            every { toolUseId } returns "tool-use-456"
            every { name } returns "failing-tool"
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
            every { stopReason } returns StopReason.ToolUse
        }

        coEvery { mockToolsProvider.listTools() } returns emptyList()
        coEvery { mockToolsProvider.callTool(any()) } throws RuntimeException("Tool crashed")
        coEvery { mockBedrockClient.converse(any()) } returns toolUseResponse

        // When
        val result = function.invoke(testInput)

        // Then
        assertTrue(result.isFailure)

        val afterTool = capturedEvents.map { it.payload }.filterIsInstance<EventPayload.AfterToolCall>()
        assertTrue(afterTool.isNotEmpty())

        val after = afterTool.first()
        assertEquals("failing-tool", after.toolName)
        assertNotNull(after.error)
        assertEquals("Tool crashed", after.error?.message)
        assertTrue(after.latency > Duration.ZERO)
    }

    @Test
    fun `should emit events for parallel tool calls`() = runBlocking {
        // Given
        val config = buildConfig(eventListeners = capturingListeners())
        val function = TestAgenticFunction(config)
        val testInput = TestInput(id = "parallel-tools", instruction = "Use multiple tools")

        val mockToolUseBlock1 = mockk<aws.sdk.kotlin.services.bedrockruntime.model.ToolUseBlock> {
            every { toolUseId } returns "tool-use-a"
            every { name } returns "tool-alpha"
            every { input } returns mockk()
        }
        val mockToolUseBlock2 = mockk<aws.sdk.kotlin.services.bedrockruntime.model.ToolUseBlock> {
            every { toolUseId } returns "tool-use-b"
            every { name } returns "tool-beta"
            every { input } returns mockk()
        }

        val parallelToolUseResponse = mockk<ConverseResponse> {
            every { output } returns mockk {
                every { asMessageOrNull() } returns Message {
                    role = ConversationRole.Assistant
                    content = listOf(
                        ContentBlock.ToolUse(value = mockToolUseBlock1),
                        ContentBlock.ToolUse(value = mockToolUseBlock2),
                    )
                }
            }
            every { usage } returns TokenUsage {
                inputTokens = 80
                outputTokens = 40
                totalTokens = 120
            }
            every { stopReason } returns StopReason.ToolUse
        }

        val mockToolResultBlock1 = mockk<aws.sdk.kotlin.services.bedrockruntime.model.ToolResultBlock> {
            every { toolUseId } returns "tool-use-a"
            every { content } returns emptyList()
            every { status } returns ToolResultStatus.Success
        }
        val mockToolResultBlock2 = mockk<aws.sdk.kotlin.services.bedrockruntime.model.ToolResultBlock> {
            every { toolUseId } returns "tool-use-b"
            every { content } returns emptyList()
            every { status } returns ToolResultStatus.Success
        }

        coEvery { mockToolsProvider.listTools() } returns emptyList()
        coEvery {
            mockToolsProvider.callTool(match { it.value.name == "tool-alpha" })
        } returns ContentBlock.ToolResult(value = mockToolResultBlock1)
        coEvery {
            mockToolsProvider.callTool(match { it.value.name == "tool-beta" })
        } returns ContentBlock.ToolResult(value = mockToolResultBlock2)
        coEvery { mockBedrockClient.converse(any()) } returnsMany listOf(
            parallelToolUseResponse,
            mockSuccessResponse(),
        )

        // When
        val result = function.invoke(testInput)

        // Then
        assertTrue(result.isSuccess)

        val beforeTools = capturedEvents.map { it.payload }.filterIsInstance<EventPayload.BeforeToolCall>()
        val afterTools = capturedEvents.map { it.payload }.filterIsInstance<EventPayload.AfterToolCall>()

        assertEquals(2, beforeTools.size)
        assertEquals(2, afterTools.size)

        val toolNames = beforeTools.map { it.toolName }.toSet()
        assertTrue(toolNames.contains("tool-alpha"))
        assertTrue(toolNames.contains("tool-beta"))
    }

    // --- Event ordering ---

    @Test
    fun `should emit events in correct lifecycle order`() = runBlocking {
        // Given
        val config = buildConfig(eventListeners = capturingListeners())
        val function = TestAgenticFunction(config)
        val testInput = TestInput(id = "order-1", instruction = "Check order")

        coEvery { mockToolsProvider.listTools() } returns emptyList()
        coEvery { mockBedrockClient.converse(any()) } returns mockSuccessResponse()

        // When
        function.invoke(testInput)

        // Then — order should be: Start, BeforeLlm, AfterLlm, End
        val payloads = capturedEvents.map { it.payload }
        assertTrue(payloads.first() is EventPayload.AgentInvocationStart)
        assertTrue(payloads.last() is EventPayload.AgentInvocationEnd)

        val llmIndex = payloads.indexOfFirst { it is EventPayload.BeforeLlmCall }
        val afterLlmIndex = payloads.indexOfFirst { it is EventPayload.AfterLlmCall }
        assertTrue(llmIndex > 0) // after start
        assertTrue(afterLlmIndex > llmIndex) // after before
        assertTrue(afterLlmIndex < payloads.lastIndex) // before end
    }

    // --- TURN_COMPLETED ---

    @Test
    fun `should emit TurnCompleted after each turn`() = runBlocking {
        // Given
        val config = buildConfig(eventListeners = capturingListeners())
        val function = TestAgenticFunction(config)
        val testInput = TestInput(id = "turn-1", instruction = "Complete a turn")

        coEvery { mockToolsProvider.listTools() } returns emptyList()
        coEvery { mockBedrockClient.converse(any()) } returns mockSuccessResponse()

        // When
        val result = function.invoke(testInput)

        // Then
        assertTrue(result.isSuccess)

        val turnCompleted = capturedEvents.map { it.payload }.filterIsInstance<EventPayload.TurnCompleted>()
        assertTrue(turnCompleted.isNotEmpty())

        val first = turnCompleted.first()
        assertEquals("test-agent", first.agentId)
        assertTrue(first.turnNumber > 0)
        assertTrue(first.conversation.messages.isNotEmpty())
    }

    @Test
    fun `should emit TurnCompleted with correct turn numbers across multiple turns`() = runBlocking {
        // Given — tool use forces multiple turns
        val config = buildConfig(eventListeners = capturingListeners())
        val function = TestAgenticFunction(config)
        val testInput = TestInput(id = "multi-turn", instruction = "Multi turn")

        val mockToolUseBlock = mockk<aws.sdk.kotlin.services.bedrockruntime.model.ToolUseBlock> {
            every { toolUseId } returns "tool-use-mt"
            every { name } returns "some-tool"
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
                inputTokens = 50
                outputTokens = 25
                totalTokens = 75
            }
            every { stopReason } returns StopReason.ToolUse
        }

        val mockToolResultBlock = mockk<aws.sdk.kotlin.services.bedrockruntime.model.ToolResultBlock> {
            every { toolUseId } returns "tool-use-mt"
            every { content } returns emptyList()
            every { status } returns ToolResultStatus.Success
        }

        coEvery { mockToolsProvider.listTools() } returns emptyList()
        coEvery { mockToolsProvider.callTool(any()) } returns ContentBlock.ToolResult(value = mockToolResultBlock)
        coEvery { mockBedrockClient.converse(any()) } returnsMany listOf(
            toolUseResponse,
            mockSuccessResponse(),
        )

        // When
        val result = function.invoke(testInput)

        // Then
        assertTrue(result.isSuccess)

        val turnCompleted = capturedEvents.map { it.payload }.filterIsInstance<EventPayload.TurnCompleted>()
        assertTrue(turnCompleted.size >= 2)

        val turnNumbers = turnCompleted.map { it.turnNumber }
        assertEquals(turnNumbers.sorted(), turnNumbers)
        assertTrue(turnNumbers.all { it > 0 })
    }

    @Test
    fun `should emit TurnCompleted with conversation containing accumulated messages`() = runBlocking {
        // Given — tool use means conversation grows
        val config = buildConfig(eventListeners = capturingListeners())
        val function = TestAgenticFunction(config)
        val testInput = TestInput(id = "accum-1", instruction = "Accumulate")

        val mockToolUseBlock = mockk<aws.sdk.kotlin.services.bedrockruntime.model.ToolUseBlock> {
            every { toolUseId } returns "tool-accum"
            every { name } returns "accum-tool"
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
                inputTokens = 40
                outputTokens = 20
                totalTokens = 60
            }
            every { stopReason } returns StopReason.ToolUse
        }

        val mockToolResultBlock = mockk<aws.sdk.kotlin.services.bedrockruntime.model.ToolResultBlock> {
            every { toolUseId } returns "tool-accum"
            every { content } returns emptyList()
            every { status } returns ToolResultStatus.Success
        }

        coEvery { mockToolsProvider.listTools() } returns emptyList()
        coEvery { mockToolsProvider.callTool(any()) } returns ContentBlock.ToolResult(value = mockToolResultBlock)
        coEvery { mockBedrockClient.converse(any()) } returnsMany listOf(
            toolUseResponse,
            mockSuccessResponse(),
        )

        // When
        val result = function.invoke(testInput)

        // Then
        assertTrue(result.isSuccess)

        val turnCompleted = capturedEvents.map { it.payload }.filterIsInstance<EventPayload.TurnCompleted>()
        assertTrue(turnCompleted.size >= 2)

        // Later turns should have more messages than earlier turns
        val messageCounts = turnCompleted.map { it.conversation.messages.size }
        for (i in 1 until messageCounts.size) {
            assertTrue(messageCounts[i] >= messageCounts[i - 1])
        }
    }

    // --- No listener ---

    @Test
    fun `should work correctly when no event listeners are configured`() = runBlocking {
        // Given — empty event listeners
        val config = buildConfig(eventListeners = EventListeners())
        val function = TestAgenticFunction(config)
        val testInput = TestInput(id = "no-listener", instruction = "No listener")

        coEvery { mockToolsProvider.listTools() } returns emptyList()
        coEvery { mockBedrockClient.converse(any()) } returns mockSuccessResponse()

        // When
        val result = function.invoke(testInput)

        // Then — should succeed without errors, no events captured
        assertTrue(result.isSuccess)
        assertTrue(capturedEvents.isEmpty())
    }
}
