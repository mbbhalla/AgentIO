package io.github.mbbhalla.agentio.module.patterns.hitl

import io.github.mbbhalla.agentio.module.patterns.common.PatternAgents
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory

/**
 * Human-in-the-loop pattern. Argument: `scripted` (default) or `interactive`. Scripted mode replays
 * a fixed reviewer (one revision request, then approval) so the run needs no terminal; interactive
 * mode reads the reviewer's decision from standard input.
 */
internal object Runner {
    private const val ADVISOR_AGENT_ID = "hitl-advisor-b8c9d0e1-0001-0001-0001-000000000001"
    private const val MAX_ROUNDS = 3
    private const val MODE_SCRIPTED = "scripted"
    private const val MODE_INTERACTIVE = "interactive"

    private val SCRIPTED_REVIEWER =
        ScriptedReviewGate<RightsizingProposal>(
            listOf(
                ReviewReply.Revise(
                    feedback =
                        "Leave ci-runner unchanged: build load spikes at release time and the 30-day average hides it. " +
                            "Also no terminations this quarter, downsizing only.",
                ),
                ReviewReply.Approve,
            ),
        )

    private val LOG = LoggerFactory.getLogger(Runner::class.java)

    fun run(args: Array<String>) =
        runBlocking {
            val mode = args.getOrElse(0) { MODE_SCRIPTED }
            val gate =
                when (mode) {
                    MODE_SCRIPTED -> SCRIPTED_REVIEWER
                    MODE_INTERACTIVE -> ConsoleReviewGate(::describe)
                    else -> throw IllegalArgumentException("mode must be '$MODE_SCRIPTED' or '$MODE_INTERACTIVE'")
                }
            val fleet = Fleet.SAMPLE

            LOG.info("=== Human-in-the-Loop Pattern: Fleet Right-sizing ({} reviewer) ===", mode)
            LOG.info("Fleet before: {} resources, ${'$'}{}/month", fleet.resources.size, fleet.monthlyCostUsd)

            val advisor =
                RightsizingAdvisorAgenticFunction(
                    agentId = ADVISOR_AGENT_ID,
                    toolsProvider = PatternAgents.inProcessTools("fleet", FleetMcpServer(fleet)),
                )
            val loop =
                HumanInTheLoop(
                    proposer = { round, _, feedback ->
                        advisor
                            .invoke(RightsizingAdvisorAgenticFunction.Input(iteration = round, reviewerFeedback = feedback))
                            .map { Proposal(it.output) }
                            .onSuccess { LOG.info("[HITL] Proposal {}:\n{}", round, describe(it.value)) }
                    },
                    gate = gate,
                    maxRounds = MAX_ROUNDS,
                )

            val outcome = loop.run()

            LOG.info("\n=== {} review round(s) ===", outcome.rounds.size)
            when (outcome) {
                is HumanInTheLoop.Outcome.Approved ->
                    fleet
                        .apply(outcome.proposal)
                        .onSuccess { after ->
                            LOG.info("Applied. Fleet after: {} resources, ${'$'}{}/month", after.resources.size, after.monthlyCostUsd)
                            LOG.info("Monthly saving: ${'$'}{}", fleet.monthlyCostUsd - after.monthlyCostUsd)
                            after.resources.forEach { LOG.info("  {} {} {}", it.id, it.size, it.workload) }
                        }.onFailure { LOG.error("Approved proposal could not be applied, fleet unchanged: {}", it.message) }
                is HumanInTheLoop.Outcome.Rejected -> LOG.info("Rejected, fleet unchanged: {}", outcome.reason)
                is HumanInTheLoop.Outcome.Failed -> LOG.error("Failed, fleet unchanged: {}", outcome.reason)
                is HumanInTheLoop.Outcome.AwaitingReview -> LOG.info("Awaiting the reviewer, fleet unchanged")
            }
        }

    private fun describe(proposal: RightsizingProposal): String =
        (listOf(proposal.summary) + proposal.changes.map { "  ${it.action} ${it.resourceId} — ${it.justification}" }).joinToString("\n")
}

fun main(args: Array<String>) {
    Runner.run(args)
}
