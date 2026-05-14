package io.github.mbbhalla.agentio.core.model

/**
 * A [Conversation] paired with its turn number in the agent's execution flow.
 *
 * The agent loop produces a Flow/Sequence of conversations. Each element represents
 * one turn. Rather than tracking the turn number as a mutable counter, we carry it
 * as an immutable val alongside the conversation — the turn number is simply the
 * index of this conversation in the sequence.
 *
 * This enables [io.github.mbbhalla.agentio.core.lib.ctx.cmm.ContextMemoryManager] implementations
 * to make turn-aware decisions (e.g., "only execute every 3rd turn") without requiring
 * mutable state in the agent loop.
 */
data class IndexedConversation(
    val turnNumber: Int,
    val conversation: Conversation,
) {
    init {
        require(turnNumber >= 0) {
            "turnNumber must be >= 0, was $turnNumber"
        }
    }

    /** Produce the next IndexedConversation with turnNumber incremented by 1. */
    fun next(other: IndexedConversation): IndexedConversation =
        IndexedConversation(
            turnNumber = this.turnNumber + 1,
            conversation = other.conversation,
        )
}
