package io.github.mbbhalla.agentio.eventlistener.impl.checkpoint

import io.github.mbbhalla.agentio.core.lib.event.EventListener
import io.github.mbbhalla.agentio.core.model.event.Event
import io.github.mbbhalla.agentio.core.model.event.EventPayload

class CheckpointingEventListener(
    private val trigger: CheckpointTrigger,
    private val writer: CheckpointWriter,
) : EventListener {

    override suspend fun onEvent(event: Event): Result<Unit> = runCatching {
        val payload = event.payload
        if (payload !is EventPayload.TurnCompleted) return Result.success(Unit)

        if (shouldCheckpoint(payload.turnNumber)) {
            writer.write(
                Checkpoint(
                    agentId = payload.agentId,
                    turnNumber = payload.turnNumber,
                    checkpointTimestamp = java.time.Instant.now(),
                    conversation = payload.conversation,
                ),
            )
        }
    }

    private fun shouldCheckpoint(turnNumber: Int): Boolean = when (trigger) {
        is CheckpointTrigger.EveryNTurns -> turnNumber > 0 && turnNumber % trigger.n == 0
    }
}
