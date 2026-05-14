package io.github.mbbhalla.agentio.cmm.impl.adaptive

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

internal class ContextSegmentTest {

    @Test
    fun `should create segment with valid parameters`() {
        val segment = ContextSegment(
            content = "hello world",
            importanceScore = 0.5,
            position = 0,
        )
        assertEquals("hello world", segment.content)
        assertEquals(0.5, segment.importanceScore)
        assertEquals(0, segment.position)
        assertFalse(segment.constraints.isAnchored)
        assertNull(segment.constraints.adjacencyGroupId)
    }

    @Test
    fun `should reject importanceScore below 0`() {
        assertThrows<IllegalArgumentException> {
            ContextSegment(content = "x", importanceScore = -0.1, position = 0)
        }
    }

    @Test
    fun `should reject importanceScore above 1`() {
        assertThrows<IllegalArgumentException> {
            ContextSegment(content = "x", importanceScore = 1.1, position = 0)
        }
    }

    @Test
    fun `should accept boundary importanceScore values`() {
        val low = ContextSegment(content = "x", importanceScore = 0.0, position = 0)
        val high = ContextSegment(content = "x", importanceScore = 1.0, position = 1)
        assertEquals(0.0, low.importanceScore)
        assertEquals(1.0, high.importanceScore)
    }

    @Test
    fun `should reject negative position`() {
        assertThrows<IllegalArgumentException> {
            ContextSegment(content = "x", importanceScore = 0.5, position = -1)
        }
    }

    @Test
    fun `SegmentConstraints should reject negative adjacencyOrder`() {
        assertThrows<IllegalArgumentException> {
            SegmentConstraints(adjacencyOrder = -1)
        }
    }

    @Test
    fun `SegmentConstraints defaults should be non-anchored with no group`() {
        val constraints = SegmentConstraints()
        assertFalse(constraints.isAnchored)
        assertNull(constraints.adjacencyGroupId)
        assertEquals(0, constraints.adjacencyOrder)
    }

    @Test
    fun `anchored segment should preserve isAnchored flag`() {
        val segment = ContextSegment(
            content = "system prompt",
            importanceScore = 0.95,
            position = 0,
            constraints = SegmentConstraints(isAnchored = true),
        )
        assertTrue(segment.constraints.isAnchored)
    }
}
