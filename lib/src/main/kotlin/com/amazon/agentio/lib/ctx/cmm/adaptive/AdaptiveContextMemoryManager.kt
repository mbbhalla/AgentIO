package com.amazon.agentio.lib.ctx.cmm.adaptive

import aws.sdk.kotlin.services.bedrockruntime.model.ContentBlock
import aws.sdk.kotlin.services.bedrockruntime.model.ConversationRole
import com.amazon.agentio.lib.ctx.cmm.ContextMemoryManager
import com.amazon.agentio.model.Conversation
import org.slf4j.LoggerFactory

/**
 * Immutable snapshot of the adaptive engine's cross-turn state.
 * Replaced (never mutated) after each cycle.
 */
data class AdaptiveState(
    val heatmap: AttentionHeatmap = AttentionHeatmap(),
    val registry: ProbeRegistry = ProbeRegistry(),
    val lastMeasurementTurn: Int = -1,
) {
    init {
        require(lastMeasurementTurn >= -1) {
            "lastMeasurementTurn must be >= -1, was $lastMeasurementTurn"
        }
    }
}

/**
 * Configuration for the adaptive context memory manager.
 *
 * @property measurementFrequency Run a measurement cycle every N turns.
 * @property measurementStrategy Strategy for probe queries and response parsing.
 * @property segmentExtractor Extracts [ContextSegment]s from a [Conversation].
 * @property segmentAssembler Reassembles a [Conversation] from reshuffled [ContextSegment]s.
 * @property heatmapDecayFactor EMA decay factor for the attention heatmap.
 * @property enablePiggyback When true, append the measurement strategy's recall request
 *   to the last user message so the model reports probe values as a side-effect of its
 *   normal response. This is the recommended production setting — it yields heatmap signal
 *   at zero additional API-call cost. Set to false if the recall request interferes with
 *   the application's task (e.g., structured-output-only agents).
 */
data class AdaptiveConfig(
    val measurementFrequency: Int = 3,
    val measurementStrategy: MeasurementStrategy = MultiProbeRecallStrategy,
    val segmentExtractor: SegmentExtractor = DefaultSegmentExtractor,
    val segmentAssembler: SegmentAssembler = DefaultSegmentAssembler,
    val heatmapDecayFactor: Double = AttentionHeatmap.DEFAULT_DECAY_FACTOR,
    val enablePiggyback: Boolean = true,
) {
    init {
        require(measurementFrequency >= 1) {
            "measurementFrequency must be >= 1, was $measurementFrequency"
        }
        require(heatmapDecayFactor in 0.0..1.0) {
            "heatmapDecayFactor must be in [0.0, 1.0], was $heatmapDecayFactor"
        }
    }
}

/**
 * Extracts logical [ContextSegment]s from a [Conversation].
 * The default implementation treats each [ContentBlock.Text] in User messages as a segment.
 */
fun interface SegmentExtractor {
    fun extract(conversation: Conversation): List<ContextSegment>
}

/**
 * Reassembles a [Conversation] from reshuffled [ContextSegment]s.
 * The default implementation replaces User message text blocks with the reshuffled content.
 */
fun interface SegmentAssembler {
    fun assemble(conversation: Conversation, segments: List<ContextSegment>): Conversation
}

/**
 * Default extractor: extracts segments from both [ContentBlock.Text] and [ContentBlock.ToolResult]
 * blocks in User messages.
 *
 * - Text blocks become movable segments (except the first, which is anchored as the system prompt).
 * - ToolResult blocks become anchored segments — they cannot be repositioned because Bedrock
 *   requires ToolResult blocks to stay in the same User message as their corresponding ToolUse,
 *   with matching toolUseId. However, they still participate in probe embedding and heatmap
 *   measurement, allowing the adaptive CMM to observe which tool results the model attends to.
 * - Piggyback recall request blocks (identified by [AdaptiveContextMemoryManager.RECALL_REQUEST_MARKER])
 *   are excluded.
 */
