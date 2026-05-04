package com.amazon.agentio.experiment.adaptive

import com.amazon.agentio.experiment.adaptive.function.NeedleRetrievalAgenticFunction
import com.amazon.agentio.experiment.adaptive.function.NeedleRetrievalFunctionFactory
import com.amazon.agentio.lib.eval.AgenticFunctionEvaluator
import com.amazon.agentio.model.LLM
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import java.io.File

/**
 * Entry point for the Adaptive CMM experiment.
 *
 * Runs the full experiment matrix: ExperimentType × Model × Toggle, with N iterations
 * per cell handled by [AgenticFunctionEvaluator].
 *
 * To start small (Phase 3 smoke test), edit [SUITE_CONFIG] below.
 * To run the full matrix, expand models and iterations.
 */
object Runner {

    private val LOG = LoggerFactory.getLogger(Runner::class.java)

    /**
     * Experiment suite configuration.
     *
     * CHANGE THESE VALUES to control the experiment scope:
     * - Start with 1 model, 1 iteration for a smoke test
     * - Expand to all models, 50 iterations for the full run
     */
    private val SUITE_CONFIG = ExperimentSuiteConfig(
        experimentTypes = listOf(
            // ExperimentType.NEEDLE_IN_HAYSTACK,  // Saturates at 100% — task too easy
            // ExperimentType.MULTI_NEEDLE,         // Saturates at 100% — task too easy
            ExperimentType.DISTRIBUTED_COUNTING,       // Distributed Counting — aggregation task
        ),
        models = listOf(
            LLM.ANTHROPIC_CLAUDE_OPUS_4_6_V1_CROSS_REGION_INFERENCE,
            // LLM.ANTHROPIC_CLAUDE_SONNET_4_CROSS_REGION_INFERENCE,                // Requires beta header for 1M context
            // LLM.ANTHROPIC_CLAUDE_OPUS_4_5_V1_CROSS_REGION_INFERENCE,             // Empty content issue with Converse API
        ),
        numIterations = 100,  // 100 iterations per cell
        maxParallelism = 1,  // Sequential to avoid throttling
        bedrockRegion = "us-west-2",
    )

    private val RESULTS_FILE = File("docs/Results.md")

