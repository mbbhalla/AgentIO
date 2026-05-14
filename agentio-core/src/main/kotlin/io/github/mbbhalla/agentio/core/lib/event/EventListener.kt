package io.github.mbbhalla.agentio.core.lib.event

import io.github.mbbhalla.agentio.core.model.event.Event

fun interface EventListener {
    fun onEvent(event: Event)
}
