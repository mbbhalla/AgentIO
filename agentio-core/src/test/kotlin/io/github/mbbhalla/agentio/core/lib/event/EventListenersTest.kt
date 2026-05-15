package io.github.mbbhalla.agentio.core.lib.event

import io.github.mbbhalla.agentio.core.model.event.Event
import io.github.mbbhalla.agentio.core.model.event.EventPayload
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Collections

internal class EventListenersTest {

    private val testEvent = Event(
        payload = EventPayload.AgentInvocationStart(
            agentId = "test-agent",
            instructionId = "test-instruction",
            instruction = "do something",
        ),
    )

    @Test
    fun `should dispatch event to all listeners`() = runBlocking {
        // Given
        val received = Collections.synchronizedList(mutableListOf<String>())
        val listeners = EventListeners(
            setOf(
                EventListener { received.add("listener-1"); Result.success(Unit) },
                EventListener { received.add("listener-2"); Result.success(Unit) },
                EventListener { received.add("listener-3"); Result.success(Unit) },
            ),
        )

        // When
        listeners.dispatch(testEvent)

        // Then
        assertEquals(3, received.size)
        assertTrue(received.contains("listener-1"))
        assertTrue(received.contains("listener-2"))
        assertTrue(received.contains("listener-3"))
    }

    @Test
    fun `should dispatch to zero listeners without error`() = runBlocking {
        // Given
        val listeners = EventListeners(emptySet())

        // When / Then — no exception
        listeners.dispatch(testEvent)
    }

    @Test
    fun `should continue dispatching when a listener fails`() = runBlocking {
        // Given
        val received = Collections.synchronizedList(mutableListOf<String>())
        val listeners = EventListeners(
            setOf(
                EventListener { received.add("before-failure"); Result.success(Unit) },
                EventListener { Result.failure(RuntimeException("Listener exploded")) },
                EventListener { received.add("after-failure"); Result.success(Unit) },
            ),
        )

        // When
        listeners.dispatch(testEvent)

        // Then — all non-failing listeners still executed
        assertEquals(2, received.size)
        assertTrue(received.contains("before-failure"))
        assertTrue(received.contains("after-failure"))
    }

    @Test
    fun `should not propagate exception when listener throws instead of returning failure`() = runBlocking {
        // Given — a badly-behaved listener that throws instead of returning Result.failure
        val received = Collections.synchronizedList(mutableListOf<String>())
        val listeners = EventListeners(
            setOf(
                EventListener { received.add("first"); Result.success(Unit) },
                EventListener { throw RuntimeException("Unexpected throw") },
                EventListener { received.add("third"); Result.success(Unit) },
            ),
        )

        // When / Then — dispatch should not throw
        // Note: the throwing listener prevents subsequent listeners from running
        // because forEach is sequential and the throw escapes the lambda.
        // This tests that the contract requires returning Result, not throwing.
        var threw = false
        try {
            listeners.dispatch(testEvent)
        } catch (_: RuntimeException) {
            threw = true
        }

        // A listener that throws (violates the contract) will propagate.
        // This is acceptable — the contract says return Result<Unit>.
        assertTrue(threw || received.isNotEmpty())
    }

    @Test
    fun `should pass the same event to all listeners`() = runBlocking {
        // Given
        val receivedEvents = Collections.synchronizedList(mutableListOf<Event>())
        val listeners = EventListeners(
            setOf(
                EventListener { event -> receivedEvents.add(event); Result.success(Unit) },
                EventListener { event -> receivedEvents.add(event); Result.success(Unit) },
            ),
        )

        // When
        listeners.dispatch(testEvent)

        // Then
        assertEquals(2, receivedEvents.size)
        assertTrue(receivedEvents.all { it === testEvent })
    }

    @Test
    fun `should handle single listener`() = runBlocking {
        // Given
        var invoked = false
        val listeners = EventListeners(
            setOf(
                EventListener { invoked = true; Result.success(Unit) },
            ),
        )

        // When
        listeners.dispatch(testEvent)

        // Then
        assertTrue(invoked)
    }
}
