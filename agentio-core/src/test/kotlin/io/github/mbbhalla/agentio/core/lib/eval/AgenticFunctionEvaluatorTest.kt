package io.github.mbbhalla.agentio.core.lib.eval

import aws.sdk.kotlin.services.bedrockruntime.model.ContentBlock
import aws.sdk.kotlin.services.bedrockruntime.model.StopReason
import aws.sdk.kotlin.services.bedrockruntime.model.TokenUsage
import aws.sdk.kotlin.services.bedrockruntime.model.ToolUseBlock
import aws.smithy.kotlin.runtime.content.Document
import io.github.mbbhalla.agentio.core.lib.AgentOutput
import io.github.mbbhalla.agentio.core.lib.Instructible
import io.github.mbbhalla.agentio.core.model.conversation.Conversation
import io.mockk.coEvery
import io.mockk.mockk
import io.vavr.control.Try
import io.vavr.kotlin.failure
import io.vavr.kotlin.success
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

internal class AgenticFunctionEvaluatorTest {
    @Serializable
    data class TestInput(
        private val id: String,
        private val instruction: String,
    ) : Instructible.WithInstruction {
        override fun instructionId(): String = id

        override fun instruction(): String = instruction

        override fun systemInstruction(): String? = null
    }

    @Serializable
    data class TestOutput(
        val result: String,
        val score: Int,
    )

    private lateinit var mockAgenticFunction: Instructible<TestInput, Try<AgentOutput<TestOutput>>>
    private lateinit var testInput: TestInput

    @BeforeEach
    fun setup() {
        mockAgenticFunction = mockk()
        testInput = TestInput("test-1", "evaluate this")
    }

    private fun agentOutput(output: TestOutput): AgentOutput<TestOutput> =
        AgentOutput(
            instructionId = "test-1",
            conversation = Conversation.initialize(listOf("test")),
            output = output,
        )

    private fun agentOutputWithTokens(
        output: TestOutput,
        inputTokens: Int,
        outputTokens: Int,
    ): AgentOutput<TestOutput> {
        val conversation =
            Conversation
                .initialize(listOf("test"))
                .appendAssistantRoleContent(
                    contentBlocks = listOf(ContentBlock.Text(value = "response")),
                    additionalTokenUsage =
                        TokenUsage {
                            this.inputTokens = inputTokens
                            this.outputTokens = outputTokens
                            this.totalTokens = inputTokens + outputTokens
                        },
                    stopReason = StopReason.EndTurn,
                )
        return AgentOutput(
            instructionId = "test-1",
            conversation = conversation,
            output = output,
        )
    }

    private fun toolUseBlock(name: String): ToolUseBlock =
        ToolUseBlock {
            this.name = name
            this.toolUseId = "id-$name"
            this.input = Document.String("{}")
        }

    // ─── Selection strategies ───────────────────────────────────────────────

    @Test
    fun `MostFrequentAgentOutputSelector selects the most frequent output`() {
        val output1 = TestOutput("result1", 10)
        val output2 = TestOutput("result2", 20)
        val outputs =
            listOf(
                success(agentOutput(output1)),
                success(agentOutput(output2)),
                success(agentOutput(output1)), // output1 wins 2-1
            )

        val result = MostFrequentAgentOutputSelector<TestOutput>().select(outputs)

        assertTrue(result.isSuccess)
        assertEquals(output1, result.get().output)
    }

    @Test
    fun `MostFrequentAgentOutputSelector ignores failures`() {
        val output1 = TestOutput("result1", 10)
        val outputs =
            listOf(
                success(agentOutput(output1)),
                failure(RuntimeException("Failed")),
                failure(RuntimeException("Failed again")),
            )

        val result = MostFrequentAgentOutputSelector<TestOutput>().select(outputs)

        assertTrue(result.isSuccess)
        assertEquals(output1, result.get().output)
    }

    @Test
    fun `MostFrequentAgentOutputSelector breaks ties with the tie-breaker`() {
        val output1 = TestOutput("result1", 10)
        val output2 = TestOutput("result2", 20)
        val outputs =
            listOf(
                success(agentOutput(output1)),
                success(agentOutput(output2)), // 1-1 tie
            )

        val tieBreaker = AgentOutputSelector<TestOutput> { it.last() }
        val result = MostFrequentAgentOutputSelector(tieBreaker).select(outputs)

        assertTrue(result.isSuccess)
        assertEquals(output2, result.get().output)
    }

    @Test
    fun `MostFrequentAgentOutputSelector returns failure when there are no successes`() {
        val outputs = listOf(failure<AgentOutput<TestOutput>>(RuntimeException("boom")))

        val result = MostFrequentAgentOutputSelector<TestOutput>().select(outputs)

        assertTrue(result.isFailure)
        assertTrue(result.cause is IllegalStateException)
    }

