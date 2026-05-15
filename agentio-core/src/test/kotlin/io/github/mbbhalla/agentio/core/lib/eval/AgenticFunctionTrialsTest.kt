package io.github.mbbhalla.agentio.core.lib.eval

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
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

internal class AgenticFunctionTrialsTest {

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
        val value: Int,
    )

    private lateinit var mockAgenticFunction: Instructible<TestInput, Try<AgentOutput<TestOutput>>>
    private lateinit var testInput: TestInput

    @BeforeEach
    fun setup() {
        mockAgenticFunction = mockk()
        testInput = TestInput("test-1", "test instruction")
    }

    @Test
    fun `MajorityOccurredAgentOutputSelector should select most frequent output`() = runBlocking {
        // Given
        val output1 = TestOutput("result1", 10)
        val output2 = TestOutput("result2", 20)
        val output3 = TestOutput("result1", 10) // Same as output1

        val agentOutput1 = AgentOutput("test-1", Conversation.initialize(listOf("test")), output1)
        val agentOutput2 = AgentOutput("test-1", Conversation.initialize(listOf("test")), output2)
        val agentOutput3 = AgentOutput("test-1", Conversation.initialize(listOf("test")), output3)

        val agentOutputs = listOf(
            success(agentOutput1),
            success(agentOutput2),
            success(agentOutput3),
        )

        val fallbackSelector = mockk<AgentOutputSelector<TestOutput>>()
        coEvery { fallbackSelector.select(any()) } returns success(agentOutput1)

        val selector = MajorityOccurredAgentOutputSelector(fallbackSelector)

        // When
        val result = selector.select(agentOutputs)

        // Then
        assertTrue(result.isSuccess)
        assertEquals(output1, result.get().output)
    }

    @Test
    fun `MajorityOccurredAgentOutputSelector should handle failures`() = runBlocking {
        // Given
        val output1 = TestOutput("result1", 10)
        val agentOutput1 = AgentOutput("test-1", Conversation.initialize(listOf("test")), output1)

        val agentOutputs = listOf(
            success(agentOutput1),
            failure(RuntimeException("Failed")),
            failure(RuntimeException("Failed again")),
        )

        val fallbackSelector = mockk<AgentOutputSelector<TestOutput>>()
        coEvery { fallbackSelector.select(any()) } returns success(agentOutput1)

        val selector = MajorityOccurredAgentOutputSelector(fallbackSelector)

        // When
        val result = selector.select(agentOutputs)

        // Then
        assertTrue(result.isSuccess)
        assertEquals(output1, result.get().output)
    }

    @Test
    fun `AgenticFunctionTrials should run multiple trials and select result`() = runBlocking {
        // Given
        val output1 = TestOutput("trial1", 100)
        val output2 = TestOutput("trial2", 200)
        val agentOutput1 = AgentOutput("test-1", Conversation.initialize(listOf("test")), output1)
        val agentOutput2 = AgentOutput("test-1", Conversation.initialize(listOf("test")), output2)

        coEvery { mockAgenticFunction.invoke(testInput) } returnsMany listOf(
            success(agentOutput1),
            success(agentOutput2),
            success(agentOutput1), // Same as first, should be selected by majority
        )

        val selector = mockk<AgentOutputSelector<TestOutput>>()
        coEvery { selector.select(any()) } returns success(agentOutput1)

        val trials = AgenticFunctionTrials(
            agenticFunction = mockAgenticFunction,
            numberOfTrials = 3,
            agentOutputSelector = selector,
        )

        // When
        val result = trials.invoke(testInput)

        // Then
        assertTrue(result.isSuccess)
        assertEquals(output1, result.get().output)
    }

    @Test
    fun `AgenticFunctionTrials should handle single trial`() = runBlocking {
        // Given
        val output = TestOutput("single", 42)
        val agentOutput = AgentOutput("test-1", Conversation.initialize(listOf("test")), output)

        coEvery { mockAgenticFunction.invoke(testInput) } returns success(agentOutput)

        val selector = mockk<AgentOutputSelector<TestOutput>>()
        coEvery { selector.select(any()) } returns success(agentOutput)

        val trials = AgenticFunctionTrials(
            agenticFunction = mockAgenticFunction,
            numberOfTrials = 1,
            agentOutputSelector = selector,
        )

        // When
        val result = trials.invoke(testInput)

        // Then
        assertTrue(result.isSuccess)
        assertEquals(output, result.get().output)
    }

    @Test
    fun `AgenticFunctionTrials should handle all failures`() = runBlocking {
        // Given
        val exception = RuntimeException("All trials failed")
        coEvery { mockAgenticFunction.invoke(testInput) } returns failure(exception)

        val selector = mockk<AgentOutputSelector<TestOutput>>()
        coEvery { selector.select(any()) } returns failure(exception)

        val trials = AgenticFunctionTrials(
            agenticFunction = mockAgenticFunction,
            numberOfTrials = 2,
            agentOutputSelector = selector,
        )

        // When
        val result = trials.invoke(testInput)

        // Then
        assertTrue(result.isFailure)
        assertTrue(result.cause is RuntimeException)
    }
}
