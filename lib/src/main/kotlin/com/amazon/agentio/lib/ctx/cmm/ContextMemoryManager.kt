package com.amazon.agentio.lib.ctx.cmm

import com.amazon.agentio.model.Conversation
import com.amazon.agentio.model.LLM

/**
 * Context Memory Manager (CMM) interface.
 *
 * Context is modeled as Conversation's messages.
 * CMM implementations process and transform context to improve agent accuracy.
 *
 * Examples: compaction, adaptive attention-based reshuffling, summarization, etc.
 *
 * Each CMM decides whether to execute on a given turn via [shouldExecuteOnTurn].
 * This allows chaining multiple CMMs where not all need to run every turn.
 */
interface ContextMemoryManager {

    data class ContextMemoryManagerInput(
        val conversation: Conversation,
        val llm: LLM,
        val turnNumber: Int,
    ) {
        init {
            require(turnNumber >= 0) {
                "turnNumber must be >= 0, was $turnNumber"
            }
        }
    }

    /**
     * Whether this CMM should execute on the given turn number.
     * Default: execute every turn.
     */
    fun shouldExecuteOnTurn(turnNumber: Int): Boolean = true

    /**
     * Process the conversation context and return a (potentially modified) Conversation.
     */
    suspend fun getContext(input: ContextMemoryManagerInput): Conversation
}

/**
 * No-op CMM that passes through the conversation unchanged.
 */
object NoOperationContextMemoryManager : ContextMemoryManager {
    override suspend fun getContext(
        input: ContextMemoryManager.ContextMemoryManagerInput,
    ) = input.conversation
}
