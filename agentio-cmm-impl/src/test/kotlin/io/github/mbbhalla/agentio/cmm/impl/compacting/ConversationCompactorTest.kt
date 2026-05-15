package io.github.mbbhalla.agentio.cmm.impl.compacting

import aws.sdk.kotlin.services.bedrockruntime.model.ContentBlock
import aws.sdk.kotlin.services.bedrockruntime.model.ConversationRole
import aws.sdk.kotlin.services.bedrockruntime.model.Message
import aws.sdk.kotlin.services.bedrockruntime.model.StopReason
import aws.sdk.kotlin.services.bedrockruntime.model.ToolResultBlock
import aws.sdk.kotlin.services.bedrockruntime.model.ToolResultContentBlock
import aws.sdk.kotlin.services.bedrockruntime.model.ToolResultStatus
import io.github.mbbhalla.agentio.core.model.conversation.AgentTokenUsage
import io.github.mbbhalla.agentio.core.model.conversation.Conversation
import io.github.mbbhalla.agentio.core.model.conversation.MessageEnvelope
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Instant

internal class ConversationCompactorTest {

    private val tokenUsageZero = AgentTokenUsage(
        totalInputTokens = 1000,
        totalOutputTokens = 500,
        lastTurnInputTokens = 200,
        lastTurnOutputTokens = 100,
        lastTurnTotalTokens = 300,
    )

    private fun makeEnvelope(role: ConversationRole, text: String) = MessageEnvelope(
        message = Message {
            this.role = role
            this.content = listOf(ContentBlock.Text(value = text))
        },
        timestamp = Instant.now(),
    )

    private fun makeToolResultEnvelope(toolUseId: String, resultText: String) = MessageEnvelope(
        message = Message {
            this.role = ConversationRole.User
            this.content = listOf(
                ContentBlock.ToolResult(
                    value = ToolResultBlock {
                        this.toolUseId = toolUseId
                        this.status = ToolResultStatus.Success
                        this.content = listOf(
                            ToolResultContentBlock.Text(value = resultText),
                        )
                    },
                ),
            )
        },
        timestamp = Instant.now(),
    )

    private fun buildConversation(messages: List<MessageEnvelope>) = Conversation(
        messages = messages,
        tokenUsage = tokenUsageZero,
        stopReason = StopReason.EndTurn,
        thinkingModeCounter = 0,
    )

    // ── split ──────────────────────────────────────────────────────────

    @Test
    fun `split should separate anchor, middle, and recent`() {
        val messages = listOf(
            makeEnvelope(ConversationRole.User, "initial prompt"),       // 0 anchor
            makeEnvelope(ConversationRole.Assistant, "response 1"),      // 1 middle
            makeEnvelope(ConversationRole.User, "follow up 1"),          // 2 middle
            makeEnvelope(ConversationRole.Assistant, "response 2"),      // 3 middle
            makeEnvelope(ConversationRole.User, "follow up 2"),          // 4 middle
            makeEnvelope(ConversationRole.Assistant, "response 3"),      // 5 recent
            makeEnvelope(ConversationRole.User, "follow up 3"),          // 6 recent
            makeEnvelope(ConversationRole.Assistant, "response 4"),      // 7 recent
            makeEnvelope(ConversationRole.User, "follow up 4"),          // 8 recent
        )
        val conversation = buildConversation(messages)

        val split = ConversationCompactor.split(conversation, preservedTurnPairs = 2)

        assertEquals(1, split.anchor.size)
        assertEquals("initial prompt", textOf(split.anchor.first()))
        assertEquals(4, split.middle.size)
        assertEquals(4, split.recent.size)
        assertTrue(split.isCompactable)
    }

    @Test
    fun `split with small conversation should not be compactable`() {
        val messages = listOf(
            makeEnvelope(ConversationRole.User, "prompt"),
            makeEnvelope(ConversationRole.Assistant, "response"),
            makeEnvelope(ConversationRole.User, "follow up"),
        )
        val conversation = buildConversation(messages)

        val split = ConversationCompactor.split(conversation, preservedTurnPairs = 2)

        assertEquals(1, split.anchor.size)
        assertTrue(split.middle.isEmpty())
        assertEquals(2, split.recent.size)
        assertFalse(split.isCompactable)
    }

