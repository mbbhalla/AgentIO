package io.github.mbbhalla.agentio.module.patterns.hitl

import org.slf4j.LoggerFactory

/**
 * Human-in-the-loop pattern: propose, review, then approve, revise or stop.
 *
 * The agent only proposes; whatever is consequential runs in the caller, and only for
 * [Outcome.Approved]. A proposal comes with the questions the agent could not decide. While any is
 * open the reviewer answers them, and the agent proposes again bound by the answers; once none is open
 * the reviewer signs off the proposal as a whole, or sends feedback for another round. Rounds are
 * bounded by [maxRounds]. A reviewer who is not there yet ([ReviewReply.Pending]) stops the loop with
 * [Outcome.AwaitingReview], whose request [run] resumes later, in this process or another.
 */
class HumanInTheLoop<P>(
    private val proposer: Proposer<P>,
    private val gate: ReviewGate<P>,
    private val maxRounds: Int,
) {
    init {
        require(maxRounds >= 1) { "maxRounds must be at least 1, found $maxRounds" }
    }

    data class Round<P>(
        val request: ReviewRequest<P>,
        val reply: ReviewReply,
    )

    sealed interface Outcome<P> {
        val rounds: List<Round<P>>

        data class Approved<P>(
            val proposal: P,
            val decisions: List<Decision>,
            override val rounds: List<Round<P>>,
        ) : Outcome<P>

        data class Rejected<P>(
            val reason: String,
            override val rounds: List<Round<P>>,
        ) : Outcome<P>

        /** The reviewer has not replied to [request]; pass it to [run] to continue. */
        data class AwaitingReview<P>(
            val request: ReviewRequest<P>,
            override val rounds: List<Round<P>>,
        ) : Outcome<P>

        data class Failed<P>(
            val reason: String,
            override val rounds: List<Round<P>>,
        ) : Outcome<P>
    }

    /** Starts at round 1, or continues from [resume], a request that was awaiting review. */
    suspend fun run(resume: ReviewRequest<P>? = null): Outcome<P> {
        val rounds = mutableListOf<Round<P>>()
        var request: ReviewRequest<P> =
            resume ?: propose(round = 1, decisions = emptyList(), feedback = emptyList()).getOrElse { return failed(1, it, rounds) }
        while (true) {
            val reply = gate.review(request)
            LOG.info("[HITL] Round {}: {}", request.round, describe(reply))
            if (reply == ReviewReply.Pending) return Outcome.AwaitingReview(request, rounds)
            rounds += Round(request, reply)
            when (reply) {
                ReviewReply.Pending -> error("handled above")
                ReviewReply.Approve ->
                    return if (request.isSignOff) {
                        Outcome.Approved(request.proposal, request.decisions, rounds)
                    } else {
                        Outcome.Failed("Round ${request.round}: approval is only possible once no question is open", rounds)
                    }
                is ReviewReply.Reject -> return Outcome.Rejected(reply.reason, rounds)
                is ReviewReply.Revise -> {
                    val answered = decide(request, reply).getOrElse { return failed(request.round, it, rounds) }
                    if (request.round >= maxRounds) return Outcome.Rejected("No approval within $maxRounds rounds", rounds)
                    val decisions = request.decisions + answered
                    val feedback = request.feedback + listOfNotNull(reply.feedback?.takeIf { it.isNotBlank() })
                    request = propose(request.round + 1, decisions, feedback).getOrElse { return failed(request.round + 1, it, rounds) }
                }
            }
        }
    }

    private suspend fun propose(
        round: Int,
        decisions: List<Decision>,
        feedback: List<String>,
    ): Result<ReviewRequest<P>> =
        proposer.propose(round, decisions, feedback).mapCatching { proposal ->
            LOG.info("[HITL] Round {}: proposal with {} open question(s)", round, proposal.questions.size)
            ReviewRequest(round, proposal.value, proposal.questions, decisions, feedback)
        }

    private fun failed(
        round: Int,
        error: Throwable,
        rounds: List<Round<P>>,
    ): Outcome<P> = Outcome.Failed("Round $round: ${error.message}", rounds)

    /** The reviewer's answers as decisions; an answer to a question that is not open is an error. */
    private fun decide(
        request: ReviewRequest<P>,
        reply: ReviewReply.Revise,
    ): Result<List<Decision>> =
        runCatching {
            val open = request.questions.associateBy { it.id }
            reply.answers.map { (id, answer) ->
                val question = requireNotNull(open[id]) { "answer to '$id', which is not an open question; open: ${open.keys}" }
                Decision(request.round, question, answer)
            }
        }

    private fun describe(reply: ReviewReply): String =
        when (reply) {
            ReviewReply.Pending -> "awaiting the reviewer"
            ReviewReply.Approve -> "approved"
            is ReviewReply.Reject -> "rejected: ${reply.reason}"
            is ReviewReply.Revise ->
                "revise with ${reply.answers.size} answer(s)" + if (reply.feedback.isNullOrBlank()) "" else " and feedback"
        }

    companion object {
        private val LOG = LoggerFactory.getLogger(HumanInTheLoop::class.java)
    }
}
