package io.github.mbbhalla.agentio.core.lib.eval

import aws.sdk.kotlin.services.bedrockruntime.model.ContentBlock
import io.github.mbbhalla.agentio.core.lib.AgentOutput
import io.github.mbbhalla.agentio.core.lib.Instructible
import io.vavr.control.Try
import io.vavr.kotlin.failure
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.milliseconds

/**
 * Strategy for picking a single "winning" [AgentOutput] out of the outputs produced by
 * running an agentic function multiple times.
 *
 * Implementations receive every trial outcome (successes and failures) and MUST be total:
 * they return a [Try.failure] rather than throwing or returning nonsense when no output can
 * be selected (e.g. all trials failed).
 *
 * This is a `fun interface`, so a one-off selection policy can be passed as a lambda while
 * the reusable built-ins ([MostFrequentAgentOutputSelector], [MetricAgentOutputSelector],
 * [FirstSuccessAgentOutputSelector], [FilteringAgentOutputSelector]) remain named types.
 */
fun interface AgentOutputSelector<O : Any> {
    fun select(outputs: List<Try<AgentOutput<O>>>): Try<AgentOutput<O>>
}

/**
 * Selects the first successful output, or a failure if there are none.
 * Serves as the default tie-breaker for frequency-based selection.
 */
class FirstSuccessAgentOutputSelector<O : Any> : AgentOutputSelector<O> {
    override fun select(outputs: List<Try<AgentOutput<O>>>): Try<AgentOutput<O>> =
        outputs.firstOrNull { it.isSuccess }
            ?: failure(IllegalStateException("No successful output to select from"))
}

/**
 * Selects the output value that occurred most frequently across successful trials (by
 * [Any.equals]). Ties between equally-frequent outputs are resolved by [tieBreaker].
 */
class MostFrequentAgentOutputSelector<O : Any>(
    private val tieBreaker: AgentOutputSelector<O> = FirstSuccessAgentOutputSelector(),
) : AgentOutputSelector<O> {
    override fun select(outputs: List<Try<AgentOutput<O>>>): Try<AgentOutput<O>> {
        val successes = outputs.filter { it.isSuccess }
        if (successes.isEmpty()) {
            return failure(IllegalStateException("No successful outputs to select the most frequent from"))
        }
        val frequencyByOutput = successes.groupingBy { it.get().output }.eachCount()
        val maxFrequency = frequencyByOutput.values.max()
        val mostFrequent = successes.filter { frequencyByOutput[it.get().output] == maxFrequency }
        return tieBreaker.select(mostFrequent)
    }
}

/** Whether a [MetricAgentOutputSelector] prefers the lowest or highest metric value. */
enum class SelectionMode { MINIMIZE, MAXIMIZE }

/**
 * Scores every successful output with [metric] — a function over the whole [AgentOutput], so it
 * can key off the output value, the [io.github.mbbhalla.agentio.core.model.conversation.Conversation]
 * (token usage, rounds, tool calls), or both — and selects the output with the lowest ([SelectionMode.MINIMIZE])
 * or highest ([SelectionMode.MAXIMIZE]) score. Returns a failure if there are no successful outputs.
 *
 * See [ConversationMetrics] for ready-made conversation-based metrics such as minimum tokens or
 * fewest rounds. To score purely on the output value, wrap it: `MetricAgentOutputSelector(MAXIMIZE) { it.output.score }`.
 */
class MetricAgentOutputSelector<O : Any>(
    private val mode: SelectionMode,
    private val metric: (AgentOutput<O>) -> Double,
) : AgentOutputSelector<O> {
    companion object {
        private val LOG = LoggerFactory.getLogger(MetricAgentOutputSelector::class.java)
    }

    private fun compute(input: AgentOutput<O>): Double {
        val metric = metric(input)
        LOG.info("Computing metric = $metric")
        return metric
    }

    override fun select(outputs: List<Try<AgentOutput<O>>>): Try<AgentOutput<O>> {
        val successes = outputs.filter { it.isSuccess }.map { it.get() }
        val selected =
            when (mode) {
                SelectionMode.MINIMIZE -> successes.minByOrNull { compute(it) }
                SelectionMode.MAXIMIZE -> successes.maxByOrNull { compute(it) }
            }
        return selected?.let { Try.success(it) }
            ?: failure(IllegalStateException("No successful output to score"))
    }
}

