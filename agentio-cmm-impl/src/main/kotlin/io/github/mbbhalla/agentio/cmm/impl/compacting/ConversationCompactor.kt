package io.github.mbbhalla.agentio.cmm.impl.compacting

import aws.sdk.kotlin.services.bedrockruntime.model.ContentBlock
import aws.sdk.kotlin.services.bedrockruntime.model.ConversationRole
import aws.sdk.kotlin.services.bedrockruntime.model.Message
import io.github.mbbhalla.agentio.core.model.conversation.AgentTokenUsage
import io.github.mbbhalla.agentio.core.model.conversation.Conversation
import io.github.mbbhalla.agentio.core.model.conversation.MessageEnvelope
import java.time.Instant

/**
 * Pure functions for splitting a [Conversation] into compactable regions
 * and reconstructing a valid [Conversation] from a compacted summary.
 *
 * A conversation is split into three regions:
 * ```
 * [Anchor] + [Middle (compactable)] + [Recent (preserved)]
 * ```
 *
 * - Anchor: The first User message (initial instruction/system prompt). Never compacted.
 * - Middle: All turns between anchor and recent. Summarized by the compaction LLM.
 * - Recent: The last N turn pairs. Preserved verbatim for recency coherence.
 */
object ConversationCompactor {

    private const val COMPACTION_MARKER = "[COMPACTED CONTEXT SUMMARY]"
    private const val COMPACTION_ACK = "Understood. I have the compacted context summary above and will continue from here."

    /**
     * Result of splitting a conversation into compactable regions.
     *
     * @property anchor The first message (always User role). Never compacted.
     * @property middle Messages between anchor and recent. These get summarized.
     * @property recent The last "preservedTurnPairs" * 2 messages. Preserved verbatim.
     */
    data class ConversationSplit(
        val anchor: List<MessageEnvelope>,
        val middle: List<MessageEnvelope>,
        val recent: List<MessageEnvelope>,
    ) {
        /** Whether there is enough middle content to justify compaction. */
        val isCompactable: Boolean get() = middle.size >= 2
    }

    /**
     * Split a conversation into anchor, middle, and recent regions.
     *
     * @param conversation The full conversation to split.
     * @param preservedTurnPairs Number of recent turn pairs (User+Assistant) to preserve.
     *   Actual preserved message count = preservedTurnPairs * 2.
     */
    fun split(
        conversation: Conversation,
        preservedTurnPairs: Int,
    ): ConversationSplit {
        val messages = conversation.messages
        val preservedMessageCount = (preservedTurnPairs * 2).coerceAtMost(messages.size - 1)

        // Anchor is always the first message
        val anchor = listOf(messages.first())

        // Recent is the tail
        val recentStartIndex = (messages.size - preservedMessageCount).coerceAtLeast(1)
        val recent = messages.subList(recentStartIndex, messages.size)

        // Middle is everything between anchor and recent
        val middle = if (recentStartIndex > 1) {
            messages.subList(1, recentStartIndex)
        } else {
            emptyList()
        }

        return ConversationSplit(
            anchor = anchor,
            middle = middle,
            recent = recent,
        )
    }

    /**
     * Extract the text content from middle messages for the compaction LLM to summarize.
     * Formats each message with its role for context.
     */
    fun middleToText(middle: List<MessageEnvelope>): String {
        return middle.joinToString("\n\n") { envelope ->
            val role = when (envelope.message.role) {
                is ConversationRole.User -> "User"
                is ConversationRole.Assistant -> "Assistant"
                else -> "Unknown"
            }
            val text = envelope.message.content
                .filterIsInstance<ContentBlock.Text>()
                .joinToString("\n") { it.value }
            val toolUses = envelope.message.content
                .filterIsInstance<ContentBlock.ToolUse>()
                .joinToString("\n") { "[ToolUse: ${it.value.name}]" }
            val toolResults = envelope.message.content
                .filterIsInstance<ContentBlock.ToolResult>()
                .joinToString("\n") { block ->
                    val resultContent = block.value.content
                        .joinToString("\n") { resultBlock ->
                            when (resultBlock) {
                                is aws.sdk.kotlin.services.bedrockruntime.model
                                .ToolResultContentBlock.Text -> resultBlock.value

                                is aws.sdk.kotlin.services.bedrockruntime.model
                                .ToolResultContentBlock.Json -> resultBlock.value.toString()

                                else -> ""
                            }
                        }
                    "[ToolResult: ${block.value.toolUseId}]\n$resultContent"
                }

            buildString {
                append("[$role]")
                if (text.isNotBlank()) append("\n$text")
                if (toolUses.isNotBlank()) append("\n$toolUses")
                if (toolResults.isNotBlank()) append("\n$toolResults")
            }
        }
    }

