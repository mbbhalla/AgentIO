package io.github.mbbhalla.agentio.core.lib.ctx.cmm

/**
 * An ordered list of [ContextMemoryManager] implementations.
 *
 * CMMs are executed in order. Each CMM decides whether to run on a given turn
 * via [ContextMemoryManager.shouldExecuteOnTurn]. The output Conversation of
 * one CMM becomes the input to the next.
 *
 * Analogous to [io.github.mbbhalla.agentio.core.lib.ctx.provider.ContextProviders] and
 * [io.github.mbbhalla.agentio.core.lib.ctx.writer.ContextWriters].
 */
data class ContextMemoryManagers(
    val value: List<ContextMemoryManager>,
) {
    /**
     * Execute the CMM chain for a given turn.
     * Each CMM that elects to run on this turn processes the conversation in sequence.
     */
    suspend fun getContext(
        input: ContextMemoryManager.ContextMemoryManagerInput,
    ): io.github.mbbhalla.agentio.core.model.Conversation {
        var conversation = input.conversation
        for (cmm in value) {
            if (cmm.shouldExecuteOnTurn(input.turnNumber)) {
                conversation = cmm.getContext(
                    ContextMemoryManager.ContextMemoryManagerInput(
                        conversation = conversation,
                        llm = input.llm,
                        turnNumber = input.turnNumber,
                    ),
                )
            }
        }
        return conversation
    }
}
