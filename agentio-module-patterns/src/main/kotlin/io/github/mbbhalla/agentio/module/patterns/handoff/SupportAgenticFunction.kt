package io.github.mbbhalla.agentio.module.patterns.handoff

import io.github.mbbhalla.agentio.core.common.Description
import io.github.mbbhalla.agentio.core.lib.AbstractAgenticFunction
import io.github.mbbhalla.agentio.core.lib.Instructible
import io.github.mbbhalla.agentio.core.lib.tool.EmptyToolsProvider
import io.github.mbbhalla.agentio.core.lib.tool.ToolsProvider
import io.github.mbbhalla.agentio.module.patterns.common.PatternAgents
import kotlinx.serialization.Serializable

private const val EVENT_TAG = "HANDOFF"

/**
 * One support agent. The same class serves every role; the role's expertise comes from its system
 * prompt and tools. The handoff is part of the typed output rather than a tool call: the agent
 * replies to the customer and states, in the same output, whether control moves to another agent.
 */
internal class SupportAgenticFunction(
    agentId: String,
    val role: AgentRole,
    expertise: String,
    toolsProvider: ToolsProvider = EmptyToolsProvider,
) : AbstractAgenticFunction<SupportAgenticFunction.Input, SupportAgenticFunction.Output>(
        PatternAgents.configuration(
            agentId = agentId,
            problemDomain = "Customer Support ($role)",
            systemPrompt =
                """
                You are the $role support agent. $expertise
                Rules: handle only what is in your area. If part of the customer's problem belongs to another agent,
                finish your part in your reply and hand off with a one-line reason. Do not repeat work already done in
                the transcript. Set handoff to STAY when nothing remains for another agent.
                """.trimIndent(),
            toolsProvider = toolsProvider,
            eventListenerTag = EVENT_TAG,
        ),
    ) {
    @Serializable
    data class Input(
        @field:Description("Customer identifier")
        val customerId: String,
        @field:Description("The customer's original message")
        val customerMessage: String,
        @field:Description("What previous agents have already said to the customer, oldest first")
        val transcript: List<Turn>,
        @field:Description("Handoff decisions this agent is allowed to make")
        val allowedHandoffs: List<HandoffDecision>,
    ) : Instructible.WithInstruction {
        override fun instructionId() = "support-$customerId-${transcript.size}"

        override fun instruction() =
            """
            Help customer '$customerId' with: '$customerMessage'.
            Read the transcript so you do not redo earlier work. Then reply for your area and decide the handoff.
            Allowed handoff values: ${allowedHandoffs.joinToString()}.
            """.trimIndent()

        override fun systemInstruction(): String? = null
    }

    @Serializable
    data class Output(
        @field:Description("Your reply to the customer for your area of responsibility")
        val reply: String,
        @field:Description("STAY if the case is fully handled, otherwise the agent that should take over")
        val handoff: HandoffDecision,
        @field:Description("Why control is being handed off; empty when handoff is STAY")
        val handoffReason: String,
    )

    override fun getInputKClass() = Input::class

    override fun getOutputKClass() = Output::class
}
