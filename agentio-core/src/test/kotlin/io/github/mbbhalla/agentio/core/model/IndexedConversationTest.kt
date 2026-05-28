package io.github.mbbhalla.agentio.core.model

import io.github.mbbhalla.agentio.core.model.conversation.Conversation
import io.github.mbbhalla.agentio.core.model.conversation.IndexedConversation
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

internal class IndexedConversationTest {
    private val conversation = Conversation.initialize(listOf("hello"))

    @Test
    fun `should create with valid turnNumber`() {
        val indexed = IndexedConversation(turnNumber = 0, conversation = conversation)
        assertEquals(0, indexed.turnNumber)
        assertEquals(conversation, indexed.conversation)
    }

    @Test
    fun `should reject negative turnNumber`() {
        assertThrows<IllegalArgumentException> {
            IndexedConversation(turnNumber = -1, conversation = conversation)
        }
    }

    @Test
    fun `next should increment turnNumber by 1`() {
        val first = IndexedConversation(turnNumber = 0, conversation = conversation)
        val other = IndexedConversation(turnNumber = 0, conversation = conversation)
        val second = first.next(other)

        assertEquals(1, second.turnNumber)
        assertEquals(conversation, second.conversation)
    }

    @Test
    fun `next chain should produce sequential turn numbers`() {
        val seed = IndexedConversation(0, conversation)
        val turns =
            generateSequence(seed) {
                it.next(IndexedConversation(turnNumber = 0, conversation = conversation))
            }.take(5).toList()

        assertEquals(listOf(0, 1, 2, 3, 4), turns.map { it.turnNumber })
    }

    @Test
    fun `next should adopt the other conversation`() {
        val conv1 = Conversation.initialize(listOf("first"))
        val conv2 = Conversation.initialize(listOf("second"))

        val first = IndexedConversation(turnNumber = 0, conversation = conv1)
        val other = IndexedConversation(turnNumber = 99, conversation = conv2)
        val second = first.next(other)

        assertEquals(conv2, second.conversation)
        assertEquals(1, second.turnNumber)
    }
}