/**
 * Wraps a delegate selector so it only sees outputs that satisfy [predicate] (evaluated over the
 * successful [AgentOutput]s). Use to discard suspect trials before selection — e.g. drop
 * conversations that were truncated (`stopReason` of MaxTokens) or content-filtered — then let the
 * delegate pick among what remains.
 *
 * Failures are always filtered out before [predicate] runs; if nothing survives, the delegate is
 * invoked with an empty list and returns its own failure.
 */
class FilteringAgentOutputSelector<O : Any>(
    private val predicate: (AgentOutput<O>) -> Boolean,
    private val delegate: AgentOutputSelector<O>,
) : AgentOutputSelector<O> {
    override fun select(outputs: List<Try<AgentOutput<O>>>): Try<AgentOutput<O>> =
        delegate.select(outputs.filter { it.isSuccess && predicate(it.get()) })
}

/**
 * Ready-made [MetricAgentOutputSelector] metrics derived from the conversation an output was
 * produced by. Compose with [MetricAgentOutputSelector] and [SelectionMode], e.g.
 * `MetricAgentOutputSelector(MINIMIZE, ConversationMetrics::totalTokens)` selects the cheapest output.
 */
object ConversationMetrics {
    /** Total tokens consumed (input + output) across the whole conversation. */
    fun totalTokens(output: AgentOutput<*>): Double {
        val tokenUsage = output.conversation.tokenUsage
        return (tokenUsage.totalInputTokens + tokenUsage.totalOutputTokens).toDouble()
    }

    /** Output tokens generated across the whole conversation. */
    fun outputTokens(output: AgentOutput<*>): Double =
        output.conversation.tokenUsage.totalOutputTokens
            .toDouble()

    /** Number of messages exchanged — a proxy for how many rounds it took to reach the answer. */
    fun rounds(output: AgentOutput<*>): Double =
        output.conversation.messages.size
            .toDouble()

    /** Number of tool invocations across the conversation. */
    fun toolCalls(output: AgentOutput<*>): Double =
        output.conversation.messages
            .flatMap { it.message.content }
            .count { it is ContentBlock.ToolUse }
            .toDouble()

    /** How many extra "thinking mode" iterations the agent used. */
    fun thinkingIterations(output: AgentOutput<*>): Double = output.conversation.thinkingModeCounter.toDouble()
}

/**
 * Runs an agentic function multiple times with the same input, then applies an
 * [AgentOutputSelector] strategy to pick a single winning output.
 *
 * The evaluator separates two concerns:
 * - **Execution** (this class): how many times to run, concurrency, staggering, progress.
 * - **Selection** (the injected [AgentOutputSelector]): which output wins.
 *
 * It implements [Instructible], so it composes anywhere a plain agentic function is expected:
 * [invoke] runs the trials and returns the selected output. Use [evaluate] instead when the
 * full set of per-trial outputs is needed for analysis (token usage, latency, match rates).
 *
 * The function under evaluation is supplied via [EvaluationConfig.agenticFunctionFactory].
 * Return the same instance every time for stateless functions, or a fresh instance per call
 * when dependencies carry mutable state that must not leak between trials.
 */
