package com.amazon.agentio.lib.ctx.cmm.compacting

/**
 * Configuration for the [CompactingContextMemoryManager].
 *
 * @property threshold Fraction of [com.amazon.agentio.model.LLM.maxContextTokens] at which
 *   compaction is triggered. Client-specified. For example, 0.75 means compact when 75%
 *   of the context window is occupied.
 * @property preservedRecentTurns Number of most-recent conversation turn pairs (User + Assistant)
 *   to preserve verbatim. These are never summarized — recency matters for coherence.
 * @property minTurnGapBetweenCompactions Minimum number of turns between consecutive compactions
 *   to prevent thrashing.
 */
data class CompactionConfig(
    val threshold: Double = 0.75,
    val preservedRecentTurns: Int = 4,
    val minTurnGapBetweenCompactions: Int = 5,
) {
    init {
        require(threshold in 0.1..0.99) {
            "threshold must be in [0.1, 0.99], was $threshold"
        }
        require(preservedRecentTurns >= 1) {
            "preservedRecentTurns must be >= 1, was $preservedRecentTurns"
        }
        require(minTurnGapBetweenCompactions >= 1) {
            "minTurnGapBetweenCompactions must be >= 1, was $minTurnGapBetweenCompactions"
        }
    }
}
