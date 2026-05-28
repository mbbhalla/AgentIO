package io.github.mbbhalla.agentio.core.lib.ctx.provider

import io.github.mbbhalla.agentio.core.lib.Instructible

/**
 * Abstraction for long term memory (LTM) fetching and loading into agent.
 */
interface ContextProvider {
    fun <I : Instructible.WithInstruction> context(input: I): String
}

data class ContextProviders(
    val value: List<ContextProvider>,
)

/**
 * A no-op provider that returns a blank space.
 */
object EmptyContextProvider : ContextProvider {
    override fun <I : Instructible.WithInstruction> context(input: I) = " "
}
