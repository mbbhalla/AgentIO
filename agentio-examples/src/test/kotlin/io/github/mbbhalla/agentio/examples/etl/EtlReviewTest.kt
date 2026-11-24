package io.github.mbbhalla.agentio.examples.etl

import io.github.mbbhalla.agentio.module.patterns.hitl.Answer
import io.github.mbbhalla.agentio.module.patterns.hitl.FileReviewGate
import io.github.mbbhalla.agentio.module.patterns.hitl.HumanInTheLoop
import io.github.mbbhalla.agentio.module.patterns.hitl.ReviewReply
import io.github.mbbhalla.agentio.module.patterns.hitl.ScriptedReviewGate
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** The ETL under human review, with reference agents: questions, answers, a blocked table, sign-off and the review file. */
class EtlReviewTest {
    @TempDir
    lateinit var out: Path

    private val maritalStatus = "How do Divorced and Widowed map to maritalstatus?"
    private val orphanLogin = "adventure-works\\paula0 has events but is not in the roster. What happens to them?"

    /** Reference agents that ask about marital status, and are blocked on the orphan login, until both are decided. */
    private val writerInputs = mutableListOf<EtlSqlWriterAgenticFunction.Input>()
    private val agents: suspend (EtlWorkspace) -> EtlAgents = {
        EtlAgents(AdventureWorks.referencePlanner) {
            SqlWriter { input ->
                writerInputs += input
                val decided = input.decisions.map { it.question }
                val answer = AdventureWorks.referenceWriter.write(input).getOrThrow()
                Result.success(
                    when {
                        input.step.targetTable == "employee" && decided.none { maritalStatus in it } ->
                            answer.copy(questions = listOf(RaisedQuestion(maritalStatus, "Divorced 1, Widowed 1", listOf("S", "M"), 0)))
                        input.step.targetTable == "employeedepartmenthistory" && decided.none { orphanLogin in it } ->
                            answer.copy(
                                selectSql = null,
                                questions = listOf(RaisedQuestion(orphanLogin, "2 events", listOf("Drop them", "Stop the load"), null)),
                            )
                        else -> answer
                    },
                )
            }
        }
    }

    private fun proposer() =
        EtlProposer(
            newWorkspace = { AdventureWorks.workspace() },
            agentsFor = agents,
            artifacts = EtlArtifacts(out, "odcs_example.generic.yaml", AdventureWorks.sources, "reference agents"),
        )

    @Test
    fun `questions are answered, the blocked table loads with the answer, and the mapping is signed off`() {
        val gate =
            ScriptedReviewGate<EtlProposal>(
                listOf(
                    ReviewReply.Revise(mapOf("Q1" to Answer.Option(0), "Q2" to Answer.Option(0))),
                    ReviewReply.Approve,
                ),
            )
        val outcome = runBlocking { HumanInTheLoop(proposer(), gate, maxRounds = 3).run() }
        assertIs<HumanInTheLoop.Outcome.Approved<EtlProposal>>(outcome)
        val first = outcome.rounds.first().request
        assertEquals(listOf("employee: $maritalStatus", "employeedepartmenthistory: $orphanLogin"), first.questions.map { it.text })
        assertEquals("employeedepartmenthistory, whose SQL needs the answers first", first.proposal.stoppedAt)
        assertTrue(outcome.proposal.complete)
        assertEquals(AdventureWorks.expectedRows, outcome.proposal.loaded.associate { it.table to it.rows })
        assertEquals(listOf("S", "Drop them"), outcome.decisions.map { it.answerText })
        assertEquals(
            listOf(
                DecidedQuestion("employee: $maritalStatus", "S"),
                DecidedQuestion("employeedepartmenthistory: $orphanLogin", "Drop them"),
            ),
            writerInputs.last().decisions,
        )
        val script = out.resolve(EtlArtifacts.SCRIPT_FILE).readText()
        assertContains(script, "-- Reviewer decisions:\n--   Round 1 [Q1] employee: $maritalStatus -> S")
    }

    @Test
    fun `a table every answer fails for becomes a written question`() {
        val run =
            EtlRun(
                plan = AdventureWorks.referencePlan,
                loaded = emptyList(),
                questions = listOf(AgentQuestion("plan", AdventureWorks.question("Load jobcandidate?"))),
                blocked = null,
                failure = StepFailure("department", listOf("Binder Error: x".repeat(200), "tool call limit")),
                orphans = emptyMap(),
            )
        val questions = EtlProposer.questionsOf(run)
        assertEquals(listOf("Q1", "Q2"), questions.map { it.id })
        val failure = questions.last()
        assertEquals("department: the agents could not load this table. What should they do differently?", failure.text)
        assertEquals(emptyList(), failure.options, "answered in words")
        assertTrue(failure.evidence.startsWith("Attempt 1: Binder Error") && failure.evidence.contains("\nAttempt 2: tool call limit"))
        assertTrue(
            failure.evidence
                .lines()
                .first()
                .length < 700,
            "long errors are cut",
        )
    }

    @Test
    fun `with the file reviewer each round ends the process, and the next process resumes from the answered file`() {
        fun gate() =
            FileReviewGate(
                out,
                EtlProposal.serializer(),
                EtlProposer::describe,
                title = "AdventureWorks ETL review",
                howToContinue = "run again",
            )

        fun run(): HumanInTheLoop.Outcome<EtlProposal> = runBlocking { HumanInTheLoop(proposer(), gate(), 3).run(gate().pending()) }

        val first = run()
        assertIs<HumanInTheLoop.Outcome.AwaitingReview<EtlProposal>>(first)
        val questions = gate().file.readText()
        assertContains(questions, "### [Q1] employee: $maritalStatus")
        assertContains(questions, "- [ ] 1. S _(the agent's assumption)_")
        assertContains(questions, "**Stopped at employeedepartmenthistory, whose SQL needs the answers first.**")
        gate().file.writeText(questions.replace("- [ ] 1. S", "- [x] 1. S").replace("- [ ] 1. Drop them", "- [x] 1. Drop them"))

        val second = run()
        assertIs<HumanInTheLoop.Outcome.AwaitingReview<EtlProposal>>(second)
        assertTrue(second.request.isSignOff)
        val signOff = gate().file.readText()
        assertContains(signOff, "| 9 | employeedepartmenthistory | org_assignment_events.csv | 19 | 1 |")
        assertContains(signOff, "- Round 1 [Q2] employeedepartmenthistory: $orphanLogin **Drop them**")
        gate().file.writeText(signOff.replace("- [ ] Approve", "- [x] Approve"))

        val calls = writerInputs.size
        val third = run()
        assertIs<HumanInTheLoop.Outcome.Approved<EtlProposal>>(third)
        assertEquals(calls, writerInputs.size, "approval does not run the agents again")
        gate().record(third)
        assertTrue(gate().finished())
        assertContains(gate().file.readText(), "**Approved in round 2.**")
    }
}
