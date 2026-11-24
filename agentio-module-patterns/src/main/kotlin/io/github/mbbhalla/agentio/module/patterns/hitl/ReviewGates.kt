package io.github.mbbhalla.agentio.module.patterns.hitl

import org.slf4j.LoggerFactory

/** Replies fixed in advance, for unattended runs and tests. Once the script is spent, every request is rejected. */
class ScriptedReviewGate<P>(
    replies: List<ReviewReply>,
) : ReviewGate<P> {
    private val remaining = ArrayDeque(replies)

    override suspend fun review(request: ReviewRequest<P>): ReviewReply =
        remaining.removeFirstOrNull() ?: ReviewReply.Reject("Scripted reviewer has no reply left for round ${request.round}")
}

/**
 * Asks a person at the terminal. For each open question: an option number, words of their own, Enter to
 * take the agent's assumption (or leave it open when there is none), or `r <reason>` to reject the run.
 * For sign-off: `a` approves, `r <reason>` rejects, anything else is feedback for another round.
 */
class ConsoleReviewGate<P>(
    private val describe: (P) -> String,
    private val readLine: () -> String? = ::readlnOrNull,
    private val show: (String) -> Unit = { LOG.info(it) },
) : ReviewGate<P> {
    override suspend fun review(request: ReviewRequest<P>): ReviewReply {
        show("Round ${request.round} proposal:\n${describe(request.proposal)}")
        return if (request.isSignOff) signOff() else answer(request.questions)
    }

    private fun signOff(): ReviewReply {
        show("No question is open. Type 'a' to approve, 'r <reason>' to reject, or feedback for another round, then Enter.")
        val line = readLine()?.trim() ?: return ReviewReply.Reject("No input available")
        return when {
            line.equals("a", ignoreCase = true) -> ReviewReply.Approve
            line.equals("r", ignoreCase = true) -> ReviewReply.Reject("Rejected by reviewer")
            line.startsWith("r ", ignoreCase = true) -> ReviewReply.Reject(line.drop(2).trim())
            line.isEmpty() -> ReviewReply.Reject("Empty response treated as rejection")
            else -> ReviewReply.Revise(feedback = line)
        }
    }

    private fun answer(questions: List<Question>): ReviewReply {
        val answers = mutableMapOf<String, Answer>()
        questions.forEachIndexed { n, question ->
            show(render(n + 1, questions.size, question))
            val line = readLine()?.trim() ?: return ReviewReply.Reject("No input available")
            when {
                line.startsWith("r ", ignoreCase = true) || line.equals("r", ignoreCase = true) ->
                    return ReviewReply.Reject(line.drop(1).trim().ifEmpty { "Rejected by reviewer" })
                line.isEmpty() -> question.assumedOption?.let { answers[question.id] = Answer.Option(it) }
                line.toIntOrNull()?.let { it - 1 in question.options.indices } == true ->
                    answers[question.id] =
                        Answer.Option(line.toInt() - 1)
                else -> answers[question.id] = Answer.Written(line)
            }
        }
        return if (answers.isEmpty()) ReviewReply.Reject("No question was answered") else ReviewReply.Revise(answers)
    }

    private fun render(
        n: Int,
        of: Int,
        question: Question,
    ): String =
        buildString {
            appendLine("Question $n of $of [${question.id}]: ${question.text}")
            if (question.evidence.isNotBlank()) appendLine(question.evidence)
            question.options.forEachIndexed { i, option ->
                appendLine("  ${i + 1}. $option${if (i == question.assumedOption) "   (the agent's assumption)" else ""}")
            }
            append(
                when {
                    question.options.isEmpty() -> "Write an answer, or 'r <reason>' to reject the run."
                    question.assumedOption != null -> "Option number, your own answer, Enter for the assumption, or 'r <reason>' to reject."
                    else -> "Option number, your own answer, Enter to leave it open, or 'r <reason>' to reject."
                },
            )
        }

    companion object {
        private val LOG = LoggerFactory.getLogger(ConsoleReviewGate::class.java)
    }
}
