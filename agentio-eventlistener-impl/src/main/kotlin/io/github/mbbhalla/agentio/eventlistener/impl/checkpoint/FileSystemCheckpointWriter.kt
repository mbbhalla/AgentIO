package io.github.mbbhalla.agentio.eventlistener.impl.checkpoint

import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

class FileSystemCheckpointWriter(
    private val directory: Path,
) : CheckpointWriter {

    override suspend fun write(checkpoint: Checkpoint) {
        directory.createDirectories()
        val fileName = "checkpoint_snapshot_${checkpoint.agentId}_turn_${checkpoint.turnNumber}.json"
        val filePath = directory.resolve(fileName)
        val content = CheckpointSerializer.serialize(checkpoint)
        filePath.writeText(content)
    }
}
