package io.github.mbbhalla.agentio.module.patterns.planexecute

import org.slf4j.LoggerFactory

/**
 * The Plan-and-Execute loop. An LLM planner writes the plan as data; this code runs the steps in
 * order, records each result, and on a failed step asks the planner for a revised plan covering
 * the remaining work, at most [maxReplans] times. When every step has run, a synthesizer turns
 * the gathered facts into the deliverable.
 *
 * Contrast with GOAP: here the LLM chooses the steps and the code only sequences them; in GOAP a
 * deterministic planner chooses the steps from typed preconditions and effects.
 */
internal class PlanAndExecute(
    private val planner: PlannerAgenticFunction,
    private val executor: ExecutorAgenticFunction,
    private val synthesizer: SynthesizerAgenticFunction,
    private val toolCatalogue: List<String>,
    private val maxSteps: Int,
    private val maxReplans: Int,
) {
    companion object {
        private val LOG = LoggerFactory.getLogger(PlanAndExecute::class.java)
    }

    sealed interface Outcome {
        val results: List<StepResult>
        val plans: List<Plan>

        data class Completed(
            val answer: String,
            val gaps: List<String>,
            override val results: List<StepResult>,
            override val plans: List<Plan>,
        ) : Outcome

        data class Abandoned(
            val reason: String,
            override val results: List<StepResult>,
            override val plans: List<Plan>,
        ) : Outcome
    }

    suspend fun run(goal: String): Outcome {
        val plans = mutableListOf<Plan>()
        val results = mutableListOf<StepResult>()

        var plan =
            plan(goal, previousSteps = emptyList(), executionLog = emptyList()).getOrElse {
                return abandoned("Planning failed: ${it.message}", results, plans)
            }
        plans += plan
        var pending = ArrayDeque(plan.steps)
        var replans = 0

        while (pending.isNotEmpty()) {
            val step = pending.removeFirst()
            val result = execute(goal, step, results)
            results += result
            LOG.info("[PLAN-EXECUTE] Step {} {}: {}", step.id, result.status, result.summary)
            if (result.status == StepStatus.SUCCESS) continue

            if (replans >=
                maxReplans
            ) {
                return abandoned("Step ${step.id} failed and the replan budget ($maxReplans) is spent", results, plans)
            }
            replans++
            LOG.info("[PLAN-EXECUTE] Replanning ({} of {})", replans, maxReplans)
            plan =
                plan(goal, previousSteps = plan.steps, executionLog = results).getOrElse {
                    return abandoned("Replanning failed: ${it.message}", results, plans)
                }
            plans += plan
            pending = ArrayDeque(plan.steps)
        }

        return synthesizer
            .invoke(SynthesizerAgenticFunction.Input(goal = goal, results = results))
            .map { Outcome.Completed(answer = it.output.answer, gaps = it.output.gaps, results = results, plans = plans) as Outcome }
            .getOrElse { abandoned("Synthesis failed: ${it.message}", results, plans) }
    }

    private suspend fun plan(
        goal: String,
        previousSteps: List<PlanStep>,
        executionLog: List<StepResult>,
    ): Result<Plan> =
        planner
            .invoke(
                PlannerAgenticFunction.Input(
                    goal = goal,
                    toolCatalogue = toolCatalogue,
                    maxSteps = maxSteps,
                    previousSteps = previousSteps,
                    executionLog = executionLog,
                ),
            ).map { it.output }
            .onSuccess { produced ->
                LOG.info("[PLAN-EXECUTE] Plan: {}", produced.steps.joinToString(" | ") { "${it.id}. ${it.description}" })
            }

    /** An executor invocation that fails outright (schema, model error) is itself a FAILED step, so the loop can replan around it. */
    private suspend fun execute(
        goal: String,
        step: PlanStep,
        priorResults: List<StepResult>,
    ): StepResult =
        executor
            .invoke(ExecutorAgenticFunction.Input(goal = goal, step = step, priorResults = priorResults))
            .map { StepResult(stepId = step.id, status = it.output.status, summary = it.output.summary, facts = it.output.facts) }
            .getOrElse { error ->
                StepResult(stepId = step.id, status = StepStatus.FAILED, summary = "Executor error: ${error.message}", facts = emptyList())
            }

    private fun abandoned(
        reason: String,
        results: List<StepResult>,
        plans: List<Plan>,
    ): Outcome.Abandoned {
        LOG.error("[PLAN-EXECUTE] Abandoned: {}", reason)
        return Outcome.Abandoned(reason = reason, results = results, plans = plans)
    }
}
