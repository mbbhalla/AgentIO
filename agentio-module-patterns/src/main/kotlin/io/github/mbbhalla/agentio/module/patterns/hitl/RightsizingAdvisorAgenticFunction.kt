package io.github.mbbhalla.agentio.module.patterns.hitl

import io.github.mbbhalla.agentio.core.common.Description
import io.github.mbbhalla.agentio.core.lib.AbstractAgenticFunction
import io.github.mbbhalla.agentio.core.lib.Instructible
import io.github.mbbhalla.agentio.core.lib.tool.ToolsProvider
import io.github.mbbhalla.agentio.module.patterns.common.PatternAgents
import kotlinx.serialization.Serializable

private const val EVENT_TAG = "HITL"

/** Proposes fleet changes. Its only tool is read-only; the proposal is a recommendation until a human approves it. */
internal class RightsizingAdvisorAgenticFunction(
    agentId: String,
    toolsProvider: ToolsProvider,
) : AbstractAgenticFunction<RightsizingAdvisorAgenticFunction.Input, RightsizingProposal>(
        PatternAgents.configuration(
            agentId = agentId,
            problemDomain = "Cloud Cost Optimisation",
            systemPrompt =
                """
                You recommend right-sizing changes for a compute fleet. Inspect the fleet with the tool, then propose
                changes a cautious operations reviewer would accept. Never propose anything you cannot justify with the
                utilisation data. Reviewer feedback overrides your own judgement.
                """.trimIndent(),
            toolsProvider = toolsProvider,
            eventListenerTag = EVENT_TAG,
        ),
    ) {
    @Serializable
    data class Input(
        @field:Description("Which round of proposal this is, starting at 1")
        val iteration: Int,
        @field:Description("Feedback from the human reviewer on earlier proposals, oldest first; empty on the first round")
        val reviewerFeedback: List<String>,
    ) : Instructible.WithInstruction {
        override fun instructionId() = "rightsizing-proposal-$iteration"

        override fun instruction() =
            if (reviewerFeedback.isEmpty()) {
                "Propose right-sizing changes that reduce monthly cost without risking production workloads."
            } else {
                """
                Revise your proposal. The reviewer said:
                ${reviewerFeedback.joinToString("\n") { "- $it" }}
                Apply every point of feedback and explain in the summary how the revision addresses it.
                """.trimIndent()
            }

        override fun systemInstruction(): String? = null
    }

    override fun getInputKClass() = Input::class

    override fun getOutputKClass() = RightsizingProposal::class
}