object DefaultSegmentExtractor : SegmentExtractor {
    override fun extract(conversation: Conversation): List<ContextSegment> {
        var position = 0
        return conversation.messages
            .filter { it.message.role == ConversationRole.User }
            .flatMap { envelope ->
                envelope.message.content.mapNotNull { block ->
                    when (block) {
                        is ContentBlock.Text -> {
                            if (block.value.startsWith(AdaptiveContextMemoryManager.RECALL_REQUEST_MARKER)) {
                                null
                            } else {
                                val isFirst = position == 0
                                val segment = ContextSegment(
                                    content = block.value,
                                    importanceScore = estimateTextImportance(block.value, isFirst),
                                    position = position,
                                    constraints = SegmentConstraints(isAnchored = isFirst),
                                    source = SegmentSource.Text,
                                )
                                position++
                                segment
                            }
                        }
                        is ContentBlock.ToolResult -> {
                            val textContent = block.value.content
                                .mapNotNull { resultBlock ->
                                    when (resultBlock) {
                                        is aws.sdk.kotlin.services.bedrockruntime.model
                                        .ToolResultContentBlock.Text -> resultBlock.value

                                        is aws.sdk.kotlin.services.bedrockruntime.model
                                        .ToolResultContentBlock.Json -> resultBlock.value.toString()

                                        else -> null
                                    }
                                }
                                .joinToString("\n")
                            val segment = ContextSegment(
                                content = textContent,
                                importanceScore = estimateToolResultImportance(textContent),
                                position = position,
                                constraints = SegmentConstraints(isAnchored = true),
                                source = SegmentSource.ToolResult(
                                    toolUseId = block.value.toolUseId,
                                    status = block.value.status,
                                ),
                            )
                            position++
                            segment
                        }
                        else -> null
                    }
                }
            }
    }

    /**
     * Heuristic importance for text segments.
     * Applications should provide their own [SegmentExtractor] for domain-specific scoring.
     */
    private fun estimateTextImportance(content: String, isFirst: Boolean): Double = when {
        isFirst -> 0.95 // System prompt / initial instruction
        content.contains("error", ignoreCase = true) ||
            content.contains("exception", ignoreCase = true) -> 0.85
        content.contains("result", ignoreCase = true) ||
            content.contains("output", ignoreCase = true) -> 0.75
        else -> 0.5
    }

    /**
     * Heuristic importance for tool result segments.
     * Tool results with errors are scored higher (more likely to need attention).
     * Recent tool results are generally more important but position-based recency
     * is handled by the heatmap, not here.
     */
    private fun estimateToolResultImportance(content: String): Double = when {
        content.contains("error", ignoreCase = true) ||
            content.contains("exception", ignoreCase = true) -> 0.85
        content.length > 5000 -> 0.6 // Large results may contain important data
        else -> 0.7 // Tool results are generally important context
    }
}

/**
 * Default assembler: writes reshuffled segment content back into User messages,
 * preserving all non-User messages unchanged.
 *
 * Uses a position-based lookup to match segments back to their original content blocks,
 * avoiding iterator-based ordering issues when User messages contain mixed block types
 * (Text + ToolResult) across multiple messages.
 *
 * For [SegmentSource.Text] segments, replaces the corresponding [ContentBlock.Text] block.
 * For [SegmentSource.ToolResult] segments, reconstructs the [ContentBlock.ToolResult] block
 * with the original toolUseId and status, using the (potentially probe-instrumented) content.
 */
