package io.github.mbbhalla.agentio.core.model.conversation

import aws.sdk.kotlin.services.bedrockruntime.model.ContentBlock
import aws.sdk.kotlin.services.bedrockruntime.model.ConversationRole
import aws.sdk.kotlin.services.bedrockruntime.model.Message
import aws.sdk.kotlin.services.bedrockruntime.model.StopReason
import aws.sdk.kotlin.services.bedrockruntime.model.TokenUsage
import java.time.Instant

data class AgentTokenUsage(
    val totalInputTokens: Int,
    val totalOutputTokens: Int,
    val lastTurnInputTokens: Int,
    val lastTurnOutputTokens: Int,
    val lastTurnTotalTokens: Int,
)

data class MessageEnvelope(
    val message: Message,
    val timestamp: Instant,
)

data class Conversation(
    // Messages accumulated in this conversation
    val messages: List<MessageEnvelope>,
    // Token usage accumulated in this conversation
    val tokenUsage: AgentTokenUsage,
    /*
        This is an attribute in Converse response
        included here to track model stop reason as the
        conversation object is generated

        Nullable since in cases where the Message originated
        from User role, then StopReason does not make sense
     */
    val stopReason: StopReason?,
    /*
        Thinking mode counter
        how many times thinking have been done
     */
    val thinkingModeCounter: Int,
) {
    companion object {
        /**
         * The content block types the framework understands and can process.
         * This is the single source of truth — [isSupportedContentBlock] and
         * [supportedContentBlockTypeNames] both derive from this set.
         */
        val SUPPORTED_CONTENT_BLOCK_TYPES: Set<kotlin.reflect.KClass<out ContentBlock>> =
            setOf(
                ContentBlock.Text::class,
                ContentBlock.ToolUse::class,
                ContentBlock.ToolResult::class,
                ContentBlock.ReasoningContent::class,
                // Add new supported types here
            )

        /**
         * Whether a [ContentBlock] is a type the framework understands and can process.
         * Use this to filter raw Bedrock responses before adding to a Conversation.
         */
        fun isSupportedContentBlock(contentBlock: ContentBlock): Boolean = SUPPORTED_CONTENT_BLOCK_TYPES.any { it.isInstance(contentBlock) }

        /**
         * Human-readable names of supported content block types. Used in error messages.
         */
        val supportedContentBlockTypeNames: String =
            SUPPORTED_CONTENT_BLOCK_TYPES.joinToString(", ") { it.simpleName ?: "Unknown" }

        val TOKEN_USAGE_ZERO =
            TokenUsage {
                // initialized values
                this.inputTokens = 0
                this.outputTokens = 0
                this.totalTokens = 0
            }

        private val AGENT_TOKEN_USAGE_ZERO =
            AgentTokenUsage(
                // initialized values
                totalInputTokens = 0,
                totalOutputTokens = 0,
                lastTurnInputTokens = 0,
                lastTurnOutputTokens = 0,
                lastTurnTotalTokens = 0,
            )

        /*
            Initializes with a User content
            which is the start of the Conversation
         */
        fun initialize(texts: List<String>): Conversation =
            Conversation(
                messages =
                    listOf(
                        MessageEnvelope(
                            message =
                                Message {
                                    this.role = ConversationRole.User
                                    this.content =
                                        texts.map { text ->
                                            ContentBlock.Text(
                                                value = text,
                                            )
                                        }
                                },
                            timestamp = Instant.now(),
                        ),
                    ),
                tokenUsage = AGENT_TOKEN_USAGE_ZERO,
                stopReason = null,
                thinkingModeCounter = 0,
            )
    }

    private fun appendRoleContent(
        role: ConversationRole,
        contentBlocks: List<ContentBlock>,
        additionalTokenUsage: TokenUsage,
        stopReason: StopReason?,
        incrementThinkingModeCounter: Int,
    ): Conversation =
        Conversation(
            messages =
                this.messages +
                    MessageEnvelope(
                        message =
                            Message {
                                this.role = role
                                this.content = contentBlocks
                            },
                        timestamp = Instant.now(),
                    ),
            tokenUsage =
                AgentTokenUsage(
                    totalInputTokens = tokenUsage.totalInputTokens + additionalTokenUsage.inputTokens,
                    totalOutputTokens = tokenUsage.totalOutputTokens + additionalTokenUsage.outputTokens,
                    lastTurnInputTokens = additionalTokenUsage.inputTokens,
                    lastTurnOutputTokens = additionalTokenUsage.outputTokens,
                    lastTurnTotalTokens = additionalTokenUsage.totalTokens,
                ),
            stopReason = stopReason,
            thinkingModeCounter = this.thinkingModeCounter + incrementThinkingModeCounter,
        )

    fun appendUserRoleContent(
        contentBlock: ContentBlock,
        additionalTokenUsage: TokenUsage,
        incrementThinkingModeCounter: Int,
    ) = appendUserRoleContents(
        contentBlocks = listOf(contentBlock),
        additionalTokenUsage = additionalTokenUsage,
        incrementThinkingModeCounter = incrementThinkingModeCounter,
    )

    fun appendUserRoleContents(
        contentBlocks: List<ContentBlock>,
        additionalTokenUsage: TokenUsage,
        incrementThinkingModeCounter: Int,
    ) = this.appendRoleContent(
        role = ConversationRole.User,
        /*
            From user multiple contents can be emitted
            For example an initial message, or multiple Tool Results
            when the model requests parallel tool calls
         */
        contentBlocks = contentBlocks,
        additionalTokenUsage = additionalTokenUsage,
        stopReason = null,
        incrementThinkingModeCounter = incrementThinkingModeCounter,
    )

    fun appendAssistantRoleContent(
        contentBlocks: List<ContentBlock>,
        additionalTokenUsage: TokenUsage,
        stopReason: StopReason,
    ) = this.appendRoleContent(
        role = ConversationRole.Assistant,
        /*
            From assistant multiple contents can be emitted
            For example - reasoning, reasoning, tool use ...
         */
        contentBlocks = contentBlocks,
        additionalTokenUsage = additionalTokenUsage,
        stopReason = stopReason,
        incrementThinkingModeCounter = 0,
    )

    fun lastMessage() = this.messages.last()

    fun converseMessages() = this.messages.map { it.message }

    init {
        require(messages.isNotEmpty()) { "Conversation cannot be empty" }
        require(tokenUsage.totalInputTokens >= 0) { "totalInputTokens must be >= 0" }
        require(tokenUsage.totalOutputTokens >= 0) { "totalOutputTokens must be >= 0" }
        require(tokenUsage.lastTurnInputTokens >= 0) { "lastTurnInputTokens must be >= 0" }
        require(tokenUsage.lastTurnOutputTokens >= 0) { "lastTurnOutputTokens must be >= 0" }
        require(tokenUsage.lastTurnTotalTokens >= 0) { "lastTurnTotalTokens must be >= 0" }

        // At least one ContentBlock in each Message
        require(messages.map { it.message }.all { it.content.isNotEmpty() }) {
            "Conversation messages content cannot be empty"
        }

        // ContentBlock are valid for the use-case
        require(
            messages.map { it.message }.flatMap { it.content }.all { contentBlock ->
                isSupportedContentBlock(contentBlock)
            },
        ) {
            "Conversation messages content is not valid type"
        }
    }
}
