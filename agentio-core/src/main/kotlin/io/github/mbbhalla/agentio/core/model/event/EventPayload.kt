package io.github.mbbhalla.agentio.core.model.event

import aws.sdk.kotlin.services.bedrockruntime.model.StopReason
import kotlin.time.Duration

sealed class EventPayload {

    data class AgentInvocationStart(
        val agentId: String,
        val instructionId: String,
        val instruction: String,
    ) : EventPayload()

    data class AgentInvocationEnd(
        val agentId: String,
        val instructionId: String,
        val totalTurns: Int,
        val totalInputTokens: Int,
        val totalOutputTokens: Int,
        val success: Boolean,
        val error: Throwable?,
        val latency: Duration,
    ) : EventPayload()

    data class BeforeLlmCall(
        val modelId: String,
        val messageCount: Int,
        val turnNumber: Int,
    ) : EventPayload()

    data class AfterLlmCall(
        val modelId: String,
        val stopReason: StopReason?,
        val inputTokens: Int,
        val outputTokens: Int,
        val latency: Duration,
        val error: Throwable?,
    ) : EventPayload()

    data class BeforeToolCall(
        val toolName: String,
        val toolInput: Any,
        val turnNumber: Int,
    ) : EventPayload()

    data class AfterToolCall(
        val toolName: String,
        val toolInput: Any,
        val toolResult: Any,
        val latency: Duration,
        val error: Throwable?,
    ) : EventPayload()
}
