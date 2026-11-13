package io.github.mbbhalla.agentio.module.patterns.goap

import io.github.mbbhalla.agentio.core.lib.AbstractAgenticFunction
import io.github.mbbhalla.agentio.core.lib.Instructible
import kotlin.reflect.KClass

/**
 * Adapts an [AbstractAgenticFunction] to a [GoapAction]. The function's typed output is the fact
 * the action publishes, so an agentic function with `Output = RiskAssessment` becomes an action
 * that produces `RiskAssessment`. [buildInput] assembles the function's input from the facts named
 * in [requires]; the process guarantees those facts exist before calling it.
 */
class AgenticFunctionAction<I : Instructible.WithInstruction, O : Any>(
    name: String,
    requires: Set<KClass<*>>,
    produces: KClass<O>,
    cost: Int,
    private val function: AbstractAgenticFunction<I, O>,
    private val buildInput: (Blackboard) -> I,
) : GoapAction<O>(name = name, requires = requires, produces = produces, cost = cost) {
    override suspend fun execute(blackboard: Blackboard): Result<O> = function.invoke(buildInput(blackboard)).map { it.output }
}
