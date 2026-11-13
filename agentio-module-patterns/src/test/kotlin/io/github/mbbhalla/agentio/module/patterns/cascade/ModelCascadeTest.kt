package io.github.mbbhalla.agentio.module.patterns.cascade

import io.github.mbbhalla.agentio.core.lib.AgentOutput
import io.github.mbbhalla.agentio.core.lib.Instructible
import io.github.mbbhalla.agentio.core.model.conversation.Conversation
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class ModelCascadeTest {
    private data class Ask(
        val question: String,
    ) : Instructible.WithInstruction {
        override fun instructionId() = question

        override fun instruction() = question

        override fun systemInstruction(): String? = null
    }

    private data class Reply(
        val answer: String,
        val confidence: Double,
    )

    /** A tier agent with a canned response, recording whether it was called. */
    private class Canned(
        private val response: Result<Reply>,
    ) : Instructible<Ask, Result<AgentOutput<Reply>>> {
        var calls = 0

        override suspend fun invoke(input: Ask): Result<AgentOutput<Reply>> {
            calls++
            return response.map {
                AgentOutput(
                    instructionId = input.instructionId(),
                    conversation = Conversation.initialize(listOf(input.question)),
                    output = it,
                )
            }
        }
    }

    private val confident: (Ask, AgentOutput<Reply>) -> Boolean = { _, output -> output.output.confidence >= 0.8 }

    private fun tier(
        name: String,
        agent: Canned,
    ) = ModelCascade.Tier(name = name, agent = agent, accept = confident)

    @Test
    fun invoke_whenFirstTierAccepted_doesNotCallLaterTiers() =
        runTest {
            val fast = Canned(Result.success(Reply("42", confidence = 0.95)))
            val slow = Canned(Result.success(Reply("42", confidence = 1.0)))

            val outcome = ModelCascade(listOf(tier("fast", fast), tier("slow", slow))).invoke(Ask("q"))

            val resolved = assertIs<ModelCascade.Outcome.Resolved<Reply>>(outcome)
            assertEquals("fast", resolved.tier)
            assertEquals(1, resolved.attempts.size)
            assertEquals(0, slow.calls)
        }

    @Test
    fun invoke_whenFirstTierLowConfidence_escalates() =
        runTest {
            val fast = Canned(Result.success(Reply("maybe 41", confidence = 0.3)))
            val slow = Canned(Result.success(Reply("42", confidence = 0.9)))

            val outcome = ModelCascade(listOf(tier("fast", fast), tier("slow", slow))).invoke(Ask("q"))

            val resolved = assertIs<ModelCascade.Outcome.Resolved<Reply>>(outcome)
            assertEquals("slow", resolved.tier)
            assertEquals(listOf(false, true), resolved.attempts.map { it.accepted })
            assertEquals(1, fast.calls)
            assertEquals(1, slow.calls)
        }

    @Test
    fun invoke_whenTierInvocationFails_treatsAsRejectedAndEscalates() =
        runTest {
            val broken = Canned(Result.failure(IllegalStateException("schema mismatch")))
            val slow = Canned(Result.success(Reply("42", confidence = 0.9)))

            val outcome = ModelCascade(listOf(tier("broken", broken), tier("slow", slow))).invoke(Ask("q"))

            val resolved = assertIs<ModelCascade.Outcome.Resolved<Reply>>(outcome)
            assertEquals("slow", resolved.tier)
            assertEquals(false, resolved.attempts.first().accepted)
        }

    @Test
    fun invoke_whenEveryTierRejected_isExhaustedWithAllAttempts() =
        runTest {
            val tiers = listOf(tier("a", Canned(Result.success(Reply("?", 0.1)))), tier("b", Canned(Result.success(Reply("?", 0.2)))))

            val outcome = ModelCascade(tiers).invoke(Ask("q"))

            val exhausted = assertIs<ModelCascade.Outcome.Exhausted<Reply>>(outcome)
            assertEquals(listOf("a", "b"), exhausted.attempts.map { it.tier })
        }

    @Test
    fun accept_seesInputSoVerifierCanCheckAnswerAgainstTask() =
        runTest {
            val wrong = Canned(Result.success(Reply("41", confidence = 0.99)))
            val right = Canned(Result.success(Reply("42", confidence = 0.99)))
            val verifier: (Ask, AgentOutput<Reply>) -> Boolean = { ask, output -> ask.question == "6*7" && output.output.answer == "42" }

            val outcome =
                ModelCascade(
                    listOf(
                        ModelCascade.Tier("wrong", wrong, verifier),
                        ModelCascade.Tier("right", right, verifier),
                    ),
                ).invoke(Ask("6*7"))

            assertEquals("right", assertIs<ModelCascade.Outcome.Resolved<Reply>>(outcome).tier)
        }

    @Test
    fun constructor_rejectsEmptyAndDuplicateTiers() {
        assertFailsWith<IllegalArgumentException> { ModelCascade<Ask, Reply>(emptyList()) }
        val agent = Canned(Result.success(Reply("x", 1.0)))
        assertFailsWith<IllegalArgumentException> { ModelCascade(listOf(tier("same", agent), tier("same", agent))) }
    }
}
