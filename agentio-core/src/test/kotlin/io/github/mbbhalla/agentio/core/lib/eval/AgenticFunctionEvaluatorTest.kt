package io.github.mbbhalla.agentio.core.lib.eval

import io.github.mbbhalla.agentio.core.lib.AbstractAgenticFunction
import io.github.mbbhalla.agentio.core.lib.AgentOutput
import io.github.mbbhalla.agentio.core.lib.Instructible
import io.github.mbbhalla.agentio.core.model.conversation.Conversation
import io.mockk.coEvery
import io.mockk.mockk
import io.vavr.kotlin.failure
import io.vavr.kotlin.success
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import org.junit.jupiter.api.Assertions.assertEquals
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

    private lateinit var mockAgenticFunction: AbstractAgenticFunction<TestInput, TestOutput>
    private lateinit var testInput: TestInput

    @BeforeEach
    fun setup() {
        mockAgenticFunction = mockk()
        testInput = TestInput("test-1", "evaluate this")
    }

    @Test
    fun `should evaluate successfully with all matching results using withFunction`() = runBlocking {
        // Given
        val successOutput = TestOutput("success", 95)
        val agentOutput = AgentOutput(
            instructionId = "test-1",
            conversation = Conversation.initialize(listOf("test")),
            output = successOutput,
        )

        coEvery { mockAgenticFunction.invoke(testInput) } returns success(agentOutput)

        val evaluationInput = AgenticFunctionEvaluator.EvaluationInput.withFunction(
            agenticFunction = mockAgenticFunction,
            input = testInput,
            numIterations = 3,
            maxParallelism = 2,
            outputMatcher = { it.score >= 90 },
        )

        val evaluator = AgenticFunctionEvaluator(evaluationInput)

        // When
        val result = evaluator.evaluate()

        // Then
        assertEquals(3, result.totalIterations)
        assertEquals(0, result.failures.size)
        assertEquals(3, result.successResults.size)
        assertEquals(3, result.successAndMatchIterations)
        assertTrue(result.successResults.all { it.score >= 90 })
        assertEquals(3, result.rawOutputs.size)
        assertTrue(result.rawOutputs.all { it.isSuccess })
        assertEquals("test-1", result.rawOutputs.first().get().instructionId)
    }

    @Test
    fun `should handle partial failures and matches`() = runBlocking {
        // Given
        val successOutput1 = TestOutput("success", 95)
        val successOutput2 = TestOutput("partial", 75)
        val agentOutput1 = AgentOutput(
            instructionId = "test-1",
            conversation = Conversation.initialize(listOf("test")),
            output = successOutput1,
        )
        val agentOutput2 = AgentOutput(
            instructionId = "test-1",
            conversation = Conversation.initialize(listOf("test")),
            output = successOutput2,
        )

        coEvery { mockAgenticFunction.invoke(testInput) } returnsMany listOf(
            success(agentOutput1),
            failure(RuntimeException("Test failure")),
            success(agentOutput2),
        )

        val evaluationInput = AgenticFunctionEvaluator.EvaluationInput.withFunction(
            agenticFunction = mockAgenticFunction,
            input = testInput,
            numIterations = 3,
            maxParallelism = 1,
            outputMatcher = { it.score >= 90 },
        )

        val evaluator = AgenticFunctionEvaluator(evaluationInput)

        // When
        val result = evaluator.evaluate()

        // Then
        assertEquals(3, result.totalIterations)
        assertEquals(1, result.failures.size)
        assertEquals(2, result.successResults.size)
        assertEquals(1, result.successAndMatchIterations) // Only first result matches score >= 90
        assertTrue(result.failures.first() is RuntimeException)
        assertEquals(3, result.rawOutputs.size)
        assertEquals(1, result.rawOutputs.count { it.isFailure })
    }

    @Test
    fun `should handle all failures`() = runBlocking {
        // Given
        val exception = RuntimeException("All failed")
        coEvery { mockAgenticFunction.invoke(testInput) } returns failure(exception)

        val evaluationInput = AgenticFunctionEvaluator.EvaluationInput.withFunction(
            agenticFunction = mockAgenticFunction,
            input = testInput,
            numIterations = 2,
            maxParallelism = 1,
            outputMatcher = { it.score >= 90 },
        )

        val evaluator = AgenticFunctionEvaluator(evaluationInput)

        // When
        val result = evaluator.evaluate()

        // Then
        assertEquals(2, result.totalIterations)
        assertEquals(2, result.failures.size)
        assertEquals(0, result.successResults.size)
        assertEquals(0, result.successAndMatchIterations)
        assertTrue(result.failures.all { it is RuntimeException })
        assertEquals(2, result.rawOutputs.size)
        assertTrue(result.rawOutputs.all { it.isFailure })
    }

    @Test
    fun `should work with single iteration`() = runBlocking {
        // Given
        val successOutput = TestOutput("single", 100)
        val agentOutput = AgentOutput(
            instructionId = "test-1",
            conversation = Conversation.initialize(listOf("test")),
            output = successOutput,
        )

        coEvery { mockAgenticFunction.invoke(testInput) } returns success(agentOutput)

        val evaluationInput = AgenticFunctionEvaluator.EvaluationInput.withFunction(
            agenticFunction = mockAgenticFunction,
            input = testInput,
            numIterations = 1,
            maxParallelism = 1,
            outputMatcher = { it.result == "single" },
        )

        val evaluator = AgenticFunctionEvaluator(evaluationInput)

        // When
        val result = evaluator.evaluate()

        // Then
        assertEquals(1, result.totalIterations)
        assertEquals(0, result.failures.size)
        assertEquals(1, result.successResults.size)
        assertEquals(1, result.successAndMatchIterations)
        assertEquals("single", result.successResults.first().result)
        assertEquals(1, result.rawOutputs.size)
        assertTrue(result.rawOutputs.first().isSuccess)
    }

    @Test
    fun `should produce fresh instance per iteration when using withFactory`() = runBlocking {
        // Given — factory that creates a new mock each time, tracking call count
        var factoryCallCount = 0
        val successOutput = TestOutput("factory", 100)
        val agentOutput = AgentOutput(
            instructionId = "test-1",
            conversation = Conversation.initialize(listOf("test")),
            output = successOutput,
        )

        val evaluationInput = AgenticFunctionEvaluator.EvaluationInput.withFactory(
            agenticFunctionFactory = {
                factoryCallCount++
                val mock = mockk<AbstractAgenticFunction<TestInput, TestOutput>>()
                coEvery { mock.invoke(any()) } returns success(agentOutput)
                mock
            },
            input = testInput,
            numIterations = 3,
            maxParallelism = 1,
            outputMatcher = { it.result == "factory" },
        )

        val evaluator = AgenticFunctionEvaluator(evaluationInput)

        // When
        val result = evaluator.evaluate()

        // Then — factory was called once per iteration
        assertEquals(3, factoryCallCount)
        assertEquals(3, result.totalIterations)
        assertEquals(3, result.successAndMatchIterations)
        assertEquals(3, result.rawOutputs.size)
    }
}
