package io.github.mbbhalla.agentio.module.patterns.hitl

import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * A reviewer who answers by editing a file, possibly days later. [review] writes `SYSTEM_INPUT.md`, which
 * a person fills in (tick a box, or write an answer) and returns [ReviewReply.Pending]; the process can then
 * end. A later process resumes with [pending], and [review] reads the person's reply from the same file.
 *
 * `SYSTEM_INPUT.state.json` beside it holds the request exactly as the loop needs it; the person edits only
 * the Markdown. After the loop ends, [record] turns the Markdown into the record of every decision.
 *
 * @param describe the proposal as Markdown, shown below the questions; headings in it should be level 3 or deeper
 * @param howToContinue what the person does after answering, such as the command that reruns the process
 */
class FileReviewGate<P>(
    directory: Path,
    proposalSerializer: KSerializer<P>,
    private val describe: (P) -> String,
    private val title: String,
    private val howToContinue: String,
) : ReviewGate<P> {
    private val directory: Path = directory.toAbsolutePath().normalize()
    val file: Path = this.directory.resolve(FILE_NAME)
    private val stateFile: Path = this.directory.resolve(STATE_FILE_NAME)
    private val requestSerializer = ReviewRequest.serializer(proposalSerializer)

    /** The request an earlier process left awaiting a reply, or null when none is. */
    fun pending(): ReviewRequest<P>? = if (stateFile.exists()) JSON.decodeFromString(requestSerializer, stateFile.readText()) else null

    /** Whether [file] holds the record of a review that has ended. */
    fun finished(): Boolean = file.exists() && !stateFile.exists()

    /**
     * The person's reply when [file] was written for [request]; otherwise writes it for [request] and returns
     * [ReviewReply.Pending], as it does while the person has not answered anything.
     *
     * @throws IllegalArgumentException when the file holds a reply that cannot be read, naming what to fix
     */
    override suspend fun review(request: ReviewRequest<P>): ReviewReply {
        val written = pending()
        if (written != null &&
            file.exists() &&
            written.round == request.round &&
            written.questions.map { it.id } == request.questions.map { it.id }
        ) {
            return parse(request, file.readText())
        }
        directory.createDirectories()
        stateFile.writeText(JSON.encodeToString(requestSerializer, request))
        file.writeText(render(request))
        return ReviewReply.Pending
    }

    /** Replaces [file] with the record of how the review ended, and forgets the pending request. */
    fun record(outcome: HumanInTheLoop.Outcome<P>) {
        val last = outcome.rounds.lastOrNull()
        val decisions =
            when (outcome) {
                is HumanInTheLoop.Outcome.Approved -> outcome.decisions
                else -> last?.let { it.request.decisions + answered(it) }.orEmpty()
            }
        val status =
            when (outcome) {
                is HumanInTheLoop.Outcome.Approved -> "**Approved in round ${last?.request?.round}.**"
                is HumanInTheLoop.Outcome.Rejected -> "**Rejected${last?.let {
                    " in round ${it.request.round}"
                }.orEmpty()}:** ${outcome.reason}"
                is HumanInTheLoop.Outcome.Failed -> "**Failed:** ${outcome.reason}"
                is HumanInTheLoop.Outcome.AwaitingReview -> throw IllegalArgumentException("A review awaiting a reply has not ended")
            }
        directory.createDirectories()
        file.writeText(
            buildString {
                appendLine("# $title")
                appendLine()
                appendLine(status)
                appendLine()
                appendDecisions(decisions, heading = "Decisions")
                if (outcome is HumanInTheLoop.Outcome.Approved) {
                    appendLine("## Proposal")
                    appendLine()
                    appendLine(describe(outcome.proposal).trim())
                }
            },
        )
        stateFile.deleteIfExists()
    }

    private fun answered(round: HumanInTheLoop.Round<P>): List<Decision> {
        val reply = round.reply as? ReviewReply.Revise ?: return emptyList()
        val questions = round.request.questions.associateBy { it.id }
        return reply.answers.mapNotNull { (id, answer) -> questions[id]?.let { Decision(round.request.round, it, answer) } }
    }

    // ---- writing -------------------------------------------------------------------------------------------

    private fun render(request: ReviewRequest<P>): String =
        buildString {
            appendLine("# $title")
            appendLine()
            appendLine("<!-- Edit only the boxes, the Answer lines and the Feedback section. -->")
            if (request.isSignOff) {
                appendLine("**Round ${request.round}: no question is open.** Review the proposal below, tick one box, then $howToContinue.")
                appendLine()
                appendLine("## $SIGN_OFF")
                appendLine()
                appendLine("- [ ] $APPROVE")
                appendLine("- [ ] $REVISE: the agents propose again with the feedback below")
                appendLine("- [ ] $REJECT: stop without approving anything; give the reason under Feedback")
            } else {
                appendLine(
                    "**Round ${request.round}: ${request.questions.size} open question(s).** For each, tick one box with `[x]` or " +
                        "write an answer after `Answer:`, then $howToContinue. A question left untouched stays open.",
                )
                appendLine()
                appendLine("## $QUESTIONS")
                request.questions.forEach { appendQuestion(it) }
                appendLine()
                appendLine("## $STOP")
                appendLine()
                appendLine("- [ ] $REJECT: stop without approving anything; give the reason under Feedback")
            }
            appendLine()
            appendLine("## $FEEDBACK")
            appendLine()
            appendLine("<!-- Optional: anything else the agents should change. Write below this line. -->")
            appendLine()
            appendLine()
            appendDecisions(request.decisions, heading = DECISIONS)
            appendLine("## $PROPOSAL")
            appendLine()
            appendLine(describe(request.proposal).trim())
        }

    private fun StringBuilder.appendQuestion(question: Question) {
        appendLine()
        appendLine("### [${question.id}] ${question.text.oneLine()}")
        appendLine()
        question.evidence
            .trim()
            .lines()
            .forEach { appendLine("> $it".trimEnd()) }
        appendLine()
        question.options.forEachIndexed { i, option ->
            appendLine("- [ ] ${i + 1}. ${option.oneLine()}${if (i == question.assumedOption) " _(the agent's assumption)_" else ""}")
        }
        if (question.options.isNotEmpty()) appendLine()
        appendLine("$ANSWER ")
    }

    private fun StringBuilder.appendDecisions(
        decisions: List<Decision>,
        heading: String,
    ) {
        appendLine("## $heading")
        appendLine()
        if (decisions.isEmpty()) appendLine("None yet.")
        decisions.forEach {
            appendLine(
                "- Round ${it.round} [${it.question.id}] ${it.question.text.oneLine()} **${it.answerText.oneLine()}**",
            )
        }
        appendLine()
    }

    // ---- reading -------------------------------------------------------------------------------------------

    private fun parse(
        request: ReviewRequest<P>,
        text: String,
    ): ReviewReply {
        val sections = sections(text)
        val feedback =
            sections[FEEDBACK]
                .orEmpty()
                .filterNot { it.isComment() }
                .joinToString("\n")
                .trim()
                .ifEmpty { null }
        return if (request.isSignOff) {
            parseSignOff(boxes(sections[SIGN_OFF].orEmpty()), feedback)
        } else {
            if (boxes(sections[STOP].orEmpty()).any { it.ticked && it.label.startsWith(REJECT) }) {
                return ReviewReply.Reject(feedback ?: "Rejected by reviewer")
            }
            val answers = parseAnswers(request.questions, sections[QUESTIONS].orEmpty())
            if (answers.isEmpty() && feedback == null) ReviewReply.Pending else ReviewReply.Revise(answers, feedback)
        }
    }

    private fun parseSignOff(
        boxes: List<Box>,
        feedback: String?,
    ): ReviewReply {
        val ticked = boxes.filter { it.ticked }
        require(ticked.size <= 1) { "$file: tick one box under Sign-off, found ${ticked.size}" }
        val choice = ticked.singleOrNull()?.label ?: return ReviewReply.Pending
        return when {
            choice.startsWith(APPROVE) -> ReviewReply.Approve
            choice.startsWith(REVISE) ->
                ReviewReply.Revise(feedback = requireNotNull(feedback) { "$file: Revise needs feedback under Feedback" })
            choice.startsWith(REJECT) -> ReviewReply.Reject(feedback ?: "Rejected by reviewer")
            else -> throw IllegalArgumentException("$file: unknown sign-off choice '$choice'")
        }
    }

    private fun parseAnswers(
        questions: List<Question>,
        lines: List<String>,
    ): Map<String, Answer> {
        val byId = questions.associateBy { it.id }
        return questionBlocks(lines)
            .mapNotNull { (id, block) ->
                val question = requireNotNull(byId[id]) { "$file: [$id] is not a question of this round; the questions are ${byId.keys}" }
                parseAnswer(question, block)?.let { id to it }
            }.toMap()
    }

    private fun parseAnswer(
        question: Question,
        block: List<String>,
    ): Answer? {
        val ticked = boxes(block).filter { it.ticked }
        val answerAt = block.indexOfFirst { it.trimStart().startsWith(ANSWER) }
        val written =
            if (answerAt < 0) {
                ""
            } else {
                (listOf(block[answerAt].trimStart().removePrefix(ANSWER)) + block.drop(answerAt + 1))
                    .filterNot { it.isComment() || it.trimStart().startsWith(">") }
                    .joinToString("\n")
                    .trim()
            }
        val where = "$file: question [${question.id}]"
        require(ticked.size <= 1) { "$where: tick one option at most, found ${ticked.size}" }
        require(ticked.isEmpty() || written.isEmpty()) { "$where: tick an option or write an answer, not both" }
        ticked.singleOrNull()?.let { box ->
            val index =
                requireNotNull(
                    OPTION_NUMBER
                        .find(box.label)
                        ?.groupValues
                        ?.get(1)
                        ?.toInt(),
                ) {
                    "$where: cannot read the option number of '${box.label}'"
                } - 1
            require(index in question.options.indices) { "$where: there is no option ${index + 1}" }
            return Answer.Option(index)
        }
        return written.takeIf { it.isNotEmpty() }?.let { Answer.Written(it) }
    }

    /** Level-2 sections by heading, up to the decisions and proposal, which are for reading only. */
    private fun sections(text: String): Map<String, List<String>> {
        val sections = mutableMapOf<String, MutableList<String>>()
        var current: MutableList<String>? = null
        for (line in text.lines()) {
            if (line.startsWith("## ")) {
                val heading = line.removePrefix("## ").trim()
                if (heading == DECISIONS || heading == PROPOSAL) break
                current = sections.getOrPut(heading) { mutableListOf() }
            } else {
                current?.add(line)
            }
        }
        return sections
    }

    private fun questionBlocks(lines: List<String>): List<Pair<String, List<String>>> {
        val blocks = mutableListOf<Pair<String, MutableList<String>>>()
        lines.forEach { line ->
            val heading = QUESTION_HEADING.find(line)
            if (heading != null) blocks += heading.groupValues[1] to mutableListOf() else blocks.lastOrNull()?.second?.add(line)
        }
        return blocks
    }

    private data class Box(
        val ticked: Boolean,
        val label: String,
    )

    private fun boxes(lines: List<String>): List<Box> =
        lines.mapNotNull { CHECKBOX.find(it) }.map { Box(ticked = it.groupValues[1].isNotBlank(), label = it.groupValues[2].trim()) }

    private fun String.isComment(): Boolean = trim().let { it.startsWith("<!--") && it.endsWith("-->") }

    private fun String.oneLine(): String = lines().joinToString(" ") { it.trim() }.trim()

    companion object {
        const val FILE_NAME = "SYSTEM_INPUT.md"
        const val STATE_FILE_NAME = "SYSTEM_INPUT.state.json"
        private const val QUESTIONS = "Questions"
        private const val SIGN_OFF = "Sign-off"
        private const val STOP = "Stop"
        private const val FEEDBACK = "Feedback"
        private const val DECISIONS = "Decisions so far"
        private const val PROPOSAL = "Proposal"
        private const val APPROVE = "Approve"
        private const val REVISE = "Revise"
        private const val REJECT = "Reject"
        private const val ANSWER = "Answer:"
        private val CHECKBOX = Regex("""^\s*[-*]\s*\[([ xX]?)]\s*(.*)$""")
        private val QUESTION_HEADING = Regex("""^###\s+\[([^\]]+)]""")
        private val OPTION_NUMBER = Regex("""^(\d+)\.""")
        private val JSON =
            Json {
                prettyPrint = true
                classDiscriminator = "kind"
            }
    }
}