class AgenticFunctionEvaluator<I : Instructible.WithInstruction, O : Any>(
    private val config: EvaluationConfig<I, O>,
) : Instructible<I, Try<AgentOutput<O>>> {
    companion object {
        private val LOG = LoggerFactory.getLogger(AgenticFunctionEvaluator::class.java)
    }

    init {
        require(config.numIterations >= 1) {
            "numIterations must be at least 1, got ${config.numIterations}"
        }
        require(config.maxParallelism >= 1) {
            "maxParallelism must be at least 1, got ${config.maxParallelism}"
        }
        require(config.delayBetweenIterationsMs >= 0) {
            "delayBetweenIterationsMs must be non-negative, got ${config.delayBetweenIterationsMs}"
        }
    }

    data class EvaluationConfig<I : Instructible.WithInstruction, O : Any>(
        /**
         * Produces the agentic function to run for each iteration. Return a shared instance
         * (`{ myFunction }`) for stateless functions, or a fresh instance per invocation when
         * mutable dependency state must not leak between trials.
         */
        val agenticFunctionFactory: () -> Instructible<I, Try<AgentOutput<O>>>,
        /** Number of iterations to run. Must be >= 1. */
        val numIterations: Int,
        /** Maximum concurrent iterations. Must be >= 1. */
        val maxParallelism: Int,
        /** Strategy that picks the winning output from all trial outcomes. */
        val selectionStrategy: AgentOutputSelector<O>,
        /**
         * Delay in milliseconds between iterations to avoid API throttling.
         * Default is 0 (no delay).
         */
        val delayBetweenIterationsMs: Long = 0,
        /**
         * Optional callback invoked after each iteration completes.
         * Called from a coroutine context — MUST be thread-safe.
         */
        val onIterationComplete: ((IterationProgress<O>) -> Unit)? = null,
    )

    /** Progress report for a single completed iteration. */
    data class IterationProgress<O : Any>(
        /** 1-based count of iterations completed so far. */
        val completedCount: Int,
        /** Total iterations in this evaluation. */
        val totalIterations: Int,
        /** The result of this iteration. */
        val result: Try<AgentOutput<O>>,
        /** Running count of failures so far. */
        val failuresSoFar: Int,
    )

    /** The outcome of an evaluation: the strategy's pick plus the full raw trial data. */
    data class EvaluationResult<O : Any>(
        val totalIterations: Int,
        /** The output chosen by the selection strategy (a failure if none could be selected). */
        val selectedOutput: Try<AgentOutput<O>>,
        /**
         * Every per-trial outcome, ordered by iteration index. Preserves the full
         * [AgentOutput] (including [io.github.mbbhalla.agentio.core.model.conversation.Conversation]
         * with token usage) for successes, and the cause for failures. Use for per-trial
         * analysis: token counts, latency, match rates, etc.
         */
        val allOutputs: List<Try<AgentOutput<O>>>,
    )

    /** [Instructible] entry point: run the trials and return the strategy's selected output. */
    override suspend fun invoke(input: I): Try<AgentOutput<O>> = evaluate(input).selectedOutput

    /** Run the trials and return the selected output alongside the full raw trial data. */
    suspend fun evaluate(input: I): EvaluationResult<O> {
        val completedCount = AtomicInteger(0)
        val failureCount = AtomicInteger(0)

        val outputs =
            withContext(Dispatchers.IO.limitedParallelism(config.maxParallelism)) {
                (0..<config.numIterations)
                    .map { iterationIndex ->
                        async {
                            // Stagger requests to avoid API throttling.
                            if (config.delayBetweenIterationsMs > 0 && iterationIndex > 0) {
                                delay((config.delayBetweenIterationsMs * iterationIndex).milliseconds)
                            }

                            val result = config.agenticFunctionFactory().invoke(input)

                            val completed = completedCount.incrementAndGet()
                            if (result.isFailure) failureCount.incrementAndGet()

                            reportProgress(completed, result, failureCount.get())

                            result
                        }
                    }.awaitAll()
            }

        return EvaluationResult(
            totalIterations = config.numIterations,
            selectedOutput = config.selectionStrategy.select(outputs),
            allOutputs = outputs,
        )
    }

    private fun reportProgress(
        completed: Int,
        result: Try<AgentOutput<O>>,
        failuresSoFar: Int,
    ) {
        val callback = config.onIterationComplete
        if (callback != null) {
            callback.invoke(
                IterationProgress(
                    completedCount = completed,
                    totalIterations = config.numIterations,
                    result = result,
                    failuresSoFar = failuresSoFar,
                ),
            )
        } else {
            val status = if (result.isFailure) "FAILED: ${result.cause.message}" else "SUCCESS"
            LOG.debug(
                "[Iteration {}/{}] {} | failures so far: {}",
                completed,
                config.numIterations,
                status,
                failuresSoFar,
            )
        }
    }
}
