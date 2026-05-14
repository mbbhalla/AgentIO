package io.github.mbbhalla.agentio.experiments.adaptive

import io.github.mbbhalla.agentio.core.model.LLM

/**
 * Configuration for a single experiment cell in the adaptive CMM evaluation matrix.
 *
 * The full experiment matrix is: Experiment × Model × Attempt × Toggle.
 * This config represents one (Experiment × Model × Toggle) cell; the evaluator
 * handles the Attempt dimension via [numIterations].
 */
data class ExperimentCellConfig(
    val experimentType: ExperimentType,
    val llm: LLM,
    val adaptiveCmmEnabled: Boolean,
    val numIterations: Int,
    val maxParallelism: Int,
)

/**
 * Top-level experiment configuration. Controls which cells to run.
 *
 * To start small (Phase 3 smoke test): use 1 model, 1 iteration, 1 experiment type.
 * To run the full matrix: use all [LLM.entries], 50 iterations, both experiment types.
 */
data class ExperimentSuiteConfig(
    /** Which experiment types to run. */
    val experimentTypes: List<ExperimentType>,

    /** Which models to evaluate. */
    val models: List<LLM>,

    /** Number of attempts per cell (50 for the full run). */
    val numIterations: Int,

    /** Max concurrent Bedrock calls per cell. */
    val maxParallelism: Int,

    /** AWS region for Bedrock client. */
    val bedrockRegion: String = "us-west-2",
) {
    /** Expand into individual cell configs for the runner loop. */
    fun cells(): List<ExperimentCellConfig> =
        experimentTypes.flatMap { experimentType ->
            models.flatMap { llm ->
                listOf(true, false).map { cmmEnabled ->
                    ExperimentCellConfig(
                        experimentType = experimentType,
                        llm = llm,
                        adaptiveCmmEnabled = cmmEnabled,
                        numIterations = numIterations,
                        maxParallelism = maxParallelism,
                    )
                }
            }
        }

    /** Total number of trials across all cells. */
    fun totalTrials(): Int = cells().size * numIterations
}
