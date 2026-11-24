package io.github.mbbhalla.agentio.module.patterns.hitl

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A decision an agent could not make on its own, put to a human. [options] are the choices; an empty
 * list asks for a written answer. [assumedOption] is the index of the option the agent went ahead with
 * while it waits for the answer, or null when it could not go on without one.
 */
@Serializable
data class Question(
    val id: String,
    val text: String,
    val evidence: String,
    val options: List<String>,
    val assumedOption: Int?,
) {
    init {
        require(id.isNotBlank() && id.none { it.isWhitespace() || it == ']' }) { "A question id is one word without ']': '$id'" }
        require(text.isNotBlank()) { "Question $id has no text" }
        require(options.none { it.isBlank() }) { "Question $id has a blank option" }
        require(options.distinct().size == options.size) { "Question $id repeats an option: $options" }
        require(assumedOption == null || assumedOption in options.indices) {
            "Question $id assumes option index $assumedOption but has ${options.size} options"
        }
    }
}

/** A reviewer's answer to one [Question]: one of its options, by index, or words of their own. */
@Serializable
sealed interface Answer {
    @Serializable
    @SerialName("option")
    data class Option(
        val index: Int,
    ) : Answer

    @Serializable
    @SerialName("written")
    data class Written(
        val text: String,
    ) : Answer {
        init {
            require(text.isNotBlank()) { "A written answer cannot be blank" }
        }
    }
}

/** A question and the answer a reviewer gave in [round]; binding on every later proposal. */
@Serializable
data class Decision(
    val round: Int,
    val question: Question,
    val answer: Answer,
) {
    init {
        if (answer is Answer.Option) {
            require(answer.index in question.options.indices) { "Question ${question.id} has no option ${answer.index + 1}" }
        }
    }

    /** The answer in words: the chosen option's text, or what the reviewer wrote. */
    val answerText: String
        get() =
            when (answer) {
                is Answer.Option -> question.options[answer.index]
                is Answer.Written -> answer.text
            }
}

/**
 * What a reviewer is shown in [round]: the proposal, the questions still open about it, and everything
 * decided before. With no open question it asks for sign-off of the proposal as a whole.
 *
 * It holds everything needed to continue, so a request written down by one process (see
 * [FileReviewGate]) can be resumed by another with [HumanInTheLoop.run].
 */
@Serializable
data class ReviewRequest<P>(
    val round: Int,
    val proposal: P,
    val questions: List<Question>,
    val decisions: List<Decision>,
    val feedback: List<String>,
) {
    init {
        require(round >= 1) { "Rounds count from 1, found $round" }
        val repeated = questions.groupBy { it.id }.filterValues { it.size > 1 }.keys
        require(repeated.isEmpty()) { "Question ids repeat in round $round: $repeated" }
    }

    val isSignOff: Boolean get() = questions.isEmpty()
}

/** What the reviewer decided about a [ReviewRequest]. */
sealed interface ReviewReply {
    /** No reply yet. The loop stops, and the request can be resumed when the reply arrives. */
    data object Pending : ReviewReply

    /** The proposal is accepted as it is. Only a sign-off request can be approved. */
    data object Approve : ReviewReply

    /** Stop; nothing is applied. */
    data class Reject(
        val reason: String,
    ) : ReviewReply

    /** Answers to open questions (by question id), feedback, or both; the agent proposes again with them. */
    data class Revise(
        val answers: Map<String, Answer> = emptyMap(),
        val feedback: String? = null,
    ) : ReviewReply {
        init {
            require(answers.isNotEmpty() || !feedback.isNullOrBlank()) { "A revision needs at least one answer or some feedback" }
        }
    }
}

/**
 * The human side of the loop. Implementations differ only in where the reply comes from: a script
 * ([ScriptedReviewGate]), a terminal ([ConsoleReviewGate]) or a file a person edits ([FileReviewGate]).
 */
fun interface ReviewGate<P> {
    suspend fun review(request: ReviewRequest<P>): ReviewReply
}

/** One proposal, and the questions the agent could not decide while making it. */
data class Proposal<P>(
    val value: P,
    val questions: List<Question> = emptyList(),
)

/** Makes a proposal for [round], bound by every decision and all feedback so far. */
fun interface Proposer<P> {
    suspend fun propose(
        round: Int,
        decisions: List<Decision>,
        feedback: List<String>,
    ): Result<Proposal<P>>
}
