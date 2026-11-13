package io.github.mbbhalla.agentio.module.patterns.goap

import kotlin.reflect.KClass

/**
 * One planning primitive: an action that needs the fact types in [requires] on the [Blackboard]
 * and, when executed, adds one fact of type [produces].
 *
 * Preconditions and effects are types, as in Embabel's GOAP: the planner never looks inside a
 * fact, only at whether it exists. An action may wrap an LLM-backed agentic function or be plain
 * deterministic code; the planner treats both identically and distinguishes them only by [cost].
 */
abstract class GoapAction<O : Any>(
    val name: String,
    val requires: Set<KClass<*>>,
    val produces: KClass<O>,
    /** Relative expense of running this action; the planner minimises the total along a plan. Must be positive. */
    val cost: Int,
) {
    init {
        require(cost > 0) { "Action '$name' must have a positive cost, got $cost" }
        require(produces !in requires) { "Action '$name' cannot require the type it produces" }
    }

    /**
     * Runs the action against a blackboard that already holds every type in [requires].
     * A failure is a legitimate outcome: the process records it and replans without this action.
     */
    abstract suspend fun execute(blackboard: Blackboard): Result<O>

    /** Executes and, on success, publishes the effect under [produces]. */
    suspend fun apply(blackboard: Blackboard): Result<Blackboard> = execute(blackboard).map { output -> blackboard.with(produces, output) }

    override fun toString(): String = "$name(cost=$cost)"
}

/** Builds a [GoapAction] from a lambda, for deterministic steps that need no class of their own. */
inline fun <reified O : Any> goapAction(
    name: String,
    requires: Set<KClass<*>>,
    cost: Int = 1,
    crossinline body: suspend (Blackboard) -> Result<O>,
): GoapAction<O> =
    object : GoapAction<O>(name = name, requires = requires, produces = O::class, cost = cost) {
        override suspend fun execute(blackboard: Blackboard): Result<O> = body(blackboard)
    }
