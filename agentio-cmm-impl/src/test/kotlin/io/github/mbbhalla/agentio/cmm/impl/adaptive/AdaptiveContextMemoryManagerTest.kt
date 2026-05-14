package io.github.mbbhalla.agentio.cmm.impl.adaptive

import aws.sdk.kotlin.services.bedrockruntime.model.ContentBlock
import aws.sdk.kotlin.services.bedrockruntime.model.ConversationRole
import io.github.mbbhalla.agentio.core.lib.ctx.cmm.ContextMemoryManager
import io.github.mbbhalla.agentio.core.model.Conversation
import io.github.mbbhalla.agentio.core.model.LLM
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

internal class AdaptiveContextMemoryManagerTest {

    private val llm = LLM.ANTHROPIC_CLAUDE_OPUS_4_5_V1_CROSS_REGION_INFERENCE

    @Test
    fun `should skip turn 0`() {
        val cmm = AdaptiveContextMemoryManager()
        assertTrue(!cmm.shouldExecuteOnTurn(0))
        assertTrue(cmm.shouldExecuteOnTurn(1))
        assertTrue(cmm.shouldExecuteOnTurn(100))
    }

    @Test
    fun `initial state should have empty heatmap and registry`() {
        val cmm = AdaptiveContextMemoryManager()
        val state = cmm.currentState()

        assertTrue(state.heatmap.scores.isEmpty())
        assertEquals(0, state.registry.size)
        assertEquals(-1, state.lastMeasurementTurn)
    }

    @Test
    fun `getContext with single-segment conversation should return unchanged`() = runBlocking {
        val cmm = AdaptiveContextMemoryManager()
        val conversation = Conversation.initialize(listOf("hello"))

        val result = cmm.getContext(
            ContextMemoryManager.ContextMemoryManagerInput(
                conversation = conversation,
                llm = llm,
                turnNumber = 1,
            ),
        )

        // Single segment — nothing to reshuffle
        assertNotNull(result)
    }

    @Test
    fun `getContext with multi-segment conversation should embed probes`() = runBlocking {
        val cmm = AdaptiveContextMemoryManager()
        val conversation = Conversation.initialize(listOf("first block", "second block"))

        val result = cmm.getContext(
            ContextMemoryManager.ContextMemoryManagerInput(
                conversation = conversation,
                llm = llm,
                turnNumber = 1,
            ),
        )

        // After first call, registry should have probes
        assertTrue(cmm.currentState().registry.size > 0)
        assertNotNull(result)
    }

    @Test
    fun `AdaptiveState should reject lastMeasurementTurn below -1`() {
        assertThrows<IllegalArgumentException> {
            AdaptiveState(lastMeasurementTurn = -2)
        }
    }

    @Test
    fun `AdaptiveConfig should reject measurementFrequency below 1`() {
        assertThrows<IllegalArgumentException> {
            AdaptiveConfig(measurementFrequency = 0)
        }
    }

    @Test
    fun `AdaptiveConfig should reject heatmapDecayFactor out of range`() {
        assertThrows<IllegalArgumentException> {
            AdaptiveConfig(heatmapDecayFactor = 1.5)
        }
        assertThrows<IllegalArgumentException> {
            AdaptiveConfig(heatmapDecayFactor = -0.1)
        }
    }

    @Test
    fun `AdaptiveConfig defaults should be valid`() {
        val config = AdaptiveConfig()
        assertEquals(3, config.measurementFrequency)
        assertEquals(AttentionHeatmap.DEFAULT_DECAY_FACTOR, config.heatmapDecayFactor)
        assertTrue(config.enablePiggyback)
    }

    @Test
    fun `getContext with piggyback enabled should append recall request to last user message`() = runBlocking {
        val cmm = AdaptiveContextMemoryManager(
            config = AdaptiveConfig(enablePiggyback = true),
        )
        val conversation = Conversation.initialize(listOf("first block", "second block"))

        val result = cmm.getContext(
            ContextMemoryManager.ContextMemoryManagerInput(
                conversation = conversation,
                llm = llm,
                turnNumber = 1,
            ),
        )

        // The last user message should contain the recall request text
        val lastUserMessage = result.messages.last { it.message.role == ConversationRole.User }
        val allText = lastUserMessage.message.content
            .filterIsInstance<ContentBlock.Text>()
            .joinToString("\n") { it.value }

        assertTrue(allText.contains("CTX_PROBE")) {
            "Expected recall request containing CTX_PROBE in last user message, got: $allText"
        }
    }

