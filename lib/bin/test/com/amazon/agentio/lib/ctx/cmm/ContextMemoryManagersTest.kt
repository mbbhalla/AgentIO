package com.amazon.agentio.lib.ctx.cmm

import com.amazon.agentio.model.Conversation
import com.amazon.agentio.model.LLM
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

internal class ContextMemoryManagersTest {

    private val conversation = Conversation.initialize(listOf("hello"))
    private val llm = LLM.ANTHROPIC_CLAUDE_OPUS_4_5_V1_CROSS_REGION_INFERENCE

    @Test
    fun `empty chain should return original conversation`() = runBlocking {
        val cmms = ContextMemoryManagers(value = emptyList())
        val input = ContextMemoryManager.ContextMemoryManagerInput(
            conversation = conversation,
            llm = llm,
            turnNumber = 0,
        )

        val result = cmms.getContext(input)

        assertSame(conversation, result)
    }

    @Test
    fun `single NoOp CMM should pass through unchanged`() = runBlocking {
        val cmms = ContextMemoryManagers(value = listOf(NoOperationContextMemoryManager))
        val input = ContextMemoryManager.ContextMemoryManagerInput(
            conversation = conversation,
            llm = llm,
            turnNumber = 1,
        )

        val result = cmms.getContext(input)

        assertSame(conversation, result)
    }

    @Test
    fun `CMMs should execute in order, each receiving previous output`() = runBlocking {
        val log = mutableListOf<String>()

        val cmm1 = object : ContextMemoryManager {
            override suspend fun getContext(
                input: ContextMemoryManager.ContextMemoryManagerInput,
            ): Conversation {
                log.add("cmm1")
                return input.conversation
            }
        }
        val cmm2 = object : ContextMemoryManager {
            override suspend fun getContext(
                input: ContextMemoryManager.ContextMemoryManagerInput,
            ): Conversation {
                log.add("cmm2")
                return input.conversation
            }
        }

        val cmms = ContextMemoryManagers(value = listOf(cmm1, cmm2))
        cmms.getContext(
            ContextMemoryManager.ContextMemoryManagerInput(
                conversation = conversation,
                llm = llm,
                turnNumber = 1,
            ),
        )

        assertEquals(listOf("cmm1", "cmm2"), log)
    }

    @Test
    fun `CMM that declines a turn should be skipped`() = runBlocking {
        val log = mutableListOf<String>()

        val everyOtherTurnCmm = object : ContextMemoryManager {
            override fun shouldExecuteOnTurn(turnNumber: Int) = turnNumber % 2 == 0
            override suspend fun getContext(
                input: ContextMemoryManager.ContextMemoryManagerInput,
            ): Conversation {
                log.add("even-turn-cmm")
                return input.conversation
            }
        }
        val alwaysCmm = object : ContextMemoryManager {
            override suspend fun getContext(
                input: ContextMemoryManager.ContextMemoryManagerInput,
            ): Conversation {
                log.add("always-cmm")
                return input.conversation
            }
        }

        val cmms = ContextMemoryManagers(value = listOf(everyOtherTurnCmm, alwaysCmm))

        // Turn 0 (even) — both execute
        cmms.getContext(
            ContextMemoryManager.ContextMemoryManagerInput(conversation = conversation, llm = llm, turnNumber = 0),
        )
        assertEquals(listOf("even-turn-cmm", "always-cmm"), log)

        // Turn 1 (odd) — only alwaysCmm executes
        log.clear()
        cmms.getContext(
            ContextMemoryManager.ContextMemoryManagerInput(conversation = conversation, llm = llm, turnNumber = 1),
        )
        assertEquals(listOf("always-cmm"), log)
    }
}
