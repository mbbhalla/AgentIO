package io.github.mbbhalla.agentio.core.lib.ctx

import io.github.mbbhalla.agentio.core.lib.Instructible
import io.github.mbbhalla.agentio.core.lib.ctx.writer.ContextWriter
import io.github.mbbhalla.agentio.core.lib.ctx.writer.ContextWriters
import io.github.mbbhalla.agentio.core.lib.ctx.writer.NoOpContextWriter
import io.github.mbbhalla.agentio.core.model.Conversation
import io.mockk.mockk
import io.mockk.verify
import kotlinx.serialization.Serializable
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

internal class ContextWriterTest {

    @Serializable
    data class TestInstruction(
        private val id: String,
        private val instruction: String,
    ) : Instructible.WithInstruction {
        override fun instructionId(): String = id
        override fun instruction(): String = instruction
        override fun systemInstruction(): String? = null
    }

    @Test
    fun `ContextWriters should hold set of writers`() {
        val writer1 = mockk<ContextWriter>()
        val writer2 = mockk<ContextWriter>()

        val contextWriters = ContextWriters(setOf(writer1, writer2))

        assertEquals(2, contextWriters.value.size)
        assertTrue(contextWriters.value.contains(writer1))
        assertTrue(contextWriters.value.contains(writer2))
    }

    @Test
    fun `ContextWriters with empty set should work`() {
        val emptyWriters = ContextWriters(emptySet())
        assertTrue(emptyWriters.value.isEmpty())
    }

    @Test
    fun `ContextWriter interface should be callable`() {
        val mockWriter = mockk<ContextWriter>(relaxed = true)
        val testInput = TestInstruction("test-1", "test instruction")
        val conversation = Conversation.initialize(listOf("test message"))

        mockWriter.write(testInput, conversation)

        verify { mockWriter.write(testInput, conversation) }
    }

    @Test
    fun `NoOpContextWriter should not throw`() {
        val testInput = TestInstruction("test-2", "test")
        val conversation = Conversation.initialize(listOf("test"))
        NoOpContextWriter.write(testInput, conversation)
    }

    @Test
    fun `custom ContextWriter implementation should work`() {
        var writtenInput: Instructible.WithInstruction? = null
        var writtenConversation: Conversation? = null

        val customWriter = object : ContextWriter {
            override fun <I : Instructible.WithInstruction> write(input: I, conversation: Conversation) {
                writtenInput = input
                writtenConversation = conversation
            }
        }

        val testInput = TestInstruction("test-3", "analyze data")
        val conversation = Conversation.initialize(listOf("analysis request"))

        customWriter.write(testInput, conversation)

        assertEquals(testInput, writtenInput)
        assertEquals(conversation, writtenConversation)
    }
}
