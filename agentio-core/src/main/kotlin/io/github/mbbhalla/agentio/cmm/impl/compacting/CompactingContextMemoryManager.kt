package io.github.mbbhalla.agentio.cmm.impl.compacting

import io.github.mbbhalla.agentio.core.common.Description
import io.github.mbbhalla.agentio.core.lib.AbstractAgenticFunction
import io.github.mbbhalla.agentio.core.lib.Instructible
import io.github.mbbhalla.agentio.core.lib.ctx.cmm.ContextMemoryManager
import io.github.mbbhalla.agentio.core.lib.ctx.cmm.ContextMemoryManagers
import io.github.mbbhalla.agentio.core.lib.ctx.cmm.NoOperationContextMemoryManager
import io.github.mbbhalla.agentio.core.lib.tool.EmptyToolsProvider
import io.github.mbbhalla.agentio.core.model.AgentConfiguration
import io.github.mbbhalla.agentio.core.model.ThinkingMode
import io.github.mbbhalla.agentio.core.model.conversation.Conversation
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import kotlin.reflect.KClass

/**
 * Context Memory Manager that compacts conversation history when context usage
 * exceeds a client-specified threshold of the LLM's maximum context window.
 *
 * Compaction is performed by an internal [AbstractAgenticFunction] that uses an LLM
 * to summarize the middle portion of the conversation, preserving the anchor (initial
 * instruction) and recent turns verbatim.
 *
 * The compaction LLM may differ from the main agent's LLM — a cheaper/faster model
 * can be used since summarization is less demanding than the primary task.
 *
 * Usage:
 * ```kotlin
 * val compactingCmm = CompactingContextMemoryManager(
 *     compactionAgentConfiguration = AgentConfiguration(
 *         agentId = "compaction-agent",
 *         languageModelParameters = LanguageModelParameters(
 *             llm = LLM.ANTHROPIC_CLAUDE_OPUS_4_5_V1_CROSS_REGION_INFERENCE,
 *             temperature = Temperature(0.3f),
 *             topP = TopP(0.9f),
 *         ),
 *         bedrockRuntimeClient = bedrockClient,
 *         toolsProvider = EmptyToolsProvider,
 *     ),
 *     compactionConfig = CompactionConfig(threshold = 0.75),
 * )
 *
 * val agentConfig = AgentConfiguration(
 *     ...
 *     contextMemoryManagers = ContextMemoryManagers(
 *         value = listOf(compactingCmm),
 *     ),
 * )
 * ```
 *
 * @param compactionAgentConfiguration Configuration for the internal compaction LLM.
 *   Must NOT include this CMM in its own CMM chain (recursion prevention).
 * @param compactionConfig Client-specified compaction parameters including threshold.
 */