    @Test
    fun `split with preservedTurnPairs larger than conversation should preserve all after anchor`() {
        val messages = listOf(
            makeEnvelope(ConversationRole.User, "prompt"),
            makeEnvelope(ConversationRole.Assistant, "response"),
        )
        val conversation = buildConversation(messages)

        val split = ConversationCompactor.split(conversation, preservedTurnPairs = 10)

        assertEquals(1, split.anchor.size)
        assertTrue(split.middle.isEmpty())
        assertEquals(1, split.recent.size)
        assertFalse(split.isCompactable)
    }

    @Test
    fun `split anchor is always the first message`() {
        val messages = listOf(
            makeEnvelope(ConversationRole.User, "system instruction"),
            makeEnvelope(ConversationRole.Assistant, "ack"),
            makeEnvelope(ConversationRole.User, "question"),
            makeEnvelope(ConversationRole.Assistant, "answer"),
            makeEnvelope(ConversationRole.User, "more"),
            makeEnvelope(ConversationRole.Assistant, "more answer"),
            makeEnvelope(ConversationRole.User, "recent q"),
            makeEnvelope(ConversationRole.Assistant, "recent a"),
        )
        val conversation = buildConversation(messages)

        val split = ConversationCompactor.split(conversation, preservedTurnPairs = 1)

        assertEquals("system instruction", textOf(split.anchor.first()))
        assertEquals(5, split.middle.size)
        assertEquals(2, split.recent.size)
        assertEquals("recent q", textOf(split.recent.first()))
    }

    // ── middleToText ───────────────────────────────────────────────────

    @Test
    fun `middleToText should format text messages with roles`() {
        val middle = listOf(
            makeEnvelope(ConversationRole.Assistant, "I found the answer"),
            makeEnvelope(ConversationRole.User, "What is it?"),
        )

        val text = ConversationCompactor.middleToText(middle)

        assertTrue(text.contains("[Assistant]"))
        assertTrue(text.contains("I found the answer"))
        assertTrue(text.contains("[User]"))
        assertTrue(text.contains("What is it?"))
    }

    @Test
    fun `middleToText should include ToolUse names`() {
        val middle = listOf(
            MessageEnvelope(
                message = Message {
                    role = ConversationRole.Assistant
                    content = listOf(
                        ContentBlock.Text(value = "Let me search"),
                        ContentBlock.ToolUse(
                            value = aws.sdk.kotlin.services.bedrockruntime.model.ToolUseBlock {
                                name = "search_tool"
                                toolUseId = "tool_123"
                                input = aws.smithy.kotlin.runtime.content.Document.String("query")
                            },
                        ),
                    )
                },
                timestamp = Instant.now(),
            ),
        )

        val text = ConversationCompactor.middleToText(middle)

        assertTrue(text.contains("[ToolUse: search_tool]"))
        assertTrue(text.contains("Let me search"))
    }

    @Test
    fun `middleToText should include ToolResult content`() {
        val middle = listOf(
            makeToolResultEnvelope("tool_123", "search result data here"),
        )

        val text = ConversationCompactor.middleToText(middle)

        assertTrue(text.contains("[ToolResult: tool_123]")) {
            "Should contain tool use ID, got: $text"
        }
        assertTrue(text.contains("search result data here")) {
            "Should contain tool result content, got: $text"
        }
    }

    @Test
    fun `middleToText with empty list should return empty string`() {
        val text = ConversationCompactor.middleToText(emptyList())
        assertEquals("", text)
    }

    // ── reconstruct ────────────────────────────────────────────────────

