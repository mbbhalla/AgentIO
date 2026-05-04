package com.amazon.agentio.lib.eval

import com.amazon.agentio.lib.AgentOutput
import com.amazon.agentio.lib.Instructible
import io.vavr.control.Try
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory

interface AgentOutputSelector<O : Any> {
    fun select(
        agentOutputs: List<Try<AgentOutput<O>>>,
    ): Try<AgentOutput<O>>
}

class MajorityOccurredAgentOutputSelector<O : Any>(
    private val selectorBetweenEquallyLikely: AgentOutputSelector<O>,
) : AgentOutputSelector<O> {
    override fun select(
        agentOutputs: List<Try<AgentOutput<O>>>,
    ): Try<AgentOutput<O>> {
        val successAgentOutputs = agentOutputs.filter { it.isSuccess }
        val frequencyMap = successAgentOutputs
            .groupingBy { it.get().output }
            .eachCount()
        val maxFreq = frequencyMap.values.maxOrNull() ?: emptyList<O>()
        return selectorBetweenEquallyLikely.select(
            successAgentOutputs.filter { frequencyMap[it.get().output] == maxFreq },
        )
    }
}

class AgenticFunctionTrials<I : Instructible.WithInstruction, O : Any>(
    private val agenticFunction: Instructible<I, Try<AgentOutput<O>>>,
    private val numberOfTrials: Int,
    private val agentOutputSelector: AgentOutputSelector<O>,
) : Instructible<I, Try<AgentOutput<O>>> {

    companion object {
        private val LOG = LoggerFactory.getLogger(AgenticFunctionTrials::class.java)
    }

    override suspend fun invoke(
        input: I,
    ): Try<AgentOutput<O>> = withContext(Dispatchers.IO) {
        agentOutputSelector.select(
            List(numberOfTrials) {
                Pair(it, agenticFunction)
            }.map { (trial, agenticFunction) ->
                async {
                    LOG.info("Invoking agent for trial: $trial")
                    agenticFunction.invoke(input)
                }
            }.awaitAll(),
        )
    }
}
