package io.github.mbbhalla.agentio.module.patterns.goap

import kotlin.reflect.KClass

/**
 * Immutable, type-keyed world state for Goal-Oriented Action Planning.
 *
 * Every fact is stored under the type an action declared it [GoapAction.produces] as, so planner
 * preconditions and blackboard lookups agree even when the runtime value is a subtype. Adding a
 * fact returns a new blackboard; the previous one is untouched, which keeps the execution history
 * inspectable step by step.
 */
class Blackboard private constructor(
    private val facts: Map<KClass<*>, Any>,
) {
    companion object {
        fun empty(): Blackboard = Blackboard(emptyMap())

        /** Starts a blackboard from concrete values, each keyed by its own runtime class. */
        fun of(vararg values: Any): Blackboard = Blackboard(values.associateBy { it::class })
    }

    /** The set of fact types currently present; this is the planner's notion of state. */
    val types: Set<KClass<*>> get() = facts.keys

    fun has(type: KClass<*>): Boolean = type in facts

    fun <T : Any> with(
        type: KClass<T>,
        value: T,
    ): Blackboard = Blackboard(facts + (type to value))

    /** Returns the fact stored under [type], failing loudly if an action ran before its precondition was met. */
    fun <T : Any> get(type: KClass<T>): T {
        val value = facts[type] ?: throw IllegalStateException("Blackboard has no fact of type ${type.simpleName}")
        return type.javaObjectType.cast(value)
    }

    inline fun <reified T : Any> get(): T = get(T::class)

    override fun toString(): String = facts.keys.joinToString(prefix = "Blackboard[", postfix = "]") { it.simpleName ?: "?" }
}
