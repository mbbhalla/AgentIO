package io.github.mbbhalla.agentio.core.lib.event

import io.github.mbbhalla.agentio.core.model.event.Event
import org.slf4j.LoggerFactory

data class EventListeners(val value: Set<EventListener> = emptySet()) {

    companion object {
        private val LOG = LoggerFactory.getLogger(EventListeners::class.java)
    }

    suspend fun dispatch(event: Event) {
        value.forEach { listener ->
            listener.onEvent(event)
                .onSuccess {
                    LOG.info("EventListener succeeded: {}", listener::class.simpleName)
                }
                .onFailure { e ->
                    LOG.error("EventListener failed: {}", listener::class.simpleName, e)
                }
        }
    }
}
