package io.github.mbbhalla.agentio.core.lib.ctx

import io.github.mbbhalla.agentio.core.lib.Instructible
import io.github.mbbhalla.agentio.core.lib.ctx.provider.ContextProvider
import io.github.mbbhalla.agentio.core.lib.ctx.provider.ContextProviders
import io.github.mbbhalla.agentio.core.lib.ctx.provider.EmptyContextProvider
import kotlinx.serialization.Serializable
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

internal class ContextProviderTest {

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
    fun `EmptyContextProvider should return blank space`() {
        val testInput = TestInstruction("test-1", "test instruction")
        val context = EmptyContextProvider.context(testInput)
        assertEquals(" ", context)
    }

    @Test
    fun `ContextProviders should hold list of providers`() {
        val provider1 = object : ContextProvider {
            override fun <I : Instructible.WithInstruction> context(input: I): String = "context1"
        }
        val provider2 = object : ContextProvider {
            override fun <I : Instructible.WithInstruction> context(input: I): String = "context2"
        }

        val contextProviders = ContextProviders(listOf(provider1, provider2))

        assertEquals(2, contextProviders.value.size)
        assertTrue(contextProviders.value.contains(provider1))
        assertTrue(contextProviders.value.contains(provider2))
    }

    @Test
    fun `custom ContextProvider should return expected context`() {
        val customProvider = object : ContextProvider {
            override fun <I : Instructible.WithInstruction> context(input: I): String {
                return "Custom context for instruction: ${input.instruction()}"
            }
        }
        val testInput = TestInstruction("test-2", "analyze data")

        val context = customProvider.context(testInput)

        assertEquals("Custom context for instruction: analyze data", context)
    }

    @Test
    fun `ContextProviders with empty list should work`() {
        val emptyProviders = ContextProviders(emptyList())
        assertTrue(emptyProviders.value.isEmpty())
    }
}
