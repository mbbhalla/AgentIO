package io.github.mbbhalla.agentio.module.patterns.cascade

import io.github.mbbhalla.agentio.core.lib.AgentOutput
import io.github.mbbhalla.agentio.core.lib.Instructible
import org.slf4j.LoggerFactory

/**
 * Model cascade (resource-aware escalation): try the cheapest tier first and escalate to the next
 * only when the result is rejected. A tier is rejected when its invocation fails (schema or model
 * error) or when its [Tier.accept] check says the output is not good enough. The accept check is
 * where verification lives: self-reported confidence, a deterministic verifier, or both.
 *
 * Cost is visible in the outcome: every attempt carries its token usage, so the saving from
 * answering at a cheap tier, and the price of escalating, can be read off directly.
 */
class ModelCascade<I : Instructible.WithInstruction, O : Any>(
    private val tiers: List<Tier<I, O>>,
) {
    companion object {
        private val LOG = LoggerFactory.getLogger(ModelCascade::class.java)
    }

    init {
        require(tiers.isNotEmpty()) { "A cascade needs at least one tier" }
        require(tiers.map { it.name }.distinct().size == tiers.size) { "Tier names must be unique" }
    }

    data class Tier<I : Instructible.WithInstruction, O : Any>(
        val name: String,
        val agent: Instructible<I, Result<AgentOutput<O>>>,
        /** Decides whether this tier's output is final. Sees the input so a verifier can check the answer against the task. */
        val accept: (I, AgentOutput<O>) -> Boolean,
    )

    data class Attempt<O : Any>(
        val tier: String,
        val result: Result<AgentOutput<O>>,
        val accepted: Boolean,
    ) {
        val inputTokens: Int get() = result.map { it.conversation.tokenUsage.totalInputTokens }.getOrDefault(0)
        val outputTokens: Int get() = result.map { it.conversation.tokenUsage.totalOutputTokens }.getOrDefault(0)
    }

    sealed interface Outcome<O : Any> {
        val attempts: List<Attempt<O>>

        data class Resolved<O : Any>(
            val tier: String,
            val output: AgentOutput<O>,
            override val attempts: List<Attempt<O>>,
        ) : Outcome<O>

        data class Exhausted<O : Any>(
            override val attempts: List<Attempt<O>>,
        ) : Outcome<O>
    }

    suspend fun invoke(input: I): Outcome<O> {
        val attempts = mutableListOf<Attempt<O>>()
        for (tier in tiers) {
            LOG.info("[CASCADE] Trying tier '{}'", tier.name)
            val result = tier.agent.invoke(input)
            val accepted = result.map { tier.accept(input, it) }.getOrDefault(false)
            attempts += Attempt(tier = tier.name, result = result, accepted = accepted)
            if (accepted) {
                LOG.info("[CASCADE] Tier '{}' accepted", tier.name)
                return Outcome.Resolved(tier = tier.name, output = result.getOrThrow(), attempts = attempts)
            }
            LOG.info("[CASCADE] Tier '{}' rejected ({}); escalating", tier.name, result.exceptionOrNull()?.message ?: "verification failed")
        }
        return Outcome.Exhausted(attempts)
    }
}
