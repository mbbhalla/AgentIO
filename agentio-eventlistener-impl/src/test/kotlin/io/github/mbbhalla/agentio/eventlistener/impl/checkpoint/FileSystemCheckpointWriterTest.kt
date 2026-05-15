package io.github.mbbhalla.agentio.eventlistener.impl.checkpoint

import io.github.mbbhalla.agentio.core.model.conversation.Conversation
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Instant
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.readText

internal class FileSystemCheckpointWriterTest {

    @TempDir
    lateinit var tempDir: Path

    private fun createCheckpoint(agentId: String, turnNumber: Int): Checkpoint = Checkpoint(
        agentId = agentId,
        turnNumber = turnNumber,
        checkpointTimestamp = Instant.ofEpochMilli(1700000000000L),
        conversation = Conversation.initialize(listOf("Turn $turnNumber message")),
    )

    @Test
    fun `should write checkpoint file with correct name for EveryNTurns`() = runBlocking {
        // Given
        val writer = FileSystemCheckpointWriter(directory = tempDir)
        val checkpoint = createCheckpoint("my-agent", 5)

        // When
        writer.write(checkpoint)

        // Then
        val expectedFile = tempDir.resolve("checkpoint_snapshot_my-agent_turn_5.json")
        assertTrue(expectedFile.exists())
    }

    @Test
    fun `should write valid JSON content`() = runBlocking {
        // Given
        val writer = FileSystemCheckpointWriter(directory = tempDir)
        val checkpoint = createCheckpoint("agent-json", 3)

        // When
        writer.write(checkpoint)

        // Then
        val file = tempDir.resolve("checkpoint_snapshot_agent-json_turn_3.json")
        val content = file.readText()
        assertTrue(content.contains("\"agentId\": \"agent-json\""))
        assertTrue(content.contains("\"turnNumber\": 3"))
        assertTrue(content.contains("Turn 3 message"))
    }

    @Test
    fun `should create directory if it does not exist`() = runBlocking {
        // Given
        val nestedDir = tempDir.resolve("sub/dir/checkpoints")
        val writer = FileSystemCheckpointWriter(directory = nestedDir)
        val checkpoint = createCheckpoint("agent-nested", 1)

        // When
        writer.write(checkpoint)

        // Then
        assertTrue(nestedDir.exists())
        val files = nestedDir.listDirectoryEntries("*.json")
        assertEquals(1, files.size)
    }

    @Test
    fun `should write multiple checkpoint files without overwriting`() = runBlocking {
        // Given
        val writer = FileSystemCheckpointWriter(directory = tempDir)

        // When
        writer.write(createCheckpoint("agent-multi", 3))
        writer.write(createCheckpoint("agent-multi", 6))
        writer.write(createCheckpoint("agent-multi", 9))

        // Then
        val files = tempDir.listDirectoryEntries("*.json")
        assertEquals(3, files.size)
        assertTrue(tempDir.resolve("checkpoint_snapshot_agent-multi_turn_3.json").exists())
        assertTrue(tempDir.resolve("checkpoint_snapshot_agent-multi_turn_6.json").exists())
        assertTrue(tempDir.resolve("checkpoint_snapshot_agent-multi_turn_9.json").exists())
    }

    @Test
    fun `should handle agent IDs with special characters`() = runBlocking {
        // Given
        val writer = FileSystemCheckpointWriter(directory = tempDir)
        val checkpoint = createCheckpoint("agent-b2c3d4e5-f6a7-8901", 1)

        // When
        writer.write(checkpoint)

        // Then
        val expectedFile = tempDir.resolve("checkpoint_snapshot_agent-b2c3d4e5-f6a7-8901_turn_1.json")
        assertTrue(expectedFile.exists())
    }
}
