package com.amazon.agentio.experiment.adaptive

/**
 * The experiment algorithms to evaluate.
 *
 * Each type defines a different way to assemble context and score the model's response.
 * Adding a new experiment type is: add an enum value here, then handle it in
 * [NeedleContextBuilder] and [NeedleScorer].
 */
enum class ExperimentType(
    val displayName: String,
    val description: String,
) {
    NEEDLE_IN_HAYSTACK(
        displayName = "Needle-in-a-Haystack",
        description = "Single needle at depth 0.5 (dead zone). " +
            "Tests whether adaptive CMM detects low attention and moves the needle to a hot zone.",
    ),

    MULTI_NEEDLE(
        displayName = "Multi-Needle",
        description = "5 needles at depths 0.1, 0.3, 0.5, 0.7, 0.9. " +
            "Target needle at 0.5 has highest importance. " +
            "Tests whether the constrained assignment correctly prioritizes the important needle.",
    ),

    DISTRIBUTED_COUNTING(
        displayName = "Distributed-Counting",
        description = "Every filler paragraph contains a vote for Proposal A or Proposal B, " +
            "expressed in varied natural language. The model must aggregate all votes and report counts. " +
            "Degree of correctness = how close the model's counts are to ground truth. " +
            "Tests whether CMM improves aggregation accuracy by placing segments in high-attention zones.",
    ),
}
