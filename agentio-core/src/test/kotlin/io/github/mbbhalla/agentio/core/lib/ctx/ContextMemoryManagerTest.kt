package io.github.mbbhalla.agentio.core.lib.ctx

import io.github.mbbhalla.agentio.core.lib.ctx.cmm.ContextMemoryManager
import io.github.mbbhalla.agentio.core.lib.ctx.cmm.NoOperationContextMemoryManager
import io.github.mbbhalla.agentio.core.model.Conversation
import io.github.mbbhalla.agentio.core.model.LLM
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

internal class ContextMemoryManagerTest {

    @Test
    fun `ContextMemoryManagerInput should hold conversation, llm, and turnNumber`() {
        val conversation = Conversation.initialize(listOf("test message"))
        val llm = LLM.ANTHROPIC_CLAUDE_OPUS_4_5_V1_CROSS_REGION_INFERENCE

        val input = ContextMemoryManager.ContextMemoryManagerInput(
            conversation = conversation,
            llm = llm,
            turnNumber = 0,
        )

        assertEquals(conversation, input.conversation)
        assertEquals(llm, input.llm)
        assertEquals(0, input.turnNumber)
    }

    @Test
    fun `ContextMemoryManagerInput should reject negative turnNumber`() {
        val conversation = Conversation.initialize(listOf("test"))
        val llm = LLM.ANTHROPIC_CLAUDE_OPUS_4_5_V1_CROSS_REGION_INFERENCE

        assertThrows<IllegalArgumentException> {
            ContextMemoryManager.ContextMemoryManagerInput(
                conversation = conversation,
                llm = llm,
                turnNumber = -1,
            )
        }
    }

    @Test
    fun `NoOperationContextMemoryManager should pass through conversation unchanged`() = runBlocking {
        val conversation = Conversation.initialize(listOf("test message"))
        val llm = LLM.ANTHROPIC_CLAUDE_OPUS_4_5_V1_CROSS_REGION_INFERENCE

        val result = NoOperationContextMemoryManager.getContext(
            ContextMemoryManager.ContextMemoryManagerInput(
                conversation = conversation,
                llm = llm,
                turnNumber = 5,
            ),
        )

        assertEquals(conversation, result)
    }

    @Test
    fun `shouldExecuteOnTurn default should return true for all turns`() {
        assertTrue(NoOperationContextMemoryManager.shouldExecuteOnTurn(0))
        assertTrue(NoOperationContextMemoryManager.shouldExecuteOnTurn(100))
    }

    @Test
    fun `ContextMemoryManagerInput should handle complex conversations`() {
        val conversation = Conversation.initialize(
            listOf(
                "First message",
                "Second message with more context",
                "Third message for testing",
            ),
        )
        val llm = LLM.ANTHROPIC_CLAUDE_OPUS_4_5_V1_CROSS_REGION_INFERENCE

        val input = ContextMemoryManager.ContextMemoryManagerInput(
            conversation = conversation,
            llm = llm,
            turnNumber = 3,
        )

        assertEquals(3, input.conversation.converseMessages().first().content.size)
        assertEquals(llm, input.llm)
        assertEquals(3, input.turnNumber)
    }
}
