package io.github.mbbhalla.agentio.module.patterns.handoff

import io.github.mbbhalla.agentio.core.common.Description
import kotlinx.serialization.Serializable

enum class AgentRole { TRIAGE, BILLING, TECHNICAL }

/**
 * What an agent decides to do after replying. A closed enum rather than a nullable target so the
 * output schema itself rules out malformed handoffs; [target] maps the decision back to a role.
 */
enum class HandoffDecision {
    STAY,
    TO_TRIAGE,
    TO_BILLING,
    TO_TECHNICAL,
    ;

    fun target(): AgentRole? =
        when (this) {
            STAY -> null
            TO_TRIAGE -> AgentRole.TRIAGE
            TO_BILLING -> AgentRole.BILLING
            TO_TECHNICAL -> AgentRole.TECHNICAL
        }

    companion object {
        fun to(role: AgentRole): HandoffDecision =
            when (role) {
                AgentRole.TRIAGE -> TO_TRIAGE
                AgentRole.BILLING -> TO_BILLING
                AgentRole.TECHNICAL -> TO_TECHNICAL
            }
    }
}

/** One message in the shared conversation that travels with every handoff. */
@Serializable
data class Turn(
    @field:Description("Which agent spoke")
    val role: AgentRole,
    @field:Description("What the agent said to the customer")
    val message: String,
)

/** Which roles each role may hand off to. Checked by code, not left to the model. */
data class HandoffPolicy(
    private val allowed: Map<AgentRole, Set<AgentRole>>,
) {
    fun permits(
        from: AgentRole,
        to: AgentRole,
    ): Boolean = to in allowed.getOrDefault(from, emptySet())

    fun decisionsFor(role: AgentRole): List<HandoffDecision> =
        listOf(HandoffDecision.STAY) + allowed.getOrDefault(role, emptySet()).map { HandoffDecision.to(it) }
}
