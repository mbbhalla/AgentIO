package io.github.mbbhalla.agentio.eventlistener.impl.checkpoint

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

internal class CheckpointTriggerTest {

    @Test
    fun `EveryNTurns should accept positive n`() {
        val trigger = CheckpointTrigger.EveryNTurns(n = 5)
        assertEquals(5, trigger.n)
    }

    @Test
    fun `EveryNTurns should accept n equals 1`() {
        val trigger = CheckpointTrigger.EveryNTurns(n = 1)
        assertEquals(1, trigger.n)
    }

    @Test
    fun `EveryNTurns should reject zero`() {
        val exception = assertThrows<IllegalArgumentException> {
            CheckpointTrigger.EveryNTurns(n = 0)
        }
        assertEquals("n must be > 0, was 0", exception.message)
    }

    @Test
    fun `EveryNTurns should reject negative values`() {
        val exception = assertThrows<IllegalArgumentException> {
            CheckpointTrigger.EveryNTurns(n = -3)
        }
        assertEquals("n must be > 0, was -3", exception.message)
    }
}
