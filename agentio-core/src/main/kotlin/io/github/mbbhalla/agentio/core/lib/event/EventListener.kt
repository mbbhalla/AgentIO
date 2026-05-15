package io.github.mbbhalla.agentio.core.lib.event

import io.github.mbbhalla.agentio.core.model.event.Event

/**
 * Receives lifecycle events emitted during agent execution.
 *
 * Implementations **must be thread-safe**. [onEvent] may be called concurrently
 * from multiple coroutines — for example, when the model requests parallel tool
 * calls, each tool emits events from its own coroutine.
 */
fun interface EventListener {
    suspend fun onEvent(event: Event): Result<Unit>
}