object DefaultSegmentAssembler : SegmentAssembler {
    override fun assemble(
        conversation: Conversation,
        segments: List<ContextSegment>,
    ): Conversation {
        // Build a map from position → segment for O(1) lookup
        val segmentByPosition = segments.associateBy { it.position }
        var position = 0

        val newMessages = conversation.messages.map { envelope ->
            if (envelope.message.role != ConversationRole.User) {
                envelope
            } else {
                val newContent = envelope.message.content.map { block ->
                    when (block) {
                        is ContentBlock.Text -> {
                            // Skip recall request blocks — they are not extracted as segments
                            if (block.value.startsWith(
                                    AdaptiveContextMemoryManager.RECALL_REQUEST_MARKER,
                                )
                            ) {
                                block
                            } else {
                                val segment = segmentByPosition[position]
                                position++
                                if (segment != null && segment.source is SegmentSource.Text) {
                                    ContentBlock.Text(segment.content)
                                } else {
                                    block
                                }
                            }
                        }
                        is ContentBlock.ToolResult -> {
                            val segment = segmentByPosition[position]
                            position++
                            if (segment != null && segment.source is SegmentSource.ToolResult) {
                                ContentBlock.ToolResult(
                                    value = aws.sdk.kotlin.services.bedrockruntime.model
                                        .ToolResultBlock {
                                        this.toolUseId = segment.source.toolUseId
                                        this.status = segment.source.status
                                        this.content = listOf(
                                            aws.sdk.kotlin.services.bedrockruntime.model
                                                .ToolResultContentBlock.Text(
                                                value = segment.content,
                                            ),
                                        )
                                    },
                                )
                            } else {
                                block
                            }
                        }
                        else -> block
                    }
                }
                envelope.copy(
                    message = aws.sdk.kotlin.services.bedrockruntime.model.Message {
                        role = envelope.message.role
                        content = newContent
                    },
                )
            }
        }

        return conversation.copy(messages = newMessages)
    }
}

/**
 * Adaptive Context Memory Manager — orchestrates the Probe → Measure → Reshuffle cycle.
 *
 * Implements [ContextMemoryManager] and integrates into the CMM chain.
 * Cross-turn state (heatmap, registry) is held as a "var" reference to an immutable
 * [AdaptiveState] — the state object itself is never mutated, only replaced.
 *
 * @property config Configuration for measurement frequency, strategy, and segment handling.
 */
