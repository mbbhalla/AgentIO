package io.github.mbbhalla.agentio.cmm.impl.compacting

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

internal class CompactionConfigTest {

    @Test
    fun `defaults should be valid`() {
        val config = CompactionConfig()
        assertEquals(0.75, config.threshold)
        assertEquals(4, config.preservedRecentTurns)
        assertEquals(5, config.minTurnGapBetweenCompactions)
    }

    @Test
    fun `should accept boundary threshold values`() {
        val low = CompactionConfig(threshold = 0.1)
        val high = CompactionConfig(threshold = 0.99)
        assertEquals(0.1, low.threshold)
        assertEquals(0.99, high.threshold)
    }

    @Test
    fun `should reject threshold below 0_1`() {
        assertThrows<IllegalArgumentException> {
            CompactionConfig(threshold = 0.09)
        }
    }

    @Test
    fun `should reject threshold above 0_99`() {
        assertThrows<IllegalArgumentException> {
            CompactionConfig(threshold = 1.0)
        }
    }

    @Test
    fun `should reject preservedRecentTurns below 1`() {
        assertThrows<IllegalArgumentException> {
            CompactionConfig(preservedRecentTurns = 0)
        }
    }

    @Test
    fun `should reject minTurnGapBetweenCompactions below 1`() {
        assertThrows<IllegalArgumentException> {
            CompactionConfig(minTurnGapBetweenCompactions = 0)
        }
    }
}