    @Test
    fun `MetricAgentOutputSelector MAXIMIZE selects the highest-scoring output`() {
        val low = TestOutput("low", 10)
        val high = TestOutput("high", 99)
        val outputs =
            listOf(
                success(agentOutput(low)),
                success(agentOutput(high)),
            )

        val result =
            MetricAgentOutputSelector<TestOutput>(SelectionMode.MAXIMIZE) { it.output.score.toDouble() }
                .select(outputs)

        assertTrue(result.isSuccess)
        assertEquals(high, result.get().output)
    }

    @Test
    fun `MetricAgentOutputSelector MINIMIZE selects the lowest-scoring output`() {
        val low = TestOutput("low", 10)
        val high = TestOutput("high", 99)
        val outputs =
            listOf(
                success(agentOutput(low)),
                success(agentOutput(high)),
            )

        val result =
            MetricAgentOutputSelector<TestOutput>(SelectionMode.MINIMIZE) { it.output.score.toDouble() }
                .select(outputs)

        assertTrue(result.isSuccess)
        assertEquals(low, result.get().output)
    }

    @Test
    fun `MetricAgentOutputSelector returns failure when there are no successes`() {
        val outputs = listOf(failure<AgentOutput<TestOutput>>(RuntimeException("boom")))

        val result =
            MetricAgentOutputSelector<TestOutput>(SelectionMode.MAXIMIZE) { it.output.score.toDouble() }
                .select(outputs)

        assertTrue(result.isFailure)
        assertTrue(result.cause is IllegalStateException)
    }

    @Test
    fun `MetricAgentOutputSelector can minimize a conversation metric to pick the cheapest output`() {
        val cheap = agentOutputWithTokens(TestOutput("cheap", 1), inputTokens = 10, outputTokens = 5)
        val pricey = agentOutputWithTokens(TestOutput("pricey", 2), inputTokens = 100, outputTokens = 80)
        val outputs = listOf(success(pricey), success(cheap))

        val result =
            MetricAgentOutputSelector<TestOutput>(SelectionMode.MINIMIZE, ConversationMetrics::totalTokens)
                .select(outputs)

        assertTrue(result.isSuccess)
        assertEquals("cheap", result.get().output.result)
    }

    @Test
    fun `FilteringAgentOutputSelector discards outputs failing the predicate before delegating`() {
        val keep = agentOutputWithTokens(TestOutput("keep", 1), inputTokens = 10, outputTokens = 5)
        val drop = agentOutputWithTokens(TestOutput("drop", 2), inputTokens = 1, outputTokens = 1)
        val outputs = listOf(success(drop), success(keep))

        // Only keep conversations that used more than 2 output tokens, then take the most frequent.
        val selector =
            FilteringAgentOutputSelector<TestOutput>(
                predicate = { it.conversation.tokenUsage.totalOutputTokens > 2 },
                delegate = MostFrequentAgentOutputSelector(),
            )
        val result = selector.select(outputs)

        assertTrue(result.isSuccess)
        assertEquals("keep", result.get().output.result)
    }

    @Test
    fun `FilteringAgentOutputSelector yields the delegate failure when nothing survives`() {
        val drop = agentOutputWithTokens(TestOutput("drop", 2), inputTokens = 1, outputTokens = 1)
        val outputs = listOf(success(drop))

        val selector =
            FilteringAgentOutputSelector<TestOutput>(
                predicate = { false },
                delegate = MostFrequentAgentOutputSelector(),
            )
        val result = selector.select(outputs)

        assertTrue(result.isFailure)
        assertTrue(result.cause is IllegalStateException)
    }

    @Test
    fun `MostFrequent with a metric tie-breaker prefers the cheaper of tied outputs`() {
        val output = TestOutput("tied", 1)
        val cheap = agentOutputWithTokens(output, inputTokens = 5, outputTokens = 5)
        val pricey = agentOutputWithTokens(output, inputTokens = 50, outputTokens = 50)
        // Both carry the same output value, so frequency ties; the tie-breaker decides.
        val outputs = listOf(success(pricey), success(cheap))

        val selector =
            MostFrequentAgentOutputSelector(
                tieBreaker =
                    MetricAgentOutputSelector<TestOutput>(SelectionMode.MINIMIZE, ConversationMetrics::totalTokens),
            )
        val result = selector.select(outputs)

        assertTrue(result.isSuccess)
        assertEquals(10.0, ConversationMetrics.totalTokens(result.get()))
    }

    @Test
    fun `FirstSuccessAgentOutputSelector returns failure on empty input`() {
        val result = FirstSuccessAgentOutputSelector<TestOutput>().select(emptyList())

        assertTrue(result.isFailure)
        assertTrue(result.cause is IllegalStateException)
    }

    // ─── ConversationMetrics ────────────────────────────────────────────────

