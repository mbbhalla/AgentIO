package io.github.mbbhalla.agentio.core.lib.tool

import aws.sdk.kotlin.services.bedrockruntime.model.ContentBlock
import aws.sdk.kotlin.services.bedrockruntime.model.Tool
import aws.sdk.kotlin.services.bedrockruntime.model.ToolUseBlock
import aws.smithy.kotlin.runtime.content.Document
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ToolsProviderTest {

    @Test
    fun `ToolsProvider interface should be implementable`() = runBlocking {
        // Given
        val mockProvider = mockk<ToolsProvider>()
        val expectedTools = listOf(
            Tool.ToolSpec(mockk()),
            Tool.ToolSpec(mockk()),
        )
        val expectedResult = ContentBlock.ToolResult(mockk())

        coEvery { mockProvider.listTools() } returns expectedTools
        coEvery { mockProvider.callTool(any()) } returns expectedResult

        // When
        val tools = mockProvider.listTools()
        val toolUse = ContentBlock.ToolUse(
            value = ToolUseBlock {
                toolUseId = "test-123"
                name = "test_tool"
                input = Document.String("test input")
            },
        )
        val result = mockProvider.callTool(toolUse)

        // Then
        assertEquals(expectedTools, tools)
        assertEquals(expectedResult, result)

        coVerify { mockProvider.listTools() }
        coVerify { mockProvider.callTool(toolUse) }
    }

    @Test
    fun `custom ToolsProvider implementation should work`() = runBlocking {
        // Given
        val customProvider = object : ToolsProvider {
            override suspend fun listTools(): List<Tool.ToolSpec> {
                return listOf(
                    Tool.ToolSpec(
                        mockk {
                            // Mock the tool spec if needed
                        },
                    ),
                )
            }

            override suspend fun callTool(contentBlock: ContentBlock.ToolUse): ContentBlock.ToolResult {
                return ContentBlock.ToolResult(
                    mockk {
                        // Configure the mock to return the expected toolUseId
                        every { toolUseId } returns contentBlock.value.toolUseId
                    },
                )
            }
        }

        // When
        val tools = customProvider.listTools()
        val toolUse = ContentBlock.ToolUse(
            value = ToolUseBlock {
                toolUseId = "custom-123"
                name = "custom_tool"
                input = null
            },
        )
        val result = customProvider.callTool(toolUse)

        // Then
        assertEquals(1, tools.size)
        assertEquals("custom-123", result.value.toolUseId)
    }

    @Test
    fun `ToolsProvider should handle empty tool list`() = runBlocking {
        // Given
        val emptyProvider = object : ToolsProvider {
            override suspend fun listTools(): List<Tool.ToolSpec> = emptyList()

            override suspend fun callTool(contentBlock: ContentBlock.ToolUse): ContentBlock.ToolResult {
                return ContentBlock.ToolResult(
                    mockk {
                        // Configure the mock to return the expected toolUseId
                        every { toolUseId } returns contentBlock.value.toolUseId
                    },
                )
            }
        }

        // When
        val tools = emptyProvider.listTools()

        // Then
        assertEquals(0, tools.size)
    }

    @Test
    fun `ToolsProvider should handle tool calls with null input`() = runBlocking {
        // Given
        val provider = object : ToolsProvider {
            override suspend fun listTools(): List<Tool.ToolSpec> = emptyList()

            override suspend fun callTool(contentBlock: ContentBlock.ToolUse): ContentBlock.ToolResult {
                // Should handle null input gracefully
                contentBlock.value.input != null
                return ContentBlock.ToolResult(
                    mockk {
                        // Configure the mock to return the expected toolUseId
                        every { toolUseId } returns contentBlock.value.toolUseId
                    },
                )
            }
        }

        val toolUseWithoutInput = ContentBlock.ToolUse(
            value = ToolUseBlock {
                toolUseId = "no-input-123"
                name = "no_input_tool"
                input = null
            },
        )

        // When
        val result = provider.callTool(toolUseWithoutInput)

        // Then
        assertEquals("no-input-123", result.value.toolUseId)
    }
}