class CompactingContextMemoryManager(
    compactionAgentConfiguration: AgentConfiguration,
    private val compactionConfig: CompactionConfig,
) : AbstractAgenticFunction<
        CompactingContextMemoryManager.CompactionInput,
        CompactingContextMemoryManager.CompactionOutput,
    >(
        // Override the agent config to ensure no recursion and no tools
        compactionAgentConfiguration.copy(
            contextMemoryManagers =
                ContextMemoryManagers(
                    value = listOf(NoOperationContextMemoryManager),
                ),
            toolsProvider = EmptyToolsProvider,
            thinkingMode = ThinkingMode(maxIterations = 0),
            maxTurnLimit = 5,
        ),
    ),
    ContextMemoryManager {
    companion object {
        private val LOG = LoggerFactory.getLogger(CompactingContextMemoryManager::class.java)
    }

    /** Track last compaction turn to enforce [CompactionConfig.minTurnGapBetweenCompactions]. */
    private var lastCompactionTurn: Int = -1

    @Serializable
    data class CompactionInput(
        @field:Description("The conversation text to compact into a summary")
        val conversationText: String,
    ) : Instructible.WithInstruction {
        override fun instructionId() = "compaction"

        override fun instruction() = ConversationCompactor.buildCompactionInstruction(conversationText)

        override fun systemInstruction(): String =
            """
            You are a context compaction assistant. Produce a concise, faithful summary.
            Output ONLY the summary. No JSON tags, no preamble.
            """.trimIndent()
    }

    @Serializable
    data class CompactionOutput(
        @field:Description("The compacted summary of the conversation")
        val summary: String,
    )

    override fun getInputKClass(): KClass<CompactionInput> = CompactionInput::class

    override fun getOutputKClass(): KClass<CompactionOutput> = CompactionOutput::class

    override fun shouldExecuteOnTurn(turnNumber: Int): Boolean = turnNumber > 0 // Skip turn 0 — no token data yet

    override suspend fun getContext(input: ContextMemoryManager.ContextMemoryManagerInput): Conversation {
        val conversation = input.conversation
        val lastInputTokens = conversation.tokenUsage.lastTurnInputTokens

        // No Bedrock call happened this turn (tool result or thinking prompt) — skip
        if (lastInputTokens == 0) {
            LOG.debug("Turn ${input.turnNumber}: skipping compaction — no token data this turn")
            return conversation
        }

        // Check threshold
        val maxTokens = input.llm.maxContextTokens
        val usageRatio = lastInputTokens.toDouble() / maxTokens.toDouble()
        if (usageRatio < compactionConfig.threshold) {
            LOG.debug(
                "Turn ${input.turnNumber}: context usage {}/{} ({}) below threshold {}",
                lastInputTokens,
                maxTokens,
                String.format("%.1f%%", usageRatio * 100),
                compactionConfig.threshold,
            )
            return conversation
        }

        // Enforce minimum turn gap between compactions
        if (lastCompactionTurn >= 0 &&
            (input.turnNumber - lastCompactionTurn) < compactionConfig.minTurnGapBetweenCompactions
        ) {
            LOG.info(
                "Turn ${input.turnNumber}: skipping compaction — " +
                    "last compaction was turn $lastCompactionTurn, " +
                    "min gap is ${compactionConfig.minTurnGapBetweenCompactions}",
            )
            return conversation
        }

        LOG.info(
            "Turn ${input.turnNumber}: triggering compaction — " +
                "context usage {}/{} ({}), threshold {}",
            lastInputTokens,
            maxTokens,
            String.format("%.1f%%", usageRatio * 100),
            compactionConfig.threshold,
        )

        return performCompaction(conversation, input.turnNumber)
    }

    /**
     * Execute the compaction: split conversation, summarize middle via LLM, reconstruct.
     */
    private suspend fun performCompaction(
        conversation: Conversation,
        turnNumber: Int,
    ): Conversation {
        val split =
            ConversationCompactor.split(
                conversation = conversation,
                preservedTurnPairs = compactionConfig.preservedRecentTurns,
            )

        if (!split.isCompactable) {
            LOG.info("Turn $turnNumber: not enough middle content to compact")
            return conversation
        }

        val middleText = ConversationCompactor.middleToText(split.middle)

        LOG.info(
            "Turn $turnNumber: compacting {} middle messages ({} chars) " +
                "while preserving {} anchor + {} recent messages",
            split.middle.size,
            middleText.length,
            split.anchor.size,
            split.recent.size,
        )

        val compactionInput = CompactionInput(conversationText = middleText)
        val result = this.invoke(compactionInput)

        return if (result.isSuccess) {
            val summary = result.getOrThrow().output.summary
            LOG.info(
                "Turn $turnNumber: compaction produced {} char summary from {} char input",
                summary.length,
                middleText.length,
            )
            lastCompactionTurn = turnNumber
            ConversationCompactor.reconstruct(
                split = split,
                summary = summary,
                originalConversation = conversation,
            )
        } else {
            LOG.error(
                "Turn $turnNumber: compaction failed, returning original conversation",
                result.exceptionOrNull(),
            )
            // Fail-safe: return original conversation unchanged
            conversation
        }
    }

    /** Expose last compaction turn for testing / observability. */
    @Suppress("unused")
    fun lastCompactionTurn(): Int = lastCompactionTurn
}
