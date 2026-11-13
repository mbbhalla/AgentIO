package io.github.mbbhalla.agentio.module.patterns.handoff

import org.slf4j.LoggerFactory

/**
 * Handoff (swarm) pattern: control moves from agent to agent, decided by the agent currently
 * working, with the growing transcript carried along. Compare with routing, where a dedicated
 * classifier picks one destination up front and never revisits it, and with orchestration, where
 * a coordinator fans work out and nothing ever "takes over".
 *
 * The code enforces the [HandoffPolicy] and the hop budget; the agents decide when to hand off.
 */
internal class HandoffConversation(
    private val agents: Map<AgentRole, SupportAgenticFunction>,
    private val policy: HandoffPolicy,
    private val entryRole: AgentRole,
    /** Upper bound on agent turns, so two agents cannot pass a case back and forth forever. */
    private val maxHops: Int,
) {
    companion object {
        private val LOG = LoggerFactory.getLogger(HandoffConversation::class.java)
    }

    init {
        require(entryRole in agents) { "No agent registered for entry role $entryRole" }
    }

    /** One agent turn: who spoke, what they said, and where control went next. */
    data class Hop(
        val role: AgentRole,
        val reply: String,
        val handoff: HandoffDecision,
        val reason: String,
    )

    sealed interface Outcome {
        val hops: List<Hop>

        data class Resolved(
            val resolvedBy: AgentRole,
            override val hops: List<Hop>,
        ) : Outcome

        data class Failed(
            val reason: String,
            override val hops: List<Hop>,
        ) : Outcome
    }

    suspend fun run(
        customerId: String,
        customerMessage: String,
    ): Outcome {
        val hops = mutableListOf<Hop>()
        var current = entryRole

        while (hops.size < maxHops) {
            val agent = agents[current] ?: return Outcome.Failed("No agent registered for role $current", hops)
            LOG.info("[HANDOFF] {} takes the case (hop {})", current, hops.size + 1)

            val output =
                agent
                    .invoke(
                        SupportAgenticFunction.Input(
                            customerId = customerId,
                            customerMessage = customerMessage,
                            transcript = hops.map { Turn(role = it.role, message = it.reply) },
                            allowedHandoffs = policy.decisionsFor(current),
                        ),
                    ).map { it.output }
                    .getOrElse { error -> return Outcome.Failed("$current failed: ${error.message}", hops) }

            hops += Hop(role = current, reply = output.reply, handoff = output.handoff, reason = output.handoffReason)
            val target = output.handoff.target() ?: return Outcome.Resolved(resolvedBy = current, hops = hops)

            if (!policy.permits(from = current, to = target)) {
                return Outcome.Failed("$current attempted a handoff to $target that the policy forbids", hops)
            }
            LOG.info("[HANDOFF] {} -> {}: {}", current, target, output.handoffReason)
            current = target
        }
        return Outcome.Failed("Hop budget of $maxHops exhausted without resolution", hops)
    }
}