    @Test
    fun `reconstruct should maintain valid role alternation when recent starts with User`() {
        val messages = listOf(
            makeEnvelope(ConversationRole.User, "prompt"),
            makeEnvelope(ConversationRole.Assistant, "mid response"),
            makeEnvelope(ConversationRole.User, "mid question"),
            makeEnvelope(ConversationRole.Assistant, "mid answer"),
            makeEnvelope(ConversationRole.User, "recent question"),
            makeEnvelope(ConversationRole.Assistant, "recent answer"),
        )
        val conversation = buildConversation(messages)
        val split = ConversationCompactor.split(conversation, preservedTurnPairs = 1)

        val result = ConversationCompactor.reconstruct(
            split = split,
            summary = "Summary of middle conversation",
            originalConversation = conversation,
        )

        // Verify role alternation: every consecutive pair has different roles
        result.messages.zipWithNext().forEach { (a, b) ->
            assertTrue(a.message.role != b.message.role) {
                "Consecutive messages have same role: ${a.message.role}"
            }
        }

        // Structure: Anchor(U) → Ack(A) → Summary(U) → Ack(A) → Recent(U) → Recent(A)
        assertEquals(6, result.messages.size)
        assertEquals(ConversationRole.User, result.messages[0].message.role)
        assertEquals(ConversationRole.Assistant, result.messages[1].message.role)
        assertEquals(ConversationRole.User, result.messages[2].message.role)
        assertEquals(ConversationRole.Assistant, result.messages[3].message.role)
        assertEquals(ConversationRole.User, result.messages[4].message.role)
        assertEquals(ConversationRole.Assistant, result.messages[5].message.role)
    }

    @Test
    fun `reconstruct should maintain valid role alternation when recent starts with Assistant`() {
        val messages = listOf(
            makeEnvelope(ConversationRole.User, "prompt"),
            makeEnvelope(ConversationRole.Assistant, "mid response"),
            makeEnvelope(ConversationRole.User, "mid question"),
            makeEnvelope(ConversationRole.Assistant, "recent response"),
            makeEnvelope(ConversationRole.User, "recent question"),
        )
        val conversation = buildConversation(messages)

        // preservedTurnPairs=1 → preserve last 2 messages: [Assistant, User]
        val split = ConversationCompactor.split(conversation, preservedTurnPairs = 1)

        val result = ConversationCompactor.reconstruct(
            split = split,
            summary = "Summary",
            originalConversation = conversation,
        )

        // Verify role alternation
        result.messages.zipWithNext().forEach { (a, b) ->
            assertTrue(a.message.role != b.message.role) {
                "Consecutive messages have same role: ${a.message.role}"
            }
        }
    }

    @Test
    fun `reconstruct should contain compaction marker in summary message`() {
        val messages = listOf(
            makeEnvelope(ConversationRole.User, "prompt"),
            makeEnvelope(ConversationRole.Assistant, "mid 1"),
            makeEnvelope(ConversationRole.User, "mid 2"),
            makeEnvelope(ConversationRole.Assistant, "mid 3"),
            makeEnvelope(ConversationRole.User, "recent"),
            makeEnvelope(ConversationRole.Assistant, "recent answer"),
        )
        val conversation = buildConversation(messages)
        val split = ConversationCompactor.split(conversation, preservedTurnPairs = 1)

        val result = ConversationCompactor.reconstruct(
            split = split,
            summary = "This is the compacted summary",
            originalConversation = conversation,
        )

        val summaryText = result.messages
            .flatMap { it.message.content }
            .filterIsInstance<ContentBlock.Text>()
            .map { it.value }
            .find { it.contains("[COMPACTED CONTEXT SUMMARY]") }

        assertTrue(summaryText != null) { "Should contain compaction marker" }
        assertTrue(summaryText!!.contains("This is the compacted summary"))
    }

    @Test
    fun `reconstruct should preserve anchor content`() {
        val messages = listOf(
            makeEnvelope(ConversationRole.User, "original system prompt"),
            makeEnvelope(ConversationRole.Assistant, "mid"),
            makeEnvelope(ConversationRole.User, "mid"),
            makeEnvelope(ConversationRole.Assistant, "recent"),
            makeEnvelope(ConversationRole.User, "recent q"),
        )
        val conversation = buildConversation(messages)
        val split = ConversationCompactor.split(conversation, preservedTurnPairs = 1)

        val result = ConversationCompactor.reconstruct(
            split = split,
            summary = "summary",
            originalConversation = conversation,
        )

        assertEquals("original system prompt", textOf(result.messages.first()))
    }

