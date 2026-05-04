package com.amazon.agentio.lib.eval

import com.amazon.agentio.lib.AbstractAgenticFunction
import com.amazon.agentio.lib.AgentOutput
import com.amazon.agentio.lib.Instructible
import io.vavr.control.Try
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicInteger

/**
 * Evaluates an [AbstractAgenticFunction] by running it multiple times with the same input
 * and aggregating results.
 *
 * Supports two modes:
 * - **Shared instance**: a single [AbstractAgenticFunction] is reused across all iterations.
 *   Suitable when the function and its dependencies are stateless.
 * - **Factory mode**: a factory produces a fresh [AbstractAgenticFunction] per iteration.
 *   Required when the function's dependencies carry mutable state that must not leak
 *   between trials (e.g., [com.amazon.agentio.lib.ctx.cmm.adaptive.AdaptiveContextMemoryManager]
 *   whose heatmap accumulates across turns).
 *
 * Use [EvaluationInput.withFunction] for shared-instance mode and
 * [EvaluationInput.withFactory] for factory mode.
 */
class AgenticFunctionEvaluator<I : Instructible.WithInstruction, O : Any>(
    private val evaluationInput: EvaluationInput<I, O>,
) {

    companion object {
        private val LOG = LoggerFactory.getLogger(AgenticFunctionEvaluator::class.java)
    }

    data class EvaluationInput<I : Instructible.WithInstruction, O : Any>(
        /**
         * Factory that produces an [AbstractAgenticFunction] for each iteration.
         * When using [withFunction], this returns the same instance every time.
         * When using [withFactory], this produces a fresh instance per iteration.
         */
        val agenticFunctionFactory: () -> AbstractAgenticFunction<I, O>,

        /** The input to evaluate with. */
        val input: I,

        /** Number of iterations to run. */
        val numIterations: Int,

        /** Maximum concurrent iterations. */
        val maxParallelism: Int,

        /** Predicate that determines whether an output is a "match" (success criterion). */
        val outputMatcher: (O) -> Boolean,

        /**
         * Optional callback invoked after each iteration completes.
         * Receives the iteration index (0-based), total iterations, and the trial result.
         * Called from a coroutine context — must be thread-safe.
         */
        val onIterationComplete: ((IterationProgress<O>) -> Unit)? = null,

        /**
         * Delay in milliseconds between iterations to avoid API throttling.
         * Default is 0 (no delay). Set to e.g., 2000 for 2 seconds between calls.
         */
        val delayBetweenIterationsMs: Long = 0,
    ) {
        companion object {
            /**
             * Create an [EvaluationInput] that reuses a single [AbstractAgenticFunction]
             * instance across all iterations. Use when the function is stateless.
             */
            fun <I : Instructible.WithInstruction, O : Any> withFunction(
                agenticFunction: AbstractAgenticFunction<I, O>,
                input: I,
                numIterations: Int,
                maxParallelism: Int,
                outputMatcher: (O) -> Boolean,
                onIterationComplete: ((IterationProgress<O>) -> Unit)? = null,
                delayBetweenIterationsMs: Long = 0,
            ): EvaluationInput<I, O> = EvaluationInput(
                agenticFunctionFactory = { agenticFunction },
                input = input,
                numIterations = numIterations,
                maxParallelism = maxParallelism,
                outputMatcher = outputMatcher,
                onIterationComplete = onIterationComplete,
                delayBetweenIterationsMs = delayBetweenIterationsMs,
            )

            /**
             * Create an [EvaluationInput] that produces a fresh [AbstractAgenticFunction]
             * per iteration via the provided factory. Use when the function's dependencies
             * carry mutable state (e.g., adaptive CMM heatmap) that must not leak between trials.
             */
            fun <I : Instructible.WithInstruction, O : Any> withFactory(
                agenticFunctionFactory: () -> AbstractAgenticFunction<I, O>,
                input: I,
                numIterations: Int,
                maxParallelism: Int,
                outputMatcher: (O) -> Boolean,
                onIterationComplete: ((IterationProgress<O>) -> Unit)? = null,
                delayBetweenIterationsMs: Long = 0,
            ): EvaluationInput<I, O> = EvaluationInput(
                agenticFunctionFactory = agenticFunctionFactory,
                input = input,
                numIterations = numIterations,
                maxParallelism = maxParallelism,
                outputMatcher = outputMatcher,
                onIterationComplete = onIterationComplete,
                delayBetweenIterationsMs = delayBetweenIterationsMs,
            )
        }
    }

    /**
     * Progress report for a single completed iteration.
     */
    data class IterationProgress<O : Any>(
        /** 1-based index of the completed iteration. */
        val completedCount: Int,

        /** Total iterations in this evaluation. */
        val totalIterations: Int,

        /** The result of this iteration. */
        val result: Try<AgentOutput<O>>,

        /** Whether the output matched (false if the iteration failed). */
        val matched: Boolean,

        /** Running count of matches so far. */
        val matchesSoFar: Int,

        /** Running count of failures so far. */
        val failuresSoFar: Int,
    )

    data class EvaluationResult<O : Any>(
        val totalIterations: Int,
        val failures: List<Throwable>,
        val successResults: List<O>,
        val successAndMatchIterations: Int,

        /**
         * Raw per-trial outputs preserving the full [AgentOutput] (including [Conversation]
         * with token usage) for trials that succeeded, and the failure cause for trials
         * that failed. Ordered by iteration index.
         *
         * Use this for per-trial analysis: token counts, latency, heatmap snapshots, etc.
         */
        val rawOutputs: List<Try<AgentOutput<O>>>,
    )

    suspend fun evaluate(): EvaluationResult<O> {
        val completedCount = AtomicInteger(0)
        val matchCount = AtomicInteger(0)
        val failureCount = AtomicInteger(0)

        val outputs = withContext(Dispatchers.IO.limitedParallelism(evaluationInput.maxParallelism)) {
            (0..<evaluationInput.numIterations).map { iterationIndex ->
                async {
                    // Stagger requests to avoid API throttling
                    if (evaluationInput.delayBetweenIterationsMs > 0 && iterationIndex > 0) {
                        delay(evaluationInput.delayBetweenIterationsMs * iterationIndex)
                    }

                    val result = evaluationInput.agenticFunctionFactory()
                        .invoke(input = evaluationInput.input)

                    // Compute match status
                    val matched = result.isSuccess &&
                        evaluationInput.outputMatcher.invoke(result.get().output)

                    // Update counters atomically
                    val completed = completedCount.incrementAndGet()
                    if (matched) matchCount.incrementAndGet()
                    if (result.isFailure) failureCount.incrementAndGet()

                    // Report progress
                    if (evaluationInput.onIterationComplete != null) {
                        evaluationInput.onIterationComplete.invoke(
                            IterationProgress(
                                completedCount = completed,
                                totalIterations = evaluationInput.numIterations,
                                result = result,
                                matched = matched,
                                matchesSoFar = matchCount.get(),
                                failuresSoFar = failureCount.get(),
                            ),
                        )
                    } else {
                        // Default progress logging when no callback is provided
                        val status = when {
                            result.isFailure -> "FAILED: ${result.cause.message}"
                            matched -> "MATCHED"
                            else -> "NO MATCH"
                        }
                        LOG.debug(
                            "[Iteration {}/{}] {} | matches so far: {}/{}",
                            completed,
                            evaluationInput.numIterations,
                            status,
                            matchCount.get(),
                            completed,
                        )
                    }

                    result
                }
            }.awaitAll()
        }
        return EvaluationResult(
            totalIterations = evaluationInput.numIterations,
            failures = outputs.filter { it.isFailure }.map { it.cause },
            successResults = outputs.filter { it.isSuccess }.map { it.get().output },
            successAndMatchIterations = outputs.filter {
                it.isSuccess && evaluationInput.outputMatcher.invoke(it.get().output)
            }.size,
            rawOutputs = outputs,
        )
    }
}
