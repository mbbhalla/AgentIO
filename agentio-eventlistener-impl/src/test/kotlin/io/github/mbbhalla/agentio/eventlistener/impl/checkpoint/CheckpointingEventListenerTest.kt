package io.github.mbbhalla.agentio.eventlistener.impl.checkpoint

import io.github.mbbhalla.agentio.core.model.conversation.Conversation
import io.github.mbbhalla.agentio.core.model.event.Event
import io.github.mbbhalla.agentio.core.model.event.EventPayload
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.Collections

internal class CheckpointingEventListenerTest {

    private lateinit var writtenCheckpoints: MutableList<Checkpoint>
    private lateinit var writer: CheckpointWriter

    @BeforeEach
    fun setup() {
        writtenCheckpoints = Collections.synchronizedList(mutableListOf())
        writer = CheckpointWriter { checkpoint -> writtenCheckpoints.add(checkpoint) }
    }

    private fun conversation(): Conversation = Conversation.initialize(listOf("test message"))

    private fun turnCompletedEvent(agentId: String, turnNumber: Int): Event = Event(
        payload = EventPayload.TurnCompleted(
            agentId = agentId,
            turnNumber = turnNumber,
            conversation = conversation(),
        ),
    )

    // --- EveryNTurns trigger ---

    @Test
    fun `should checkpoint on every Nth turn`() = runBlocking {
        // Given
        val listener = CheckpointingEventListener(
            trigger = CheckpointTrigger.EveryNTurns(n = 3),
            writer = writer,
        )

        // When
        for (turn in 1..9) {
            listener.onEvent(turnCompletedEvent("agent-1", turn))
        }

        // Then — checkpoints at turns 3, 6, 9
        assertEquals(3, writtenCheckpoints.size)
        assertEquals(3, writtenCheckpoints[0].turnNumber)
        assertEquals(6, writtenCheckpoints[1].turnNumber)
        assertEquals(9, writtenCheckpoints[2].turnNumber)
    }

    @Test
    fun `should not checkpoint on turn 0`() = runBlocking {
        // Given
        val listener = CheckpointingEventListener(
            trigger = CheckpointTrigger.EveryNTurns(n = 1),
            writer = writer,
        )

        // When
        listener.onEvent(turnCompletedEvent("agent-1", 0))

        // Then
        assertTrue(writtenCheckpoints.isEmpty())
    }

    @Test
    fun `should checkpoint every turn when n is 1`() = runBlocking {
        // Given
        val listener = CheckpointingEventListener(
            trigger = CheckpointTrigger.EveryNTurns(n = 1),
            writer = writer,
        )

        // When
        for (turn in 1..5) {
            listener.onEvent(turnCompletedEvent("agent-1", turn))
        }

        // Then
        assertEquals(5, writtenCheckpoints.size)
        assertEquals(listOf(1, 2, 3, 4, 5), writtenCheckpoints.map { it.turnNumber })
    }

    @Test
    fun `should not checkpoint between intervals`() = runBlocking {
        // Given
        val listener = CheckpointingEventListener(
            trigger = CheckpointTrigger.EveryNTurns(n = 5),
            writer = writer,
        )

        // When — turns 1 through 4
        for (turn in 1..4) {
            listener.onEvent(turnCompletedEvent("agent-1", turn))
        }

        // Then
        assertTrue(writtenCheckpoints.isEmpty())
    }

    @Test
    fun `should pass correct agentId in checkpoint`() = runBlocking {
        // Given
        val listener = CheckpointingEventListener(
            trigger = CheckpointTrigger.EveryNTurns(n = 1),
            writer = writer,
        )

        // When
        listener.onEvent(turnCompletedEvent("my-special-agent", 1))

        // Then
        assertEquals(1, writtenCheckpoints.size)
        assertEquals("my-special-agent", writtenCheckpoints[0].agentId)
    }

    @Test
    fun `should include conversation in checkpoint`() = runBlocking {
        // Given
        val listener = CheckpointingEventListener(
            trigger = CheckpointTrigger.EveryNTurns(n = 1),
            writer = writer,
        )

        // When
        listener.onEvent(turnCompletedEvent("agent-1", 1))

        // Then
        assertEquals(1, writtenCheckpoints.size)
        assertTrue(writtenCheckpoints[0].conversation.messages.isNotEmpty())
    }

    @Test
    fun `should set checkpointTimestamp`() = runBlocking {
        // Given
        val listener = CheckpointingEventListener(
            trigger = CheckpointTrigger.EveryNTurns(n = 1),
            writer = writer,
        )

        // When
        val before = java.time.Instant.now()
        listener.onEvent(turnCompletedEvent("agent-1", 1))
        val after = java.time.Instant.now()

        // Then
        val timestamp = writtenCheckpoints[0].checkpointTimestamp
        assertTrue(!timestamp.isBefore(before))
        assertTrue(!timestamp.isAfter(after))
    }

    // --- Non-TurnCompleted events are ignored ---

    @Test
    fun `should ignore non-TurnCompleted events`() = runBlocking {
        // Given
        val listener = CheckpointingEventListener(
            trigger = CheckpointTrigger.EveryNTurns(n = 1),
            writer = writer,
        )

        // When
        listener.onEvent(
            Event(
                payload = EventPayload.AgentInvocationStart(
                    agentId = "agent-1",
                    instructionId = "inst-1",
                    instruction = "do something",
                ),
            ),
        )
        listener.onEvent(
            Event(
                payload = EventPayload.BeforeLlmCall(
                    modelId = "some-model",
                    messageCount = 1,
                    turnNumber = 1,
                ),
            ),
        )

        // Then
        assertTrue(writtenCheckpoints.isEmpty())
    }

    // --- Result semantics ---

    @Test
    fun `should return success when no checkpoint needed`() = runBlocking {
        // Given
        val listener = CheckpointingEventListener(
            trigger = CheckpointTrigger.EveryNTurns(n = 5),
            writer = writer,
        )

        // When — turn 1 does not trigger checkpoint at n=5
        val result = listener.onEvent(turnCompletedEvent("agent-1", 1))

        // Then
        assertTrue(result.isSuccess)
    }

    @Test
    fun `should return success when checkpoint is written`() = runBlocking {
        // Given
        val listener = CheckpointingEventListener(
            trigger = CheckpointTrigger.EveryNTurns(n = 1),
            writer = writer,
        )

        // When
        val result = listener.onEvent(turnCompletedEvent("agent-1", 1))

        // Then
        assertTrue(result.isSuccess)
    }

    @Test
    fun `should return failure when writer throws`() = runBlocking {
        // Given
        val failingWriter = CheckpointWriter { throw RuntimeException("Disk full") }
        val listener = CheckpointingEventListener(
            trigger = CheckpointTrigger.EveryNTurns(n = 1),
            writer = failingWriter,
        )

        // When
        val result = listener.onEvent(turnCompletedEvent("agent-1", 1))

        // Then
        assertTrue(result.isFailure)
        assertEquals("Disk full", result.exceptionOrNull()?.message)
    }
}