    @Test
    fun `totalTokens sums input and output tokens across the conversation`() {
        val output = agentOutputWithTokens(TestOutput("x", 1), inputTokens = 30, outputTokens = 12)

        assertEquals(42.0, ConversationMetrics.totalTokens(output))
    }

    @Test
    fun `outputTokens counts only generated tokens`() {
        val output = agentOutputWithTokens(TestOutput("x", 1), inputTokens = 30, outputTokens = 12)

        assertEquals(12.0, ConversationMetrics.outputTokens(output))
    }

    @Test
    fun `rounds counts the messages in the conversation`() {
        // initialize() seeds one user message, appendAssistantRoleContent adds a second.
        val output = agentOutputWithTokens(TestOutput("x", 1), inputTokens = 1, outputTokens = 1)

        assertEquals(2.0, ConversationMetrics.rounds(output))
    }

    @Test
    fun `toolCalls counts ToolUse content blocks across all messages`() {
        val conversation =
            Conversation
                .initialize(listOf("test"))
                .appendAssistantRoleContent(
                    contentBlocks =
                        listOf(
                            ContentBlock.Text(value = "let me use tools"),
                            ContentBlock.ToolUse(value = toolUseBlock("tool_a")),
                            ContentBlock.ToolUse(value = toolUseBlock("tool_b")),
                        ),
                    additionalTokenUsage = Conversation.TOKEN_USAGE_ZERO,
                    stopReason = StopReason.EndTurn,
                )
        val output = AgentOutput("test-1", conversation, TestOutput("x", 1))

        assertEquals(2.0, ConversationMetrics.toolCalls(output))
    }

    @Test
    fun `thinkingIterations reflects the conversation thinking mode counter`() {
        val conversation =
            Conversation
                .initialize(listOf("test"))
                .appendUserRoleContent(
                    contentBlock = ContentBlock.Text(value = "think again"),
                    additionalTokenUsage = Conversation.TOKEN_USAGE_ZERO,
                    incrementThinkingModeCounter = 3,
                )
        val output = AgentOutput("test-1", conversation, TestOutput("x", 1))

        assertEquals(3.0, ConversationMetrics.thinkingIterations(output))
    }

    // ─── Evaluator: evaluate() ──────────────────────────────────────────────

    @Test
    fun `evaluate runs all iterations and returns the selected output plus raw data`() =
        runBlocking {
            val output = TestOutput("success", 95)
            coEvery { mockAgenticFunction.invoke(testInput) } returns success(agentOutput(output))

            val evaluator =
                AgenticFunctionEvaluator(
                    AgenticFunctionEvaluator.EvaluationConfig(
                        agenticFunctionFactory = { mockAgenticFunction },
                        numIterations = 3,
                        maxParallelism = 2,
                        selectionStrategy = MostFrequentAgentOutputSelector(),
                    ),
                )

            val result = evaluator.evaluate(testInput)

            assertEquals(3, result.totalIterations)
            assertEquals(3, result.allOutputs.size)
            assertTrue(result.allOutputs.all { it.isSuccess })
            assertTrue(result.selectedOutput.isSuccess)
            assertEquals(output, result.selectedOutput.get().output)
            assertEquals("test-1", result.selectedOutput.get().instructionId)
        }

    @Test
    fun `evaluate preserves failures in raw outputs while selecting from successes`() =
        runBlocking {
            val output1 = TestOutput("winner", 95)
            val output2 = TestOutput("loser", 75)
            coEvery { mockAgenticFunction.invoke(testInput) } returnsMany
                listOf(
                    success(agentOutput(output1)),
                    failure(RuntimeException("Test failure")),
                    success(agentOutput(output2)),
                )

            val evaluator =
                AgenticFunctionEvaluator(
                    AgenticFunctionEvaluator.EvaluationConfig(
                        agenticFunctionFactory = { mockAgenticFunction },
                        numIterations = 3,
                        maxParallelism = 1,
                        selectionStrategy = MetricAgentOutputSelector(SelectionMode.MAXIMIZE) { it.output.score.toDouble() },
                    ),
                )

            val result = evaluator.evaluate(testInput)

            assertEquals(3, result.allOutputs.size)
            assertEquals(1, result.allOutputs.count { it.isFailure })
            assertTrue(result.selectedOutput.isSuccess)
            assertEquals(output1, result.selectedOutput.get().output)
        }

    @Test
    fun `evaluate yields a failed selection when all iterations fail`() =
        runBlocking {
            coEvery { mockAgenticFunction.invoke(testInput) } returns failure(RuntimeException("All failed"))

            val evaluator =
                AgenticFunctionEvaluator(
                    AgenticFunctionEvaluator.EvaluationConfig(
                        agenticFunctionFactory = { mockAgenticFunction },
                        numIterations = 2,
                        maxParallelism = 1,
                        selectionStrategy = MostFrequentAgentOutputSelector(),
                    ),
                )

            val result = evaluator.evaluate(testInput)

            assertEquals(2, result.totalIterations)
            assertEquals(2, result.allOutputs.size)
            assertTrue(result.allOutputs.all { it.isFailure })
            assertTrue(result.selectedOutput.isFailure)
        }

