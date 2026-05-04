package com.amazon.agentio.lib.ctx.writer

import com.amazon.agentio.lib.Instructible
import com.amazon.agentio.model.Conversation

/**
 * Abstraction for persisting context into long term memory (LTM) after the agent
 * is done its processing.
 */
interface ContextWriter {
    fun <I : Instructible.WithInstruction> write(
        input: I,
        conversation: Conversation,
    )
}

data class ContextWriters(
    val value: Set<ContextWriter>,
)

object NoOpContextWriter : ContextWriter {
    override fun <I : Instructible.WithInstruction> write(
        input: I,
        conversation: Conversation,
    ) = Unit
}
