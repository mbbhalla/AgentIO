package io.github.mbbhalla.agentio.module.patterns.hitl

import org.slf4j.LoggerFactory

/** What the reviewer decided. REVISE sends feedback back to the agent; APPROVE and REJECT end the loop. */
sealed interface ReviewDecision {
    data object Approve : ReviewDecision

    data class Reject(
        val reason: String,
    ) : ReviewDecision

    data class Revise(
        val feedback: String,
    ) : ReviewDecision
}

/**
 * The human side of the loop. The agent never executes anything; it hands a proposal to the gate
 * and only an [ReviewDecision.Approve] lets the process apply it. Implementations differ only in
 * where the decision comes from, which is what makes the loop testable and runnable unattended.
 */
fun interface ApprovalGate {
    suspend fun review(
        iteration: Int,
        proposal: RightsizingProposal,
    ): ReviewDecision
}

/** Decisions fixed in advance; used for unattended runs and tests. Once the script is spent, every proposal is rejected. */
class ScriptedApprovalGate(
    decisions: List<ReviewDecision>,
) : ApprovalGate {
    private val remaining = ArrayDeque(decisions)

    override suspend fun review(
        iteration: Int,
        proposal: RightsizingProposal,
    ): ReviewDecision =
        remaining.removeFirstOrNull() ?: ReviewDecision.Reject("Scripted reviewer has no decision left for iteration $iteration")
}

/** Reads the decision from standard input: `a` approve, `r <reason>` reject, anything else is feedback for revision. */
class ConsoleApprovalGate : ApprovalGate {
    private val log = LoggerFactory.getLogger(ConsoleApprovalGate::class.java)

    override suspend fun review(
        iteration: Int,
        proposal: RightsizingProposal,
    ): ReviewDecision {
        log.info("Reviewer: type 'a' to approve, 'r <reason>' to reject, or feedback text to request a revision, then Enter.")
        val line = readlnOrNull()?.trim() ?: return ReviewDecision.Reject("No input available")
        return when {
            line.equals("a", ignoreCase = true) -> ReviewDecision.Approve
            line.startsWith("r ", ignoreCase = true) -> ReviewDecision.Reject(line.drop(2).trim())
            line.equals("r", ignoreCase = true) -> ReviewDecision.Reject("Rejected by reviewer")
            line.isEmpty() -> ReviewDecision.Reject("Empty response treated as rejection")
            else -> ReviewDecision.Revise(line)
        }
    }
}
