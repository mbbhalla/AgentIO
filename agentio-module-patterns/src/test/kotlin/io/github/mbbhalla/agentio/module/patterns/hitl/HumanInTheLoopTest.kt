package io.github.mbbhalla.agentio.module.patterns.hitl

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class HumanInTheLoopTest {
    private val unit =
        Question("unit", "Which unit is the amount in?", "The file says nothing", listOf("cents", "dollars"), assumedOption = 1)
    private val id = Question("id", "Which column identifies a row?", "", listOf("A", "B"), assumedOption = null)

    /** Records what each round was proposed with, and asks [questions] until every one is decided. */
    private class Agent(
        private val questions: List<Question>,
    ) : Proposer<String> {
        val calls = mutableListOf<Triple<Int, List<Decision>, List<String>>>()

        override suspend fun propose(
            round: Int,
            decisions: List<Decision>,
            feedback: List<String>,
        ): Result<Proposal<String>> {
            calls += Triple(round, decisions, feedback)
            val decided = decisions.map { it.question.id }.toSet()
            return Result.success(Proposal("proposal $round", questions.filterNot { it.id in decided }))
        }
    }

    @Test
    fun `answers bind the next round, and sign-off is asked once no question is open`() =
        runTest {
            val agent = Agent(listOf(unit, id))
            val gate =
                ScriptedReviewGate<String>(
                    listOf(
                        ReviewReply.Revise(mapOf("unit" to Answer.Option(0))),
                        ReviewReply.Revise(mapOf("id" to Answer.Written("Neither: use C"))),
                        ReviewReply.Approve,
                    ),
                )
            val outcome = HumanInTheLoop(agent, gate, maxRounds = 5).run()
            assertIs<HumanInTheLoop.Outcome.Approved<String>>(outcome)
            assertEquals("proposal 3", outcome.proposal)
            assertEquals(listOf("cents", "Neither: use C"), outcome.decisions.map { it.answerText })
            assertEquals(listOf(1, 2), outcome.decisions.map { it.round })
            assertEquals(listOf(listOf(unit, id), listOf(id), emptyList()), outcome.rounds.map { it.request.questions })
            assertEquals(listOf(0, 1, 2), agent.calls.map { it.second.size })
        }

    @Test
    fun `sign-off feedback starts another round, and approval of open questions fails`() =
        runTest {
            val agent = Agent(emptyList())
            val revised =
                HumanInTheLoop(agent, ScriptedReviewGate(listOf(ReviewReply.Revise(feedback = "smaller"), ReviewReply.Approve)), 3).run()
            assertIs<HumanInTheLoop.Outcome.Approved<String>>(revised)
            assertEquals(listOf("smaller"), agent.calls.last().third)

            val early = HumanInTheLoop(Agent(listOf(unit)), ScriptedReviewGate(listOf(ReviewReply.Approve)), 3).run()
            assertIs<HumanInTheLoop.Outcome.Failed<String>>(early)
            assertTrue("approval is only possible once no question is open" in early.reason)
        }

    @Test
    fun `an answer to a question that is not open fails, and rounds are bounded`() =
        runTest {
            val stray =
                HumanInTheLoop(
                    Agent(listOf(unit)),
                    ScriptedReviewGate(listOf(ReviewReply.Revise(mapOf("x" to Answer.Option(0))))),
                    3,
                ).run()
            assertIs<HumanInTheLoop.Outcome.Failed<String>>(stray)
            assertTrue("answer to 'x', which is not an open question" in stray.reason)

            val endless = ScriptedReviewGate<String>(List(5) { ReviewReply.Revise(feedback = "again") })
            val bounded = HumanInTheLoop(Agent(emptyList()), endless, maxRounds = 2).run()
            assertEquals(HumanInTheLoop.Outcome.Rejected("No approval within 2 rounds", bounded.rounds), bounded)
            assertEquals(2, bounded.rounds.size)
        }

    @Test
    fun `a pending reviewer stops the loop, and the request resumes it without proposing again`() =
        runTest {
            val agent = Agent(listOf(unit))
            val waiting = HumanInTheLoop(agent, ScriptedReviewGate(listOf(ReviewReply.Pending)), 3).run()
            assertIs<HumanInTheLoop.Outcome.AwaitingReview<String>>(waiting)
            assertEquals(1, agent.calls.size)

            val resumed =
                HumanInTheLoop(
                    agent,
                    ScriptedReviewGate(listOf(ReviewReply.Revise(mapOf("unit" to Answer.Option(1))), ReviewReply.Approve)),
                    3,
                ).run(resume = waiting.request)
            assertIs<HumanInTheLoop.Outcome.Approved<String>>(resumed)
            assertEquals(listOf(1, 2), agent.calls.map { it.first }, "round 1 is not proposed again")
        }

    @Test
    fun `a failed proposal and a rejection end the loop`() =
        runTest {
            val failing = Proposer<String> { _, _, _ -> Result.failure(IllegalStateException("tool limit")) }
            assertEquals(
                HumanInTheLoop.Outcome.Failed<String>("Round 1: tool limit", emptyList()),
                HumanInTheLoop(failing, ScriptedReviewGate(emptyList()), 3).run(),
            )
            val rejected = HumanInTheLoop(Agent(listOf(unit)), ScriptedReviewGate(listOf(ReviewReply.Reject("wrong source"))), 3).run()
            assertEquals("wrong source", (rejected as HumanInTheLoop.Outcome.Rejected).reason)
        }
}
