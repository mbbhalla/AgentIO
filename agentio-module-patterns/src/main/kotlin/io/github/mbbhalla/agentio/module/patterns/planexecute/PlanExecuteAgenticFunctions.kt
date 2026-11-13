package io.github.mbbhalla.agentio.module.patterns.planexecute

import io.github.mbbhalla.agentio.core.common.Description
import io.github.mbbhalla.agentio.core.lib.AbstractAgenticFunction
import io.github.mbbhalla.agentio.core.lib.Instructible
import io.github.mbbhalla.agentio.core.lib.tool.ToolsProvider
import io.github.mbbhalla.agentio.module.patterns.common.PatternAgents
import kotlinx.serialization.Serializable

private const val EVENT_TAG = "PLAN-EXECUTE"

/** Produces the plan. Has no tools: it reasons about the tool catalogue it is told about, it never calls one. */
internal class PlannerAgenticFunction(
    agentId: String,
) : AbstractAgenticFunction<PlannerAgenticFunction.Input, Plan>(
        PatternAgents.configuration(
            agentId = agentId,
            problemDomain = "Task Planning",
            systemPrompt = "You decompose goals into short, concrete, tool-executable plans. Fewer steps are better.",
            eventListenerTag = EVENT_TAG,
        ),
    ) {
    @Serializable
    data class Input(
        @field:Description("The goal the plan must achieve")
        val goal: String,
        @field:Description("Tools the executor can call, with a one-line description each")
        val toolCatalogue: List<String>,
        @field:Description("Maximum number of steps allowed in the plan")
        val maxSteps: Int,
        @field:Description("Steps of the previous plan; empty when planning for the first time")
        val previousSteps: List<PlanStep>,
        @field:Description("Results of steps already executed, including the failure that triggered replanning; empty on first planning")
        val executionLog: List<StepResult>,
    ) : Instructible.WithInstruction {
        private val isReplan: Boolean get() = executionLog.isNotEmpty()

        override fun instructionId() = if (isReplan) "plan-revise-${executionLog.size}" else "plan-initial"

        override fun instruction() =
            if (isReplan) {
                """
                The previous plan hit a failure (see executionLog). Produce a revised plan for the REMAINING work only:
                do not repeat steps whose results are already SUCCESS; replace or re-sequence what failed so the goal
                '$goal' can still be reached with the available tools. Keep at most $maxSteps steps and number them
                continuing after the highest id already executed.
                """.trimIndent()
            } else {
                """
                Produce a plan of at most $maxSteps steps to achieve the goal: '$goal'.
                Each step must be achievable by calling the listed tools; say which tool(s) to use in the description.
                """.trimIndent()
            }

        override fun systemInstruction(): String? = null
    }

    override fun getInputKClass() = Input::class

    override fun getOutputKClass() = Plan::class
}

/** Executes one step with tools. Sees only the goal, the step, and facts from earlier steps. */
internal class ExecutorAgenticFunction(
    agentId: String,
    toolsProvider: ToolsProvider,
) : AbstractAgenticFunction<ExecutorAgenticFunction.Input, ExecutorAgenticFunction.Output>(
        PatternAgents.configuration(
            agentId = agentId,
            problemDomain = "Repository Exploration",
            systemPrompt =
                """
                You execute exactly one step of a plan using the tools provided. Report FAILED honestly when the step's
                expected output cannot be produced; never invent facts.
                """.trimIndent(),
            toolsProvider = toolsProvider,
            eventListenerTag = EVENT_TAG,
        ),
    ) {
    @Serializable
    data class Input(
        @field:Description("Overall goal, for context only")
        val goal: String,
        @field:Description("The single step to execute now")
        val step: PlanStep,
        @field:Description("Results of the steps executed before this one")
        val priorResults: List<StepResult>,
    ) : Instructible.WithInstruction {
        override fun instructionId() = "execute-step-${step.id}"

        override fun instruction() =
            """
            Execute step ${step.id}: '${step.description}'. Expected output: '${step.expectedOutput}'.
            Use the tools to gather real data. Record every concrete fact you establish in 'facts'.
            """.trimIndent()

        override fun systemInstruction(): String? = null
    }

    @Serializable
    data class Output(
        @field:Description("SUCCESS if the expected output was produced, otherwise FAILED")
        val status: StepStatus,
        @field:Description("Summary of what was found, or why the step failed")
        val summary: String,
        @field:Description("Concrete facts established in this step, one per entry")
        val facts: List<String>,
    )

    override fun getInputKClass() = Input::class

    override fun getOutputKClass() = Output::class
}

/** Composes the final answer from the gathered facts. No tools: it must not go looking for more data. */
internal class SynthesizerAgenticFunction(
    agentId: String,
) : AbstractAgenticFunction<SynthesizerAgenticFunction.Input, SynthesizerAgenticFunction.Output>(
        PatternAgents.configuration(
            agentId = agentId,
            problemDomain = "Technical Writing",
            systemPrompt = "You write concise, accurate briefs strictly from the facts you are given.",
            eventListenerTag = EVENT_TAG,
        ),
    ) {
    @Serializable
    data class Input(
        @field:Description("The goal the brief must satisfy")
        val goal: String,
        @field:Description("Everything the executor established, step by step")
        val results: List<StepResult>,
    ) : Instructible.WithInstruction {
        override fun instructionId() = "synthesize"

        override fun instruction() = "Write the deliverable for the goal '$goal' using only the facts in results."

        override fun systemInstruction(): String? = null
    }

    @Serializable
    data class Output(
        @field:Description("The deliverable, in Markdown")
        val answer: String,
        @field:Description("Facts that were requested by the goal but could not be established")
        val gaps: List<String>,
    )

    override fun getInputKClass() = Input::class

    override fun getOutputKClass() = Output::class
}
