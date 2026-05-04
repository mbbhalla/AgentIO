package com.amazon.agentio.lib.ctx.cmm.adaptive

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

internal class ContextReshufflerTest {

    @Test
    fun `should place highest importance at highest attention position`() {
        val segments = listOf(
            ContextSegment(content = "low", importanceScore = 0.2, position = 0),
            ContextSegment(content = "high", importanceScore = 0.9, position = 1),
            ContextSegment(content = "mid", importanceScore = 0.5, position = 2),
        )
        // Position 2 has highest attention, position 0 lowest
        val heatmap = AttentionHeatmap(scores = mapOf(0 to 0.1, 1 to 0.5, 2 to 0.9))

        val result = ContextReshuffler.reshuffle(segments, heatmap)

        // Highest importance (0.9) should be at highest attention position (2)
        val highSegment = result.first { it.content == "high" }
        assertEquals(2, highSegment.position)
    }

    @Test
    fun `anchored segments should not move`() {
        val segments = listOf(
            ContextSegment(
                content = "anchored",
                importanceScore = 0.1,
                position = 0,
                constraints = SegmentConstraints(isAnchored = true),
            ),
            ContextSegment(content = "movable", importanceScore = 0.9, position = 1),
        )
        val heatmap = AttentionHeatmap(scores = mapOf(0 to 0.9, 1 to 0.1))

        val result = ContextReshuffler.reshuffle(segments, heatmap)

        val anchored = result.first { it.content == "anchored" }
        assertEquals(0, anchored.position)
    }

    @Test
    fun `single segment should remain unchanged`() {
        val segments = listOf(
            ContextSegment(content = "only", importanceScore = 0.5, position = 0),
        )
        val heatmap = AttentionHeatmap(scores = mapOf(0 to 0.5))

        val result = ContextReshuffler.reshuffle(segments, heatmap)

        assertEquals(1, result.size)
        assertEquals(0, result[0].position)
    }

    @Test
    fun `result should be sorted by position`() {
        val segments = listOf(
            ContextSegment(content = "a", importanceScore = 0.3, position = 0),
            ContextSegment(content = "b", importanceScore = 0.7, position = 1),
            ContextSegment(content = "c", importanceScore = 0.5, position = 2),
        )
        val heatmap = AttentionHeatmap(scores = mapOf(0 to 0.5, 1 to 0.3, 2 to 0.7))

        val result = ContextReshuffler.reshuffle(segments, heatmap)

        val positions = result.map { it.position }
        assertEquals(positions.sorted(), positions)
    }

    @Test
    fun `empty heatmap should still produce valid output`() {
        val segments = listOf(
            ContextSegment(content = "a", importanceScore = 0.5, position = 0),
            ContextSegment(content = "b", importanceScore = 0.8, position = 1),
        )
        val heatmap = AttentionHeatmap()

        val result = ContextReshuffler.reshuffle(segments, heatmap)

        assertEquals(2, result.size)
    }

    @Test
    fun `adjacency group members should be placed consecutively`() {
        val groupId = "tool-pair"
        val segments = listOf(
            ContextSegment(
                content = "tool-call",
                importanceScore = 0.8,
                position = 0,
                constraints = SegmentConstraints(adjacencyGroupId = groupId, adjacencyOrder = 0),
            ),
            ContextSegment(
                content = "unrelated",
                importanceScore = 0.5,
                position = 1,
            ),
            ContextSegment(
                content = "tool-result",
                importanceScore = 0.8,
                position = 2,
                constraints = SegmentConstraints(adjacencyGroupId = groupId, adjacencyOrder = 1),
            ),
        )
        val heatmap = AttentionHeatmap(scores = mapOf(0 to 0.3, 1 to 0.9, 2 to 0.1))

        val result = ContextReshuffler.reshuffle(segments, heatmap)

        val toolCall = result.first { it.content == "tool-call" }
        val toolResult = result.first { it.content == "tool-result" }

        // They should be adjacent and in order
        assertTrue(
            toolResult.position == toolCall.position + 1,
            "tool-result (${toolResult.position}) should be right after tool-call (${toolCall.position})",
        )
    }

    @Test
    fun `all anchored segments should preserve original positions`() {
        val segments = listOf(
            ContextSegment(
                content = "a",
                importanceScore = 0.5,
                position = 0,
                constraints = SegmentConstraints(isAnchored = true),
            ),
            ContextSegment(
                content = "b",
                importanceScore = 0.5,
                position = 1,
                constraints = SegmentConstraints(isAnchored = true),
            ),
        )
        val heatmap = AttentionHeatmap(scores = mapOf(0 to 0.1, 1 to 0.9))

        val result = ContextReshuffler.reshuffle(segments, heatmap)

        assertEquals(0, result.first { it.content == "a" }.position)
        assertEquals(1, result.first { it.content == "b" }.position)
    }
}
