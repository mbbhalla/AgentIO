package io.github.mbbhalla.agentio.eventlistener.impl.checkpoint

fun interface CheckpointWriter {
    suspend fun write(checkpoint: Checkpoint)
}