    fun run(): List<CellResult> = runBlocking {
        val cells = SUITE_CONFIG.cells()
        val startTime = System.currentTimeMillis()

        LOG.info("╔══════════════════════════════════════════════════════════════╗")
        LOG.info("║          Adaptive CMM Experiment — Starting                 ║")
        LOG.info("╠══════════════════════════════════════════════════════════════╣")
        LOG.info("║ Cells: {} | Iterations/cell: {} | Total trials: {}",
            cells.size, SUITE_CONFIG.numIterations, SUITE_CONFIG.totalTrials())
        LOG.info("║ Models: {}", SUITE_CONFIG.models.joinToString(", ") { it.name })
        LOG.info("║ Experiments: {}", SUITE_CONFIG.experimentTypes.joinToString(", ") { it.displayName })
        LOG.info("╚══════════════════════════════════════════════════════════════╝")

        val results = mutableListOf<CellResult>()

        for ((cellIndex, cell) in cells.withIndex()) {
            val cellLabel = "${cell.experimentType.displayName} | ${cell.llm.name} | " +
                "CMM ${if (cell.adaptiveCmmEnabled) "ON" else "OFF"}"

            LOG.info("")
            LOG.info("┌──────────────────────────────────────────────────────────────┐")
            LOG.info("│ Cell {}/{}: {}", cellIndex + 1, cells.size, cellLabel)
            LOG.info("│ {} iterations, parallelism = {}", cell.numIterations, cell.maxParallelism)
            LOG.info("│ Context window: {} tokens", cell.llm.maxContextTokens)
            LOG.info("└──────────────────────────────────────────────────────────────┘")

            val cellStartTime = System.currentTimeMillis()
            val needleContext = NeedleContextBuilder.build(cell.experimentType, cell.llm)

            LOG.info("  Context built: {} blocks, ~{} tokens",
                needleContext.contextBlocks.size, needleContext.approximateTokens)

            val input = NeedleRetrievalAgenticFunction.NeedleRetrievalInput(
                question = needleContext.retrievalQuestion,
                isCountingTask = cell.experimentType == ExperimentType.DISTRIBUTED_COUNTING,
            )

            /*
                Use withFactory to ensure each iteration gets a fresh
                NeedleRetrievalAgenticFunction with its own:
                - AdaptiveContextMemoryManager (fresh heatmap, no leakage)
                - BedrockRuntimeClient
                - ContextProviders

                This is the key isolation mechanism between trials.
             */
            val evaluationInput = AgenticFunctionEvaluator.EvaluationInput.withFactory(
                agenticFunctionFactory = {
                    NeedleRetrievalFunctionFactory.create(
                        experimentType = cell.experimentType,
                        llm = cell.llm,
                        adaptiveCmmEnabled = cell.adaptiveCmmEnabled,
                        bedrockRegion = SUITE_CONFIG.bedrockRegion,
                    )
                },
                input = input,
                numIterations = cell.numIterations,
                maxParallelism = cell.maxParallelism,
                delayBetweenIterationsMs = 10000, // 10 seconds between iterations to avoid throttling
                outputMatcher = { output ->
                    if (cell.experimentType == ExperimentType.DISTRIBUTED_COUNTING) {
                        // Distributed Counting: match if the model's structured counts
                        // identify the correct winner
                        val groundTruth = needleContext.groundTruthVotes
                        if (groundTruth != null && output.proposalACount > 0 && output.proposalBCount > 0) {
                            val modelWinnerIsA = output.proposalACount > output.proposalBCount
                            val trueWinnerIsA = groundTruth.proposalA > groundTruth.proposalB
                            modelWinnerIsA == trueWinnerIsA
                        } else {
                            false
                        }
                    } else {
                        // Needle experiments: exact code match
                        needleContext.expectedCodes.any { code ->
                            output.answer.contains(code, ignoreCase = true)
                        }
                    }
                },
                onIterationComplete = { progress ->
                    if (progress.result.isFailure) {
                        LOG.info(
                            "  [Iter {}/{}] ❌ FAILED: {}",
                            progress.completedCount,
                            progress.totalIterations,
                            progress.result.cause.message?.take(80),
                        )
                    } else if (cell.experimentType == ExperimentType.DISTRIBUTED_COUNTING &&
                        needleContext.groundTruthVotes != null
                    ) {
                        val gt = needleContext.groundTruthVotes
                        val output = progress.result.get().output
                        val icon = if (progress.matched) "✅" else "❌"
                        LOG.info(
                            "  [Iter {}/{}] {} | True A={} B={} | Model A={} B={} | Diff A={} B={}",
                            progress.completedCount,
                            progress.totalIterations,
                            icon,
                            gt.proposalA, gt.proposalB,
                            output.proposalACount, output.proposalBCount,
                            kotlin.math.abs(output.proposalACount - gt.proposalA),
                            kotlin.math.abs(output.proposalBCount - gt.proposalB),
                        )
                    } else {
                        val icon = if (progress.matched) "✅" else "❌"
                        val answer = progress.result.get().output.answer.take(60)
                        LOG.info(
                            "  [Iter {}/{}] {} | \"{}\"",
                            progress.completedCount,
                            progress.totalIterations,
                            icon,
                            answer,
                        )
                    }
                },
            )

            val evaluator = AgenticFunctionEvaluator(evaluationInput)
            val evaluationResult = evaluator.evaluate()

            val cellDuration = (System.currentTimeMillis() - cellStartTime) / 1000
            val cellResult = CellResult(
                experimentType = cell.experimentType,
                llm = cell.llm,
                adaptiveCmmEnabled = cell.adaptiveCmmEnabled,
                evaluationResult = evaluationResult,
            )
            results.add(cellResult)

            // Cell summary
            val accuracy = evaluationResult.successAndMatchIterations.toDouble() /
                evaluationResult.totalIterations * 100
            LOG.info("")
            LOG.info("  ┌─ Cell {}/{} Summary ─────────────────────────────────────┐",
                cellIndex + 1, cells.size)
            LOG.info("  │ Accuracy: {}/{} ({}%)",
                evaluationResult.successAndMatchIterations,
                evaluationResult.totalIterations,
                accuracy.toInt())
            LOG.info("  │ Successes: {} | Failures: {}",
                evaluationResult.successResults.size,
                evaluationResult.failures.size)
            LOG.info("  │ Duration: {}s", cellDuration)
            LOG.info("  └──────────────────────────────────────────────────────────┘")

            // Log failure details if any
            evaluationResult.failures.forEachIndexed { i, throwable ->
                LOG.error("  Failure {}: {}", i + 1, throwable.message, throwable)
            }
        }

        // Write results
        ResultsWriter.write(results, SUITE_CONFIG, RESULTS_FILE)

        val totalDuration = (System.currentTimeMillis() - startTime) / 1000
        LOG.info("")
        LOG.info("╔══════════════════════════════════════════════════════════════╗")
        LOG.info("║          Experiment Complete                                ║")
        LOG.info("╠══════════════════════════════════════════════════════════════╣")
        LOG.info("║ Total duration: {}s", totalDuration)
        LOG.info("║ Results written to: {}", RESULTS_FILE.absolutePath)
        LOG.info("╚══════════════════════════════════════════════════════════════╝")

        results
    }
}

fun main() {
    Runner.run()
}
