package io.github.mbbhalla.agentio.module.patterns.planexecute

import io.github.mbbhalla.agentio.core.common.Description
import kotlinx.serialization.Serializable

/*
    The plan is data produced by one LLM call and consumed by code. That is the defining trait of
    Plan-and-Execute: an LLM decides the steps, but the loop that runs them, detects failure, and
    asks for a revised plan is ordinary deterministic code.
 */

@Serializable
data class PlanStep(
    @field:Description("Step number, starting at 1 and increasing in execution order")
    val id: Int,
    @field:Description("What the executor must do in this step, concrete enough to act on with the available tools")
    val description: String,
    @field:Description("What a successful result of this step looks like")
    val expectedOutput: String,
)

@Serializable
data class Plan(
    @field:Description("Ordered steps that together achieve the goal")
    val steps: List<PlanStep>,
    @field:Description("Why these steps, in this order")
    val reasoning: String,
)

enum class StepStatus { SUCCESS, FAILED }

@Serializable
data class StepResult(
    @field:Description("Id of the plan step this result belongs to")
    val stepId: Int,
    @field:Description("Whether the step achieved its expected output")
    val status: StepStatus,
    @field:Description("One-paragraph summary of what was found or why the step failed")
    val summary: String,
    @field:Description("Concrete facts gathered in this step, one per entry, for later steps and the final answer")
    val facts: List<String>,
)
