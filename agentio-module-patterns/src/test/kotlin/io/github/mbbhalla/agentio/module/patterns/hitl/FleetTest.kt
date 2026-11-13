package io.github.mbbhalla.agentio.module.patterns.hitl

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class FleetTest {
    private val fleet =
        Fleet(
            listOf(
                Resource("a", "web", SizeTier.LARGE, avgCpuPercent = 60, servesProduction = true),
                Resource("b", "batch", SizeTier.XLARGE, avgCpuPercent = 5, servesProduction = false),
                Resource("c", "sandbox", SizeTier.SMALL, avgCpuPercent = 1, servesProduction = false),
            ),
        )

    private fun proposal(vararg changes: ProposedChange) = RightsizingProposal(changes = changes.toList(), summary = "test")

    @Test
    fun apply_downsizesAndTerminates_leavingOthersUntouched() {
        val after =
            fleet
                .apply(
                    proposal(
                        ProposedChange("b", ChangeAction.DOWNSIZE, "idle"),
                        ProposedChange("c", ChangeAction.TERMINATE, "unused"),
                        ProposedChange("a", ChangeAction.KEEP, "busy"),
                    ),
                ).getOrThrow()

        assertEquals(listOf("a" to SizeTier.LARGE, "b" to SizeTier.LARGE), after.resources.map { it.id to it.size })
        assertEquals(fleet.monthlyCostUsd - 200.0 - 50.0, after.monthlyCostUsd)
    }

    @Test
    fun apply_isPure_originalFleetUnchanged() {
        fleet.apply(proposal(ProposedChange("b", ChangeAction.TERMINATE, "x"))).getOrThrow()

        assertEquals(3, fleet.resources.size)
    }

    @Test
    fun apply_rejectsUnknownResourceAndImpossibleDownsize() {
        val result =
            fleet.apply(
                proposal(ProposedChange("zzz", ChangeAction.TERMINATE, "x"), ProposedChange("c", ChangeAction.DOWNSIZE, "x")),
            )

        assertTrue(result.isFailure)
        val message = result.exceptionOrNull()?.message.orEmpty()
        assertTrue("zzz" in message && "'c' is already the smallest size" in message)
    }

    @Test
    fun scriptedGate_replaysDecisionsThenRejects() =
        runTest {
            val gate = ScriptedApprovalGate(listOf(ReviewDecision.Revise("tighter"), ReviewDecision.Approve))
            val anyProposal = proposal()

            assertEquals(ReviewDecision.Revise("tighter"), gate.review(1, anyProposal))
            assertEquals(ReviewDecision.Approve, gate.review(2, anyProposal))
            assertIs<ReviewDecision.Reject>(gate.review(3, anyProposal))
        }
}
