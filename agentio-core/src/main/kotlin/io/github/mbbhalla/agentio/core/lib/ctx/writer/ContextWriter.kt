package io.github.mbbhalla.agentio.core.lib.ctx.writer

import io.github.mbbhalla.agentio.core.lib.Instructible
import io.github.mbbhalla.agentio.core.model.conversation.Conversation

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