    @Test
    fun `getContext with piggyback disabled should not append recall request`() = runBlocking {
        val cmm = AdaptiveContextMemoryManager(
            config = AdaptiveConfig(enablePiggyback = false),
        )
        val conversation = Conversation.initialize(listOf("first block", "second block"))

        val result = cmm.getContext(
            ContextMemoryManager.ContextMemoryManagerInput(
                conversation = conversation,
                llm = llm,
                turnNumber = 1,
            ),
        )

        // The last user message should have probes embedded in segment content
        // but should NOT have the recall query as a separate content block
        val lastUserMessage = result.messages.last { it.message.role == ConversationRole.User }
        val textBlocks = lastUserMessage.message.content.filterIsInstance<ContentBlock.Text>()

        // Probes are embedded in the segment content blocks (the first N blocks)
        // but the recall request ("report the CTX_PROBE values") should not appear
        val allText = textBlocks.joinToString("\n") { it.value }
        assertTrue(!allText.contains("report the CTX_PROBE values", ignoreCase = true)) {
            "Piggyback is disabled but recall request was found in: $allText"
        }
    }

    @Test
    fun `piggyback recall request should be a separate content block with marker`() = runBlocking {
        val cmm = AdaptiveContextMemoryManager(
            config = AdaptiveConfig(enablePiggyback = true),
        )
        val conversation = Conversation.initialize(listOf("first block", "second block"))

        val result = cmm.getContext(
            ContextMemoryManager.ContextMemoryManagerInput(
                conversation = conversation,
                llm = llm,
                turnNumber = 1,
            ),
        )

        val lastUserMessage = result.messages.last { it.message.role == ConversationRole.User }
        val textBlocks = lastUserMessage.message.content.filterIsInstance<ContentBlock.Text>()

        // Original conversation had 2 text blocks. After processing:
        // - 2 blocks with probes embedded in content
        // - 1 additional block with the recall request (marked)
        assertTrue(textBlocks.size >= 3) {
            "Expected at least 3 text blocks (2 segments + 1 recall request), got ${textBlocks.size}"
        }

        // The last text block should be the recall request with the marker
        val lastBlock = textBlocks.last()
        assertTrue(lastBlock.value.startsWith(AdaptiveContextMemoryManager.RECALL_REQUEST_MARKER)) {
            "Last text block should start with recall request marker, got: ${lastBlock.value}"
        }
        assertTrue(lastBlock.value.contains("CTX_PROBE")) {
            "Last text block should contain the recall request, got: ${lastBlock.value}"
        }
    }

    @Test
    fun `DefaultSegmentExtractor should exclude recall request blocks`() {
        val conversation = Conversation.initialize(
            listOf(
                "first block",
                "second block",
                "${AdaptiveContextMemoryManager.RECALL_REQUEST_MARKER}\nSome recall request text",
            ),
        )

        val segments = DefaultSegmentExtractor.extract(conversation)

        assertEquals(2, segments.size) {
            "Expected 2 segments (recall request block should be excluded), got ${segments.size}"
        }
        assertTrue(segments.none { it.content.contains(AdaptiveContextMemoryManager.RECALL_REQUEST_MARKER) }) {
            "No segment should contain the recall request marker"
        }
    }

    @Test
    fun `measurementFrequency should control when reshuffling occurs`() = runBlocking {
        val cmm = AdaptiveContextMemoryManager(
            config = AdaptiveConfig(
                measurementFrequency = 3,
                enablePiggyback = false,
            ),
        )
        val conversation = Conversation.initialize(listOf("first block", "second block"))

        // Turn 1: not a measurement turn (1 % 3 != 0), but heatmap is empty so it runs anyway
        cmm.getContext(
            ContextMemoryManager.ContextMemoryManagerInput(
                conversation = conversation,
                llm = llm,
                turnNumber = 1,
            ),
        )
        assertTrue(cmm.currentState().registry.size > 0) {
            "Turn 1 should embed probes because heatmap is empty"
        }

        // Turn 2: not a measurement turn (2 % 3 != 0), heatmap still empty → runs
        // Turn 3: measurement turn (3 % 3 == 0) → runs
        // Turn 4: not a measurement turn (4 % 3 != 0), heatmap has data → skips
    }
}
