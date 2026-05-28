package io.github.mbbhalla.agentio.cmm.impl.adaptive

import java.util.UUID

/**
 * Identifies the origin content block type of a [ContextSegment].
 *
 * Used by the assembler to write segment content back into the correct
 * [aws.sdk.kotlin.services.bedrockruntime.model.ContentBlock] type.
 */
sealed class SegmentSource {
    /** Segment originated from a [ContentBlock.Text] block. */
    data object Text : SegmentSource()

    /**
     * Segment originated from a [ContentBlock.ToolResult] block.
     *
     * @property toolUseId The tool use ID that this result corresponds to.
     *   Required by Bedrock's Converse API for ToolUse ↔ ToolResult linkage.
     * @property status The original tool result status (Success / Error).
     */
    data class ToolResult(
        val toolUseId: String,
        val status: aws.sdk.kotlin.services.bedrockruntime.model.ToolResultStatus?,
    ) : SegmentSource()
}

/**
 * A logical unit of context that can be repositioned within the context window.
 * Segments are the atomic unit of reshuffling — the system moves entire segments,
 * never splitting content within a segment.
 */
data class ContextSegment(
    val id: String = UUID.randomUUID().toString(),
    val content: String,
    val importanceScore: Double,
    val position: Int,
    val constraints: SegmentConstraints = SegmentConstraints(),
    val source: SegmentSource = SegmentSource.Text,
) {
    init {
        require(importanceScore in 0.0..1.0) {
            "importanceScore must be in [0.0, 1.0], was $importanceScore"
        }
        require(position >= 0) {
            "position must be >= 0, was $position"
        }
    }
}

/**
 * Structural constraints governing segment placement during reshuffling.
 */
data class SegmentConstraints(
    /** If true, this segment cannot be moved from its current position. */
    val isAnchored: Boolean = false,
    /**
     * Segments sharing the same group ID must remain adjacent and preserve
     * their relative ordering (e.g., tool call + tool result pairs).
     */
    val adjacencyGroupId: String? = null,
    /** Ordering rank within an adjacency group. Lower values come first. */
    val adjacencyOrder: Int = 0,
) {
    init {
        require(adjacencyOrder >= 0) {
            "adjacencyOrder must be >= 0, was $adjacencyOrder"
        }
    }
}
