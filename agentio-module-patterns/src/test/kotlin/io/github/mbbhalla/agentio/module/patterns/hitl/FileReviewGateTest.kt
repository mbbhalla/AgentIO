package io.github.mbbhalla.agentio.module.patterns.hitl

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.serializer
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FileReviewGateTest {
    @TempDir
    lateinit var dir: Path

    private val unit =
        Question(
            "Q1",
            "Which unit is the amount in?",
            "Amounts look like 1999.\nThe header says Amt.",
            listOf("cents", "dollars"),
            assumedOption = 1,
        )
    private val note = Question("Q2", "What does status X mean?", "Only one row has it", emptyList(), assumedOption = null)
    private val questions =
        ReviewRequest(
            round = 1,
            proposal = "load 7 tables",
            questions = listOf(unit, note),
            decisions = emptyList(),
            feedback = emptyList(),
        )

    private fun gate(directory: Path = dir) =
        FileReviewGate(
            directory = directory,
            proposalSerializer = String.serializer(),
            describe = { "#### Plan\n$it" },
            title = "ETL review",
            howToContinue = "run the ETL again",
        )

    /** Ticks the box whose line starts with [label]. */
    private fun String.tick(label: String): String = replace("- [ ] $label", "- [x] $label")

    /** Writes [text] as feedback. */
    private fun String.feedback(text: String): String = replace(FEEDBACK_PROMPT, "$FEEDBACK_PROMPT$text\n")

    /** Writes the file for [request] in a fresh directory, lets [edit] change it as a person would, then reads the reply. */
    private fun replyAfter(
        request: ReviewRequest<String>,
        edit: (String) -> String,
    ): ReviewReply =
        runBlocking {
            val fresh = Files.createTempDirectory(dir, "review")
            assertEquals(ReviewReply.Pending, gate(fresh).review(request))
            gate(fresh).file.writeText(edit(gate(fresh).file.readText()))
            gate(fresh).review(request)
        }

    @Test
    fun `the file shows questions with their evidence, options and assumption, then the decisions and proposal`() =
        runTest {
            val decided = Decision(1, Question("Q0", "Old question", "", listOf("yes", "no"), 0), Answer.Option(1))
            assertEquals(ReviewReply.Pending, gate().review(questions.copy(round = 2, decisions = listOf(decided))))
            val text = gate().file.readText()
            assertContains(text, "**Round 2: 2 open question(s).**")
            assertContains(text, "### [Q1] Which unit is the amount in?\n\n> Amounts look like 1999.\n> The header says Amt.\n")
            assertContains(text, "- [ ] 1. cents\n- [ ] 2. dollars _(the agent's assumption)_\n\nAnswer: \n")
            assertContains(text, "### [Q2] What does status X mean?")
            assertContains(text, "## Decisions so far\n\n- Round 1 [Q0] Old question **no**")
            assertContains(text, "## Proposal\n\n#### Plan\nload 7 tables")
            assertEquals(2, gate().pending()?.round)
        }

    @Test
    fun `an untouched file is still pending, and a ticked box or written answer is read`() {
        assertEquals(ReviewReply.Pending, replyAfter(questions) { it })
        assertEquals(
            ReviewReply.Revise(mapOf("Q1" to Answer.Option(0), "Q2" to Answer.Written("Archived, treat as inactive"))),
            replyAfter(questions) { it.tick("1. cents").replace("Answer: \n\n## Stop", "Answer: Archived, treat as inactive\n\n## Stop") },
        )
    }

    @Test
    fun `a partly answered file revises with what is answered, and feedback is read`() {
        val reply =
            replyAfter(questions) { it.replace("- [ ] 2. dollars", "- [X] 2. dollars").feedback("Keep the header row.") }
        assertEquals(ReviewReply.Revise(mapOf("Q1" to Answer.Option(1)), feedback = "Keep the header row."), reply)
    }

    @Test
    fun `stop rejects, with the feedback as the reason`() {
        val reply =
            replyAfter(questions) { it.tick("Reject: stop").feedback("Wrong source file.") }
        assertEquals(ReviewReply.Reject("Wrong source file."), reply)
    }

    @Test
    fun `an ambiguous answer names the question and what to fix`() {
        val both = assertThrows<IllegalArgumentException> { replyAfter(questions) { it.tick("1.").tick("2.") } }
        assertContains(both.message.orEmpty(), "question [Q1]: tick one option at most, found 2")
        val mixed =
            assertThrows<IllegalArgumentException> {
                replyAfter(questions) { it.tick("1.").replaceFirst("Answer: \n", "Answer: cents, really\n") }
            }
        assertContains(mixed.message.orEmpty(), "question [Q1]: tick an option or write an answer, not both")
    }

    @Test
    fun `sign-off reads approve, revise with feedback, or reject`() {
        val signOff = questions.copy(questions = emptyList())
        assertEquals(ReviewReply.Pending, replyAfter(signOff) { it })
        assertEquals(ReviewReply.Approve, replyAfter(signOff) { it.tick("Approve") })
        assertEquals(
            ReviewReply.Revise(feedback = "Use the 2014 rates."),
            replyAfter(signOff) {
                it.tick("Revise").feedback("Use the 2014 rates.")
            },
        )
        val noFeedback = assertThrows<IllegalArgumentException> { replyAfter(signOff) { it.tick("Revise") } }
        assertContains(noFeedback.message.orEmpty(), "Revise needs feedback under Feedback")
        assertEquals(ReviewReply.Reject("Rejected by reviewer"), replyAfter(signOff) { it.tick("Reject") })
    }

    @Test
    fun `a new round rewrites the file, and the record of an approval ends the review`() =
        runTest {
            val gate = gate()
            assertEquals(ReviewReply.Pending, gate.review(questions))
            val next = questions.copy(round = 2, questions = emptyList(), decisions = listOf(Decision(1, unit, Answer.Option(0))))
            assertEquals(ReviewReply.Pending, gate.review(next), "a request for another round is written, not read")
            assertContains(gate.file.readText(), "## Sign-off")
            val outcome =
                HumanInTheLoop.Outcome.Approved(
                    next.proposal,
                    next.decisions,
                    listOf(HumanInTheLoop.Round(next, ReviewReply.Approve)),
                )
            gate.record(outcome)
            assertNull(gate.pending())
            assertTrue(gate.finished())
            assertFalse(dir.resolve(FileReviewGate.STATE_FILE_NAME).exists())
            val record = gate.file.readText()
            assertContains(record, "**Approved in round 2.**")
            assertContains(record, "- Round 1 [Q1] Which unit is the amount in? **cents**")
        }

    @Test
    fun `the loop resumes from the file in a new process`() =
        runTest {
            // Asks about the unit until it is decided, then has nothing left to ask.
            val agent = Proposer { round, decisions, _ -> Result.success(Proposal("p$round", listOf(unit).filter { decisions.isEmpty() })) }
            val first = HumanInTheLoop(agent, gate(), 3).run()
            assertIs<HumanInTheLoop.Outcome.AwaitingReview<String>>(first)
            gate().file.writeText(gate().file.readText().tick("1. cents"))

            val second = HumanInTheLoop(agent, gate(), 3).run(resume = gate().pending())
            assertIs<HumanInTheLoop.Outcome.AwaitingReview<String>>(second)
            assertTrue(second.request.isSignOff)
            gate().file.writeText(gate().file.readText().tick("Approve"))

            val third = HumanInTheLoop(agent, gate(), 3).run(resume = gate().pending())
            assertIs<HumanInTheLoop.Outcome.Approved<String>>(third)
            assertEquals("p2", third.proposal)
            assertEquals(listOf("cents"), third.decisions.map { it.answerText })
        }

    companion object {
        private const val FEEDBACK_PROMPT = "Write below this line. -->\n"
    }
}
