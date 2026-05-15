package io.github.mbbhalla.agentio.core.model.serializable

import kotlinx.serialization.Serializable

@Serializable
data class SerializableConversation(
    val messages: List<SerializableMessageEnvelope>,
    val tokenUsage: SerializableTokenUsage,
    val stopReason: String?,
    val thinkingModeCounter: Int,
)

@Serializable
data class SerializableMessageEnvelope(
    val role: String,
    val content: List<SerializableContentBlock>,
    val timestamp: Long,
)

@Serializable
data class SerializableContentBlock(
    val type: String,
    val text: String? = null,
    val toolUseId: String? = null,
    val toolName: String? = null,
    val toolInput: String? = null,
    val toolResultStatus: String? = null,
    val toolResultContent: List<String>? = null,
)

@Serializable
data class SerializableTokenUsage(
    val totalInputTokens: Int,
    val totalOutputTokens: Int,
    val lastTurnInputTokens: Int,
    val lastTurnOutputTokens: Int,
    val lastTurnTotalTokens: Int,
)
