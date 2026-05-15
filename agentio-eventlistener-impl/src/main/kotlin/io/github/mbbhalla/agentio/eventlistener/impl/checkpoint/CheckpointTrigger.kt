package io.github.mbbhalla.agentio.eventlistener.impl.checkpoint

sealed class CheckpointTrigger {

    data class EveryNTurns(val n: Int) : CheckpointTrigger() {
        init {
            require(n > 0) { "n must be > 0, was $n" }
        }
    }
}
