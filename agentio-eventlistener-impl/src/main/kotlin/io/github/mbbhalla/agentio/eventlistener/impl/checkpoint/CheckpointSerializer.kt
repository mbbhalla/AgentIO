package io.github.mbbhalla.agentio.eventlistener.impl.checkpoint

import aws.sdk.kotlin.services.bedrockruntime.model.ContentBlock
import io.github.mbbhalla.agentio.core.model.conversation.Conversation
import io.github.mbbhalla.agentio.core.model.serializable.SerializableContentBlock
import io.github.mbbhalla.agentio.core.model.serializable.SerializableConversation
import io.github.mbbhalla.agentio.core.model.serializable.SerializableMessageEnvelope
import io.github.mbbhalla.agentio.core.model.serializable.SerializableTokenUsage
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

object CheckpointSerializer {

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
    }

    fun serialize(checkpoint: Checkpoint): String {
        val serializable = checkpoint.toSerializable()
        return json.encodeToString(serializable)
    }

    private fun Checkpoint.toSerializable(): SerializableCheckpoint =
        SerializableCheckpoint(
            agentId = agentId,
            turnNumber = turnNumber,
            checkpointTimestamp = checkpointTimestamp.toEpochMilli(),
            conversation = conversation.toSerializable(),
        )

    private fun Conversation.toSerializable(): SerializableConversation =
        SerializableConversation(
            messages = messages.map { envelope ->
                SerializableMessageEnvelope(
                    role = envelope.message.role.value,
                    content = envelope.message.content.mapNotNull { it.toSerializable() },
                    timestamp = envelope.timestamp.toEpochMilli(),
                )
            },
            tokenUsage = SerializableTokenUsage(
                totalInputTokens = tokenUsage.totalInputTokens,
                totalOutputTokens = tokenUsage.totalOutputTokens,
                lastTurnInputTokens = tokenUsage.lastTurnInputTokens,
                lastTurnOutputTokens = tokenUsage.lastTurnOutputTokens,
                lastTurnTotalTokens = tokenUsage.lastTurnTotalTokens,
            ),
            stopReason = stopReason?.value,
            thinkingModeCounter = thinkingModeCounter,
        )

    private fun ContentBlock.toSerializable(): SerializableContentBlock? = when (this) {
        is ContentBlock.Text -> SerializableContentBlock(
            type = "text",
            text = value,
        )
        is ContentBlock.ToolUse -> SerializableContentBlock(
            type = "toolUse",
            toolUseId = value.toolUseId,
            toolName = value.name,
            toolInput = value.input?.toString(),
        )
        is ContentBlock.ToolResult -> SerializableContentBlock(
            type = "toolResult",
            toolUseId = value.toolUseId,
            toolResultStatus = value.status?.value,
            toolResultContent = value.content?.mapNotNull { block ->
                when (block) {
                    is aws.sdk.kotlin.services.bedrockruntime.model.ToolResultContentBlock.Text ->
                        block.value
                    else -> null
                }
            },
        )
        is ContentBlock.ReasoningContent -> SerializableContentBlock(
            type = "reasoningContent",
            text = value.asReasoningTextOrNull()?.text,
        )
        else -> null
    }
}

@Serializable
internal data class SerializableCheckpoint(
    val agentId: String,
    val turnNumber: Int,
    val checkpointTimestamp: Long,
    val conversation: SerializableConversation,
)