    @Test
    fun `reconstruct should preserve recent messages verbatim`() {
        val messages = listOf(
            makeEnvelope(ConversationRole.User, "prompt"),
            makeEnvelope(ConversationRole.Assistant, "mid"),
            makeEnvelope(ConversationRole.User, "mid q"),
            makeEnvelope(ConversationRole.Assistant, "mid a"),
            makeEnvelope(ConversationRole.User, "recent question"),
            makeEnvelope(ConversationRole.Assistant, "recent answer"),
        )
        val conversation = buildConversation(messages)
        val split = ConversationCompactor.split(conversation, preservedTurnPairs = 1)

        val result = ConversationCompactor.reconstruct(
            split = split,
            summary = "summary",
            originalConversation = conversation,
        )

        val lastTwo = result.messages.takeLast(2)
        assertEquals("recent question", textOf(lastTwo[0]))
        assertEquals("recent answer", textOf(lastTwo[1]))
    }

    @Test
    fun `reconstruct should reset total token usage`() {
        val messages = listOf(
            makeEnvelope(ConversationRole.User, "prompt"),
            makeEnvelope(ConversationRole.Assistant, "mid"),
            makeEnvelope(ConversationRole.User, "mid q"),
            makeEnvelope(ConversationRole.Assistant, "recent"),
            makeEnvelope(ConversationRole.User, "recent q"),
        )
        val conversation = buildConversation(messages)
        val split = ConversationCompactor.split(conversation, preservedTurnPairs = 1)

        val result = ConversationCompactor.reconstruct(
            split = split,
            summary = "summary",
            originalConversation = conversation,
        )

        assertEquals(0, result.tokenUsage.totalInputTokens)
        assertEquals(0, result.tokenUsage.totalOutputTokens)
        // lastTurn values should be preserved
        assertEquals(
            conversation.tokenUsage.lastTurnInputTokens,
            result.tokenUsage.lastTurnInputTokens,
        )
        assertEquals(
            conversation.tokenUsage.lastTurnOutputTokens,
            result.tokenUsage.lastTurnOutputTokens,
        )
    }

    @Test
    fun `reconstruct should preserve stopReason and thinkingModeCounter`() {
        val messages = listOf(
            makeEnvelope(ConversationRole.User, "prompt"),
            makeEnvelope(ConversationRole.Assistant, "mid"),
            makeEnvelope(ConversationRole.User, "mid q"),
            makeEnvelope(ConversationRole.Assistant, "recent"),
            makeEnvelope(ConversationRole.User, "recent q"),
        )
        val conversation = Conversation(
            messages = messages,
            tokenUsage = tokenUsageZero,
            stopReason = StopReason.ToolUse,
            thinkingModeCounter = 3,
        )
        val split = ConversationCompactor.split(conversation, preservedTurnPairs = 1)

        val result = ConversationCompactor.reconstruct(
            split = split,
            summary = "summary",
            originalConversation = conversation,
        )

        assertTrue(result.stopReason is StopReason.ToolUse)
        assertEquals(3, result.thinkingModeCounter)
    }

    @Test
    fun `reconstruct with empty recent should still produce valid alternation`() {
        // Edge case: preservedTurnPairs = 0 is not allowed by config,
        // but split can produce empty recent if conversation is very short.
        val split = ConversationCompactor.ConversationSplit(
            anchor = listOf(makeEnvelope(ConversationRole.User, "prompt")),
            middle = listOf(
                makeEnvelope(ConversationRole.Assistant, "mid 1"),
                makeEnvelope(ConversationRole.User, "mid 2"),
            ),
            recent = emptyList(),
        )
        val conversation = buildConversation(
            split.anchor + split.middle,
        )

        val result = ConversationCompactor.reconstruct(
            split = split,
            summary = "summary",
            originalConversation = conversation,
        )

        // Should be: Anchor(U) → Ack(A) → Summary(U) → Ack(A)
        result.messages.zipWithNext().forEach { (a, b) ->
            assertTrue(a.message.role != b.message.role) {
                "Consecutive messages have same role"
            }
        }
    }

    // ── buildCompactionInstruction ─────────────────────────────────────

    @Test
    fun `buildCompactionInstruction should include the middle text`() {
        val instruction = ConversationCompactor.buildCompactionInstruction("some conversation text")

        assertTrue(instruction.contains("some conversation text"))
        assertTrue(instruction.contains("RULES"))
        assertTrue(instruction.contains("CONVERSATION TO COMPACT"))
    }

    // ── helpers ────────────────────────────────────────────────────────

    private fun textOf(envelope: MessageEnvelope): String =
        envelope.message.content
            .filterIsInstance<ContentBlock.Text>()
            .joinToString("") { it.value }
}
