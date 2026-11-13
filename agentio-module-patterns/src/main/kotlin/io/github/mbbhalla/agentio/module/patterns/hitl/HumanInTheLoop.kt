package io.github.mbbhalla.agentio.module.patterns.hitl

import org.slf4j.LoggerFactory

/**
 * Human-in-the-loop pattern: propose, review, then either apply, revise, or stop.
 *
 * The agent can only read; the one consequential step (changing the fleet) runs here and only after
 * [ReviewDecision.Approve]. [ReviewDecision.Revise] feeds the reviewer's words back into the next
 * proposal, bounded by [maxRounds]. [ReviewDecision.Reject] ends the loop with the fleet untouched.
 */
internal class HumanInTheLoop(
    private val advisor: RightsizingAdvisorAgenticFunction,
    private val gate: ApprovalGate,
    private val maxRounds: Int,
) {
    companion object {
        private val LOG = LoggerFactory.getLogger(HumanInTheLoop::class.java)
    }

    data class Round(
        val iteration: Int,
        val proposal: RightsizingProposal,
        val decision: ReviewDecision,
    )

    sealed interface Outcome {
        val rounds: List<Round>

        data class Applied(
            val before: Fleet,
            val after: Fleet,
            override val rounds: List<Round>,
        ) : Outcome

        data class Rejected(
            val reason: String,
            override val rounds: List<Round>,
        ) : Outcome

        data class Failed(
            val reason: String,
            override val rounds: List<Round>,
        ) : Outcome
    }

    suspend fun run(fleet: Fleet): Outcome {
        val rounds = mutableListOf<Round>()
        val feedback = mutableListOf<String>()

        for (iteration in 1..maxRounds) {
            val proposal =
                advisor
                    .invoke(RightsizingAdvisorAgenticFunction.Input(iteration = iteration, reviewerFeedback = feedback))
                    .map { it.output }
                    .getOrElse { error -> return Outcome.Failed("Advisor failed on round $iteration: ${error.message}", rounds) }
            LOG.info("[HITL] Proposal {}: {}", iteration, proposal.summary)
            proposal.changes.forEach { LOG.info("[HITL]   {} {} — {}", it.action, it.resourceId, it.justification) }

            val decision = gate.review(iteration, proposal)
            rounds += Round(iteration = iteration, proposal = proposal, decision = decision)
            LOG.info("[HITL] Reviewer decision: {}", decision)

            when (decision) {
                is ReviewDecision.Approve ->
                    return fleet
                        .apply(proposal)
                        .map { after -> Outcome.Applied(before = fleet, after = after, rounds = rounds) as Outcome }
                        .getOrElse { error -> Outcome.Failed("Approved proposal could not be applied: ${error.message}", rounds) }
                is ReviewDecision.Reject -> return Outcome.Rejected(reason = decision.reason, rounds = rounds)
                is ReviewDecision.Revise -> feedback += decision.feedback
            }
        }
        return Outcome.Rejected(reason = "No approval within $maxRounds rounds", rounds = rounds)
    }
}