class AdaptiveContextMemoryManager(
    private val config: AdaptiveConfig = AdaptiveConfig(),
) : ContextMemoryManager {

    companion object {
        private val LOG = LoggerFactory.getLogger(AdaptiveContextMemoryManager::class.java)

        /**
         * Marker prefix for the piggyback recall request content block.
         * Used by [DefaultSegmentExtractor] to exclude the recall request from segments,
         * and by "stripRecallRequests" to remove stale requests from prior turns.
         */
        const val RECALL_REQUEST_MARKER = "[AGENTIO_RECALL_REQUEST]"
    }

    /** Cross-turn state — immutable value, replaced each cycle. */
    private var state: AdaptiveState = AdaptiveState(
        heatmap = AttentionHeatmap(decayFactor = config.heatmapDecayFactor),
    )

    override fun shouldExecuteOnTurn(turnNumber: Int): Boolean =
        turnNumber > 0 // Skip turn 0 (initial prompt, no data to measure yet)

    override suspend fun getContext(
        input: ContextMemoryManager.ContextMemoryManagerInput,
    ): Conversation {
        val conversation = input.conversation
        val turnNumber = input.turnNumber

        // Phase 2: Measure — parse the latest assistant response for probe recall data
        val measurements = measureFromLatestResponse(conversation)
        val updatedHeatmap = if (measurements.isNotEmpty()) {
            LOG.debug("Turn $turnNumber: measured ${measurements.size} probe responses")
            state.heatmap.update(measurements)
        } else {
            state.heatmap
        }

        // Check if this turn is a measurement cycle
        val isMeasurementTurn = (turnNumber % config.measurementFrequency) == 0 ||
            updatedHeatmap.scores.isEmpty() // Always measure if we have no data yet

        if (!isMeasurementTurn) {
            state = state.copy(heatmap = updatedHeatmap)
            return conversation
        }

        // Only reshuffle if we have heatmap data
        val result = if (updatedHeatmap.scores.isNotEmpty()) {
            // Phase 1 & 4: Extract segments, reshuffle, re-embed probes, reassemble
            val segments = config.segmentExtractor.extract(conversation)

            if (segments.size < 2) {
                // Nothing to reshuffle with fewer than 2 segments
                state = state.copy(heatmap = updatedHeatmap)
                return conversation
            }

            val stripped = ProbeTokenManager.stripProbes(segments)
            val reshuffled = ContextReshuffler.reshuffle(stripped, updatedHeatmap)
            val embeddingResult = ProbeTokenManager.embedProbes(reshuffled)

            LOG.debug(
                "Turn $turnNumber: reshuffled ${segments.size} segments, " +
                    "hot zones: ${updatedHeatmap.hotZones().take(3)}",
            )

            // Update state with new heatmap and registry
            state = AdaptiveState(
                heatmap = updatedHeatmap,
                registry = embeddingResult.registry,
                lastMeasurementTurn = turnNumber,
            )

            config.segmentAssembler.assemble(conversation, embeddingResult.segments)
        } else {
            // No heatmap yet — just embed probes for the first measurement
            val segments = config.segmentExtractor.extract(conversation)
            if (segments.size >= 2) {
                val embeddingResult = ProbeTokenManager.embedProbes(segments)
                state = state.copy(
                    heatmap = updatedHeatmap,
                    registry = embeddingResult.registry,
                )
                config.segmentAssembler.assemble(conversation, embeddingResult.segments)
            } else {
                state = state.copy(heatmap = updatedHeatmap)
                conversation
            }
        }

        // Piggyback: append the recall request to the last user message so the model
        // reports probe values as a side-effect of its normal response.
        return if (config.enablePiggyback && state.registry.size > 0) {
            appendRecallRequest(result, state.registry)
        } else {
            result
        }
    }

    /**
     * Append the measurement strategy's recall query to the last User message
     * in the conversation. Adds it as a separate [ContentBlock.Text] prefixed with
     * [RECALL_REQUEST_MARKER] so [DefaultSegmentExtractor] can exclude it from segments
     * on subsequent turns.
     */
    private fun appendRecallRequest(
        conversation: Conversation,
        registry: ProbeRegistry,
    ): Conversation {
        val recallQuery = config.measurementStrategy.buildQuery(registry)
        val markedQuery = "$RECALL_REQUEST_MARKER\n$recallQuery"
        val lastUserIndex = conversation.messages.indexOfLast {
            it.message.role == ConversationRole.User
        }
        if (lastUserIndex < 0) return conversation

        val updatedMessages = conversation.messages.mapIndexed { index, envelope ->
            if (index != lastUserIndex) {
                envelope
            } else {
                // Remove any prior recall request blocks before adding the fresh one
                val cleanedContent = envelope.message.content.filter { block ->
                    block !is ContentBlock.Text ||
                        !block.value.startsWith(RECALL_REQUEST_MARKER)
                }
                val augmentedContent = cleanedContent + ContentBlock.Text(markedQuery)
                envelope.copy(
                    message = aws.sdk.kotlin.services.bedrockruntime.model.Message {
                        role = envelope.message.role
                        content = augmentedContent
                    },
                )
            }
        }
        return conversation.copy(messages = updatedMessages)
    }

    /**
     * Parse the latest assistant response for probe recall data.
     * Returns a map of segmentPosition → attention score derived from recall rank.
     */
    private fun measureFromLatestResponse(conversation: Conversation): Map<Int, Double> {
        val lastAssistantText = conversation.messages
            .lastOrNull { it.message.role == ConversationRole.Assistant }
            ?.message?.content
            ?.filterIsInstance<ContentBlock.Text>()
            ?.joinToString("\n") { it.value }
            ?: return emptyMap()

        val responses = config.measurementStrategy.parseResponse(lastAssistantText, state.registry)

        if (responses.isEmpty()) return emptyMap()

        // Convert ranked recall into attention scores:
        // rank 1 → score 1.0, rank 2 → 0.5, rank 3 → 0.33, etc.
        return responses
            .filter { it.segmentIndex != null }
            .associate { response ->
                response.segmentIndex!! to (1.0 / response.rankPosition)
            }
    }

    /** Expose current state for testing / observability. */
    fun currentState(): AdaptiveState = state
}