    /**
     * Reconstruct a valid [Conversation] from the compacted summary and preserved regions.
     *
     * The resulting conversation maintains valid User→Assistant role alternation:
     * ```
     * [Anchor(User)] + [Ack(Assistant)] + [Summary(User)] + [Ack(Assistant)] + [Recent...]
     * ```
     *
     * An Assistant ack is always inserted between Anchor and Summary to prevent
     * consecutive User messages. A second ack is inserted between Summary and Recent
     * only if Recent starts with a User message (or is empty).
     *
     * Token usage totals are reset since the compacted conversation has a different
     * token footprint. Last-turn values are preserved for the next threshold check.
     *
     * @param split The original conversation split.
     * @param summary The compacted summary text produced by the compaction LLM.
     * @param originalConversation The original conversation (for token usage and state carry-over).
     */
    fun reconstruct(
        split: ConversationSplit,
        summary: String,
        originalConversation: Conversation,
    ): Conversation {
        val summaryEnvelope = MessageEnvelope(
            message = Message {
                role = ConversationRole.User
                content = listOf(
                    ContentBlock.Text(
                        value = "$COMPACTION_MARKER\n$summary",
                    ),
                )
            },
            timestamp = Instant.now(),
        )

        val ackEnvelope = MessageEnvelope(
            message = Message {
                role = ConversationRole.Assistant
                content = listOf(
                    ContentBlock.Text(value = COMPACTION_ACK),
                )
            },
            timestamp = Instant.now(),
        )

        // Build the reconstructed conversation maintaining valid role alternation.
        // Anchor is always User. Summary is User. We need an Assistant ack between
        // anchor and summary to maintain User→Assistant→User alternation.
        // Then, if recent starts with User, we need another ack after summary.
        // If recent starts with Assistant, summary(User) → recent(Assistant) is valid.
        val recentStartsWithUser = split.recent.firstOrNull()
            ?.message?.role is ConversationRole.User
        val needsAckBeforeRecent = recentStartsWithUser || split.recent.isEmpty()

        val anchorToMiddleAck = MessageEnvelope(
            message = Message {
                role = ConversationRole.Assistant
                content = listOf(
                    ContentBlock.Text(
                        value = "Understood. Continuing with the task.",
                    ),
                )
            },
            timestamp = Instant.now(),
        )

        val messages = buildList {
            addAll(split.anchor)
            add(anchorToMiddleAck) // Anchor(User) → Ack(Assistant) → Summary(User)
            add(summaryEnvelope)
            if (needsAckBeforeRecent) add(ackEnvelope) // Summary(User) → Ack(Assistant) → Recent(User)
            addAll(split.recent)
        }

        // Validate role alternation
        require(messages.zipWithNext().all { (a, b) -> a.message.role != b.message.role }) {
            "Reconstructed conversation has consecutive messages with the same role"
        }

        return Conversation(
            messages = messages,
            // Reset token usage: totals are no longer meaningful after compaction
            // since the middle was replaced with a summary. lastTurn values are
            // preserved so the next threshold check uses the pre-compaction signal,
            // but the actual token count will be recalculated on the next Bedrock call.
            tokenUsage = AgentTokenUsage(
                totalInputTokens = 0,
                totalOutputTokens = 0,
                lastTurnInputTokens = originalConversation.tokenUsage.lastTurnInputTokens,
                lastTurnOutputTokens = originalConversation.tokenUsage.lastTurnOutputTokens,
                lastTurnTotalTokens = originalConversation.tokenUsage.lastTurnTotalTokens,
            ),
            stopReason = originalConversation.stopReason,
            thinkingModeCounter = originalConversation.thinkingModeCounter,
        )
    }

    /**
     * Build the compaction instruction for the compaction LLM.
     */
    fun buildCompactionInstruction(middleText: String): String = """
        You are a context compaction assistant. Your job is to produce a concise, 
        faithful summary of the following conversation history. 
        
        RULES:
        1. Preserve ALL key decisions, conclusions, and action items.
        2. Preserve ALL tool call results and their outcomes.
        3. Preserve ALL error messages, exceptions, and their resolutions.
        4. Remove redundant back-and-forth, pleasantries, and verbose explanations.
        5. Keep technical details (variable names, file paths, code snippets) that are 
           referenced in later turns.
        6. Output ONLY the summary text. No preamble, no explanation of what you did.
        
        CONVERSATION TO COMPACT:
        
        $middleText
    """.trimIndent()
}
