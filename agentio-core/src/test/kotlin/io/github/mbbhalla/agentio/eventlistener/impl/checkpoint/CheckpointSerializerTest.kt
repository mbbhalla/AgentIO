package io.github.mbbhalla.agentio.eventlistener.impl.checkpoint

import io.github.mbbhalla.agentio.core.model.conversation.Conversation
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

internal class CheckpointSerializerTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `should serialize checkpoint to valid JSON`() {
        // Given
        val conversation = Conversation.initialize(listOf("Hello, agent"))
        val checkpoint =
            Checkpoint(
                agentId = "agent-123",
                turnNumber = 5,
                checkpointTimestamp = Instant.ofEpochMilli(1700000000000L),
                conversation = conversation,
            )

        // When
        val serialized = CheckpointSerializer.serialize(checkpoint)

        // Then
        val parsed = json.parseToJsonElement(serialized).jsonObject
        assertEquals("agent-123", parsed["agentId"]?.jsonPrimitive?.content)
        assertEquals("5", parsed["turnNumber"]?.jsonPrimitive?.content)
        assertEquals("1700000000000", parsed["checkpointTimestamp"]?.jsonPrimitive?.content)
        assertNotNull(parsed["conversation"])
    }

    @Test
    fun `should serialize conversation messages`() {
        // Given
        val conversation = Conversation.initialize(listOf("First message", "Second message"))
        val checkpoint =
            Checkpoint(
                agentId = "agent-456",
                turnNumber = 1,
                checkpointTimestamp = Instant.now(),
                conversation = conversation,
            )

        // When
        val serialized = CheckpointSerializer.serialize(checkpoint)

        // Then
        assertTrue(serialized.contains("First message"))
        assertTrue(serialized.contains("Second message"))
        assertTrue(serialized.contains("\"role\""))
        assertTrue(serialized.contains("\"user\""))
    }

    @Test
    fun `should serialize token usage`() {
        // Given
        val conversation = Conversation.initialize(listOf("test"))
        val checkpoint =
            Checkpoint(
                agentId = "agent-789",
                turnNumber = 1,
                checkpointTimestamp = Instant.now(),
                conversation = conversation,
            )

        // When
        val serialized = CheckpointSerializer.serialize(checkpoint)

        // Then
        assertTrue(serialized.contains("\"totalInputTokens\""))
        assertTrue(serialized.contains("\"totalOutputTokens\""))
    }

    @Test
    fun `should produce pretty-printed JSON`() {
        // Given
        val conversation = Conversation.initialize(listOf("test"))
        val checkpoint =
            Checkpoint(
                agentId = "agent-pretty",
                turnNumber = 1,
                checkpointTimestamp = Instant.now(),
                conversation = conversation,
            )

        // When
        val serialized = CheckpointSerializer.serialize(checkpoint)

        // Then — pretty print includes newlines and indentation
        assertTrue(serialized.contains("\n"))
        assertTrue(serialized.contains("    "))
    }

    @Test
    fun `should serialize text content blocks`() {
        // Given
        val conversation = Conversation.initialize(listOf("Hello world"))
        val checkpoint =
            Checkpoint(
                agentId = "agent-text",
                turnNumber = 1,
                checkpointTimestamp = Instant.now(),
                conversation = conversation,
            )

        // When
        val serialized = CheckpointSerializer.serialize(checkpoint)

        // Then
        assertTrue(serialized.contains("\"type\": \"text\""))
        assertTrue(serialized.contains("Hello world"))
    }

    @Test
    fun `should include timestamp in messages`() {
        // Given
        val conversation = Conversation.initialize(listOf("test"))
        val checkpoint =
            Checkpoint(
                agentId = "agent-ts",
                turnNumber = 1,
                checkpointTimestamp = Instant.now(),
                conversation = conversation,
            )

        // When
        val serialized = CheckpointSerializer.serialize(checkpoint)

        // Then
        assertTrue(serialized.contains("\"timestamp\""))
    }
}
