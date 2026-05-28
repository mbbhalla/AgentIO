package io.github.mbbhalla.agentio.eventlistener.impl.checkpoint

import io.github.mbbhalla.agentio.core.model.conversation.Conversation
import java.time.Instant

data class Checkpoint(
    val agentId: String,
    val turnNumber: Int,
    val checkpointTimestamp: Instant,
    val conversation: Conversation,
)
