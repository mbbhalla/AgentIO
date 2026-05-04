package com.amazon.agentio.lib.ctx.cmm.adaptive

/**
 * Immutable attention heatmap mapping context segment positions to empirical attention scores.
 *
 * Scores are maintained as an exponential moving average (EMA) across measurement cycles.
 * Each [update] produces a new [AttentionHeatmap] — the original is never mutated.
 *
 * @property scores Position index → attention score in [0.0, 1.0].
 * @property decayFactor EMA decay factor. Higher = more stable, lower = more responsive.
 */
data class AttentionHeatmap(
    val scores: Map<Int, Double> = emptyMap(),
    val decayFactor: Double = DEFAULT_DECAY_FACTOR,
) {
    companion object {
        const val DEFAULT_DECAY_FACTOR = 0.8
    }

    init {
        require(decayFactor in 0.0..1.0) {
            "decayFactor must be in [0.0, 1.0], was $decayFactor"
        }
        require(scores.values.all { it >= 0.0 }) {
            "All attention scores must be >= 0.0"
        }
    }

    /** Get the attention score for a position, defaulting to 0.0 if unmeasured. */
    fun scoreAt(position: Int): Double = scores.getOrDefault(position, 0.0)

    /**
     * Produce a new heatmap with updated scores from a measurement cycle.
     * Uses EMA: newScore = decay * oldScore + (1 - decay) * measuredScore
     *
     * @param measurements Position index → measured attention score from this cycle.
     */
    fun update(measurements: Map<Int, Double>): AttentionHeatmap {
        val updatedScores = (scores.keys + measurements.keys).associateWith { position ->
            val old = scores.getOrDefault(position, 0.0)
            val measured = measurements[position]
            if (measured != null) {
                decayFactor * old + (1.0 - decayFactor) * measured
            } else {
                // Positions not measured this cycle decay toward zero
                decayFactor * old
            }
        }
        return copy(scores = updatedScores)
    }

    /**
     * Positions sorted by attention score descending (hot zones first).
     */
    fun hotZones(): List<Pair<Int, Double>> =
        scores.entries
            .sortedByDescending { it.value }
            .map { it.key to it.value }

    /**
     * Positions sorted by attention score ascending (dead zones first).
     */
    fun deadZones(): List<Pair<Int, Double>> =
        scores.entries
            .sortedBy { it.value }
            .map { it.key to it.value }
}
