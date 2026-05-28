package io.github.mbbhalla.agentio.cmm.impl.adaptive

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

internal class AttentionHeatmapTest {
    @Test
    fun `empty heatmap should return 0 for any position`() {
        val heatmap = AttentionHeatmap()
        assertEquals(0.0, heatmap.scoreAt(0))
        assertEquals(0.0, heatmap.scoreAt(99))
    }

    @Test
    fun `should reject decayFactor below 0`() {
        assertThrows<IllegalArgumentException> {
            AttentionHeatmap(decayFactor = -0.1)
        }
    }

    @Test
    fun `should reject decayFactor above 1`() {
        assertThrows<IllegalArgumentException> {
            AttentionHeatmap(decayFactor = 1.1)
        }
    }

    @Test
    fun `should reject negative score values`() {
        assertThrows<IllegalArgumentException> {
            AttentionHeatmap(scores = mapOf(0 to -0.5))
        }
    }

    @Test
    fun `update should apply EMA formula correctly`() {
        val decay = 0.8
        val heatmap =
            AttentionHeatmap(
                scores = mapOf(0 to 1.0),
                decayFactor = decay,
            )

        val updated = heatmap.update(mapOf(0 to 0.5))

        // EMA: 0.8 * 1.0 + 0.2 * 0.5 = 0.9
        assertEquals(0.9, updated.scoreAt(0), 1e-10)
    }

    @Test
    fun `update should decay unmeasured positions toward zero`() {
        val heatmap =
            AttentionHeatmap(
                scores = mapOf(0 to 1.0, 1 to 0.5),
                decayFactor = 0.5,
            )

        // Only measure position 0
        val updated = heatmap.update(mapOf(0 to 1.0))

        // Position 0: 0.5 * 1.0 + 0.5 * 1.0 = 1.0
        assertEquals(1.0, updated.scoreAt(0), 1e-10)
        // Position 1: 0.5 * 0.5 = 0.25 (decayed, not measured)
        assertEquals(0.25, updated.scoreAt(1), 1e-10)
    }

    @Test
    fun `update should introduce new positions from measurements`() {
        val heatmap = AttentionHeatmap(decayFactor = 0.8)

        val updated = heatmap.update(mapOf(5 to 0.7))

        // New position: 0.8 * 0.0 + 0.2 * 0.7 = 0.14
        assertEquals(0.14, updated.scoreAt(5), 1e-10)
    }

    @Test
    fun `hotZones should return positions sorted by score descending`() {
        val heatmap =
            AttentionHeatmap(
                scores = mapOf(0 to 0.3, 1 to 0.9, 2 to 0.6),
            )

        val hot = heatmap.hotZones()

        assertEquals(1, hot[0].first)
        assertEquals(2, hot[1].first)
        assertEquals(0, hot[2].first)
    }

    @Test
    fun `deadZones should return positions sorted by score ascending`() {
        val heatmap =
            AttentionHeatmap(
                scores = mapOf(0 to 0.3, 1 to 0.9, 2 to 0.6),
            )

        val dead = heatmap.deadZones()

        assertEquals(0, dead[0].first)
        assertEquals(2, dead[1].first)
        assertEquals(1, dead[2].first)
    }

    @Test
    fun `update should not mutate original heatmap`() {
        val original = AttentionHeatmap(scores = mapOf(0 to 0.5))
        val updated = original.update(mapOf(0 to 1.0))

        assertEquals(0.5, original.scoreAt(0))
        assertTrue(updated.scoreAt(0) != 0.5)
    }

    @Test
    fun `boundary decay factors should work`() {
        // decay = 0.0 means fully responsive (ignore history)
        val responsive = AttentionHeatmap(scores = mapOf(0 to 1.0), decayFactor = 0.0)
        val r = responsive.update(mapOf(0 to 0.3))
        assertEquals(0.3, r.scoreAt(0), 1e-10)

        // decay = 1.0 means fully stable (ignore new measurement)
        val stable = AttentionHeatmap(scores = mapOf(0 to 1.0), decayFactor = 1.0)
        val s = stable.update(mapOf(0 to 0.3))
        assertEquals(1.0, s.scoreAt(0), 1e-10)
    }
}
