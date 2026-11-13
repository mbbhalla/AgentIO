package io.github.mbbhalla.agentio.module.patterns.goap

import org.slf4j.LoggerFactory
import kotlin.reflect.KClass

/**
 * The GOAP execution loop (Embabel's OODA cycle): observe the blackboard, plan, run the first
 * action of the plan, publish its effect, and plan again from the new state. Replanning after
 * every action is what lets the process adapt: a failed action is dropped from the available set
 * and the planner finds another route, or reports that it is stuck.
 *
 * Control flow is entirely deterministic. If an LLM is involved it lives inside an action, never
 * in the decision of which action runs next.
 */
class GoapAgentProcess(
    private val actions: Collection<GoapAction<*>>,
    /** Upper bound on executed actions so a mis-specified action set cannot loop forever. */
    private val maxSteps: Int = DEFAULT_MAX_STEPS,
) {
    companion object {
        const val DEFAULT_MAX_STEPS = 20
        private val LOG = LoggerFactory.getLogger(GoapAgentProcess::class.java)
    }

    /** One executed action and whether it succeeded. */
    data class Step(
        val action: GoapAction<*>,
        val succeeded: Boolean,
        val error: Throwable?,
    )

    sealed interface Outcome {
        val blackboard: Blackboard
        val history: List<Step>

        data class Achieved(
            override val blackboard: Blackboard,
            override val history: List<Step>,
        ) : Outcome

        data class Stuck(
            val reason: String,
            override val blackboard: Blackboard,
            override val history: List<Step>,
        ) : Outcome
    }

    suspend fun run(
        initial: Blackboard,
        goal: KClass<*>,
    ): Outcome {
        var blackboard = initial
        var available = actions.toList()
        val history = mutableListOf<Step>()

        while (history.size < maxSteps) {
            val plan =
                GoapPlanner.plan(state = blackboard.types, goal = goal, actions = available)
                    ?: return Outcome.Stuck(
                        reason = "No action sequence reaches ${goal.simpleName} from $blackboard with ${available.map { it.name }}",
                        blackboard = blackboard,
                        history = history,
                    )
            LOG.info("[GOAP] State={} | {}", blackboard, plan)
            if (plan.isEmpty) return Outcome.Achieved(blackboard = blackboard, history = history)

            val action = plan.actions.first()
            LOG.info("[GOAP] Executing '{}'", action.name)
            action
                .apply(blackboard)
                .onSuccess { next ->
                    blackboard = next
                    history += Step(action = action, succeeded = true, error = null)
                }.onFailure { error ->
                    LOG.warn("[GOAP] Action '{}' failed ({}); replanning without it", action.name, error.message)
                    available = available - action
                    history += Step(action = action, succeeded = false, error = error)
                }
        }
        return Outcome.Stuck(reason = "Exceeded $maxSteps steps", blackboard = blackboard, history = history)
    }
}
