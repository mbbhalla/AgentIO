package io.github.mbbhalla.agentio.module.patterns.handoff

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HandoffPolicyTest {
    private val policy =
        HandoffPolicy(
            mapOf(
                AgentRole.TRIAGE to setOf(AgentRole.BILLING, AgentRole.TECHNICAL),
                AgentRole.BILLING to setOf(AgentRole.TECHNICAL),
            ),
        )

    @Test
    fun permits_onlyConfiguredTransitions() {
        assertTrue(policy.permits(AgentRole.TRIAGE, AgentRole.BILLING))
        assertTrue(policy.permits(AgentRole.BILLING, AgentRole.TECHNICAL))
        assertFalse(policy.permits(AgentRole.BILLING, AgentRole.TRIAGE))
        assertFalse(policy.permits(AgentRole.TECHNICAL, AgentRole.BILLING))
    }

    @Test
    fun decisionsFor_alwaysIncludesStayPlusAllowedTargets() {
        assertEquals(listOf(HandoffDecision.STAY, HandoffDecision.TO_TECHNICAL), policy.decisionsFor(AgentRole.BILLING))
        assertEquals(listOf(HandoffDecision.STAY), policy.decisionsFor(AgentRole.TECHNICAL))
    }

    @Test
    fun handoffDecision_roundTripsWithRole() {
        AgentRole.entries.forEach { role -> assertEquals(role, HandoffDecision.to(role).target()) }
        assertNull(HandoffDecision.STAY.target())
    }
}
