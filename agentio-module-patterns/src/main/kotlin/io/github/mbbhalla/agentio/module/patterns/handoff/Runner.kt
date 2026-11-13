package io.github.mbbhalla.agentio.module.patterns.handoff

import io.github.mbbhalla.agentio.module.patterns.common.PatternAgents
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory

/**
 * Handoff pattern. Optional arguments: customerId, then customerMessage. A triage agent takes the case and
 * hands it to specialists, who may hand it onward; the transcript travels with every handoff.
 */
internal object Runner {
    private const val TRIAGE_AGENT_ID = "handoff-triage-f6a7b8c9-0001-0001-0001-000000000001"
    private const val BILLING_AGENT_ID = "handoff-billing-f6a7b8c9-0001-0001-0001-000000000002"
    private const val TECHNICAL_AGENT_ID = "handoff-tech-f6a7b8c9-0001-0001-0001-000000000003"
    private const val MAX_HOPS = 4

    private val POLICY =
        HandoffPolicy(
            mapOf(
                AgentRole.TRIAGE to setOf(AgentRole.BILLING, AgentRole.TECHNICAL),
                AgentRole.BILLING to setOf(AgentRole.TECHNICAL, AgentRole.TRIAGE),
                AgentRole.TECHNICAL to setOf(AgentRole.BILLING, AgentRole.TRIAGE),
            ),
        )

    private val LOG = LoggerFactory.getLogger(Runner::class.java)

    fun run(args: Array<String>) =
        runBlocking {
            val customerId = args.getOrElse(0) { "cust-1001" }
            val customerMessage =
                args.getOrElse(1) {
                    "I was charged twice for November, and since yesterday every call to the analytics API returns 503. Please sort out both."
                }

            LOG.info("=== Handoff Pattern: Customer Support ===")
            LOG.info("Customer {}: {}", customerId, customerMessage)

            val conversation =
                HandoffConversation(
                    agents =
                        mapOf(
                            AgentRole.TRIAGE to
                                SupportAgenticFunction(
                                    agentId = TRIAGE_AGENT_ID,
                                    role = AgentRole.TRIAGE,
                                    expertise =
                                        "You have no tools. Acknowledge the customer briefly and hand off to the agent for the first issue.",
                                ),
                            AgentRole.BILLING to
                                SupportAgenticFunction(
                                    agentId = BILLING_AGENT_ID,
                                    role = AgentRole.BILLING,
                                    expertise = "You resolve charges, invoices and refunds using the billing tools. Quote invoice ids.",
                                    toolsProvider = PatternAgents.inProcessTools("billing", BillingMcpServer()),
                                ),
                            AgentRole.TECHNICAL to
                                SupportAgenticFunction(
                                    agentId = TECHNICAL_AGENT_ID,
                                    role = AgentRole.TECHNICAL,
                                    expertise =
                                        "You diagnose service problems using the status tools. Give the customer a concrete status and ETA.",
                                    toolsProvider = PatternAgents.inProcessTools("technical", TechnicalMcpServer()),
                                ),
                        ),
                    policy = POLICY,
                    entryRole = AgentRole.TRIAGE,
                    maxHops = MAX_HOPS,
                )

            val outcome = conversation.run(customerId = customerId, customerMessage = customerMessage)

            LOG.info("\n=== Transcript ({} hops) ===", outcome.hops.size)
            outcome.hops.forEach { hop ->
                LOG.info("[{}] {}", hop.role, hop.reply)
                if (hop.handoff != HandoffDecision.STAY) LOG.info("    -> {} ({})", hop.handoff, hop.reason)
            }
            when (outcome) {
                is HandoffConversation.Outcome.Resolved -> LOG.info("\n=== Resolved by {} ===", outcome.resolvedBy)
                is HandoffConversation.Outcome.Failed -> LOG.error("\n=== Failed: {} ===", outcome.reason)
            }
        }
}

fun main(args: Array<String>) {
    Runner.run(args)
}