    @Test
    fun `evaluate invokes the progress callback once per iteration`() =
        runBlocking {
            val output = TestOutput("ok", 50)
            coEvery { mockAgenticFunction.invoke(testInput) } returns success(agentOutput(output))

            val progress = mutableListOf<AgenticFunctionEvaluator.IterationProgress<TestOutput>>()
            val evaluator =
                AgenticFunctionEvaluator(
                    AgenticFunctionEvaluator.EvaluationConfig(
                        agenticFunctionFactory = { mockAgenticFunction },
                        numIterations = 3,
                        maxParallelism = 1,
                        selectionStrategy = MostFrequentAgentOutputSelector(),
                        onIterationComplete = { synchronized(progress) { progress.add(it) } },
                    ),
                )

            evaluator.evaluate(testInput)

            assertEquals(3, progress.size)
            assertTrue(progress.all { it.totalIterations == 3 })
            assertEquals(0, progress.last().failuresSoFar)
        }

    @Test
    fun `factory produces a fresh instance per iteration`() =
        runBlocking {
            var factoryCallCount = 0
            val output = TestOutput("factory", 100)

            val evaluator =
                AgenticFunctionEvaluator(
                    AgenticFunctionEvaluator.EvaluationConfig(
                        agenticFunctionFactory = {
                            factoryCallCount++
                            val mock = mockk<Instructible<TestInput, Try<AgentOutput<TestOutput>>>>()
                            coEvery { mock.invoke(any()) } returns success(agentOutput(output))
                            mock
                        },
                        numIterations = 3,
                        maxParallelism = 1,
                        selectionStrategy = MostFrequentAgentOutputSelector(),
                    ),
                )

            val result = evaluator.evaluate(testInput)

            assertEquals(3, factoryCallCount)
            assertEquals(3, result.allOutputs.size)
        }

    // ─── Evaluator: invoke() (Instructible composition) ─────────────────────

    @Test
    fun `invoke returns the selected output for composition`() =
        runBlocking {
            val output1 = TestOutput("trial1", 100)
            val output2 = TestOutput("trial2", 200)
            coEvery { mockAgenticFunction.invoke(testInput) } returnsMany
                listOf(
                    success(agentOutput(output1)),
                    success(agentOutput(output2)),
                    success(agentOutput(output1)), // output1 wins by frequency
                )

            val evaluator =
                AgenticFunctionEvaluator(
                    AgenticFunctionEvaluator.EvaluationConfig(
                        agenticFunctionFactory = { mockAgenticFunction },
                        numIterations = 3,
                        maxParallelism = 1,
                        selectionStrategy = MostFrequentAgentOutputSelector(),
                    ),
                )

            val result = evaluator.invoke(testInput)

            assertTrue(result.isSuccess)
            assertEquals(output1, result.get().output)
        }

    @Test
    fun `invoke propagates a failed selection when all trials fail`() =
        runBlocking {
            coEvery { mockAgenticFunction.invoke(testInput) } returns failure(RuntimeException("boom"))

            val evaluator =
                AgenticFunctionEvaluator(
                    AgenticFunctionEvaluator.EvaluationConfig(
                        agenticFunctionFactory = { mockAgenticFunction },
                        numIterations = 2,
                        maxParallelism = 1,
                        selectionStrategy = MostFrequentAgentOutputSelector(),
                    ),
                )

            val result = evaluator.invoke(testInput)

            assertTrue(result.isFailure)
        }

    // ─── Config validation ──────────────────────────────────────────────────

    @Test
    fun `constructor rejects a non-positive iteration count`() {
        assertThrows(IllegalArgumentException::class.java) {
            AgenticFunctionEvaluator(
                AgenticFunctionEvaluator.EvaluationConfig(
                    agenticFunctionFactory = { mockAgenticFunction },
                    numIterations = 0,
                    maxParallelism = 1,
                    selectionStrategy = MostFrequentAgentOutputSelector<TestOutput>(),
                ),
            )
        }
    }

    @Test
    fun `constructor rejects a non-positive parallelism`() {
        assertFalse(
            runCatching {
                AgenticFunctionEvaluator(
                    AgenticFunctionEvaluator.EvaluationConfig(
                        agenticFunctionFactory = { mockAgenticFunction },
                        numIterations = 1,
                        maxParallelism = 0,
                        selectionStrategy = MostFrequentAgentOutputSelector<TestOutput>(),
                    ),
                )
            }.isSuccess,
        )
    }
}
