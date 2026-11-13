package io.github.mbbhalla.agentio.module.patterns.cascade

import io.github.mbbhalla.agentio.core.common.Description
import io.github.mbbhalla.agentio.core.lib.AbstractAgenticFunction
import io.github.mbbhalla.agentio.core.lib.Instructible
import io.github.mbbhalla.agentio.core.lib.tool.EmptyToolsProvider
import io.github.mbbhalla.agentio.core.lib.tool.ToolsProvider
import io.github.mbbhalla.agentio.core.model.LLM
import io.github.mbbhalla.agentio.core.model.ThinkingMode
import io.github.mbbhalla.agentio.module.patterns.common.PatternAgents
import kotlinx.serialization.Serializable

private const val EVENT_TAG = "CASCADE"

/**
 * The same task-solving function at every tier; tiers differ only in model, tools, output budget
 * and thinking mode. The output carries a self-assessed confidence so a tier can decline a task it
 * cannot do with the means it has, which is what lets the cascade escalate before wasting a verifier
 * call on an answer the model itself does not trust.
 */
internal class TaskAgenticFunction(
    agentId: String,
    llm: LLM,
    maxOutputTokens: Int = llm.maxOutputTokens,
    toolsProvider: ToolsProvider = EmptyToolsProvider,
    thinkingMode: ThinkingMode = ThinkingMode(maxIterations = 0),
) : AbstractAgenticFunction<TaskAgenticFunction.Input, TaskAgenticFunction.Output>(
        PatternAgents.configuration(
            agentId = agentId,
            problemDomain = "General Problem Solving",
            systemPrompt =
                """
                You solve short tasks exactly. Rate your confidence honestly: if the task needs a computation you cannot
                perform exactly with the means available (and you have no tool for it), give the best answer you can but
                set confidence below 0.5 and say why in 'method'. Confidence above 0.8 means you verified the answer.
                """.trimIndent(),
            toolsProvider = toolsProvider,
            languageModelParameters = PatternAgents.languageModelParameters(llm = llm, maxOutputTokens = maxOutputTokens),
            thinkingMode = thinkingMode,
            eventListenerTag = EVENT_TAG,
        ),
    ) {
    @Serializable
    data class Input(
        @field:Description("The task to solve")
        val task: String,
    ) : Instructible.WithInstruction {
        override fun instructionId() = "task-${task.hashCode()}"

        override fun instruction() = "Solve: '$task'"

        override fun systemInstruction(): String? = null
    }

    @Serializable
    data class Output(
        @field:Description("The answer, containing the exact value when the task asks for one")
        val answer: String,
        @field:Description("Self-assessed confidence from 0.0 to 1.0")
        val confidence: Double,
        @field:Description("How the answer was obtained, naming any tool used")
        val method: String,
    )

    override fun getInputKClass() = Input::class

    override fun getOutputKClass() = Output::class
}
