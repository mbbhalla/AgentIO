package io.github.mbbhalla.agentio.core.lib.tool

import aws.sdk.kotlin.services.bedrockruntime.model.ContentBlock
import aws.sdk.kotlin.services.bedrockruntime.model.ToolUseBlock
import aws.smithy.kotlin.runtime.content.Document
import io.mockk.coEvery
import io.mockk.mockk
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ListToolsResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.Tool as McpTool
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class McpClientsTest {

    private lateinit var mockClient1: Client
    private lateinit var mockClient2: Client
    private lateinit var namedClient1: NamedClient
    private lateinit var namedClient2: NamedClient

    @BeforeEach
    fun setup() {
        mockClient1 = mockk()
        mockClient2 = mockk()

        namedClient1 = NamedClient(
            name = "client1",
            client = mockClient1,
            deniedTools = emptySet(),
        )

        namedClient2 = NamedClient(
            name = "client2",
            client = mockClient2,
            deniedTools = setOf("denied_tool"),
        )
    }

    @Test
    fun `should require unique client names`() {
        // Given
        val duplicateClient = NamedClient("client1", mockk(), emptySet())

        // When & Then
        assertThrows(IllegalArgumentException::class.java) {
            McpClients(setOf(namedClient1, duplicateClient))
        }
    }

    @Test
    fun `should list tools from all clients`() = runBlocking {
        // Given
        val tool1 = McpTool(
            name = "search",
            description = "Search tool",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    put(
                        "query",
                        buildJsonObject {
                            put("type", "string")
                        },
                    )
                },
                required = listOf("query"),
            ),
        )

        val tool2 = McpTool(
            name = "analyze",
            description = "Analysis tool",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    put(
                        "data",
                        buildJsonObject {
                            put("type", "string")
                        },
                    )
                },
                required = listOf("data"),
            ),
        )

        coEvery { mockClient1.listTools() } returns ListToolsResult(
            tools = listOf(tool1),
            nextCursor = null,
        )
        coEvery { mockClient2.listTools() } returns ListToolsResult(
            tools = listOf(tool2),
            nextCursor = null,
        )

        val mcpClients = McpClients(setOf(namedClient1, namedClient2))

        // When
        val tools = mcpClients.listTools()

        // Then
        assertEquals(2, tools.size)

        val toolNames = tools.map { it.value.name }
        assertTrue(toolNames.contains("client1___search"))
        assertTrue(toolNames.contains("client2___analyze"))
    }

    @Test
    fun `should handle tool name sanitization`() = runBlocking {
        // Given
        val toolWithSpecialChars = McpTool(
            name = "search-with@special#chars",
            description = "Tool with special characters",
            inputSchema = ToolSchema(
                properties = buildJsonObject {},
                required = emptyList(),
            ),
        )

        coEvery { mockClient1.listTools() } returns ListToolsResult(
            tools = listOf(toolWithSpecialChars),
            nextCursor = null,
        )

        val mcpClients = McpClients(setOf(namedClient1))

        // When
        val tools = mcpClients.listTools()

        // Then
        assertEquals(1, tools.size)
        assertEquals("client1___search-with-special-chars", tools.first().value.name)
    }

    @Test
    fun `should respect denied tools`() = runBlocking {
        // Given
        val allowedTool = McpTool(
            name = "allowed_tool",
            description = "Allowed tool",
            inputSchema = ToolSchema(properties = buildJsonObject {}, required = emptyList()),
        )

        val deniedTool = McpTool(
            name = "denied_tool",
            description = "Denied tool",
            inputSchema = ToolSchema(properties = buildJsonObject {}, required = emptyList()),
        )

        coEvery { mockClient2.listTools() } returns ListToolsResult(
            tools = listOf(allowedTool, deniedTool),
            nextCursor = null,
        )

        val mcpClients = McpClients(setOf(namedClient2))

        // When
        val tools = mcpClients.listTools()

        // Then
        assertEquals(1, tools.size)
        assertEquals("client2___allowed_tool", tools.first().value.name)
    }

    @Test
    fun `should call tool on correct client`() = runBlocking {
        // Given
        val tool = McpTool(
            name = "test_tool",
            description = "Test tool",
            inputSchema = ToolSchema(properties = buildJsonObject {}, required = emptyList()),
        )

        coEvery { mockClient1.listTools() } returns ListToolsResult(
            tools = listOf(tool),
            nextCursor = null,
        )

        coEvery { mockClient1.callTool(any()) } returns CallToolResult(
            content = listOf(TextContent("Tool executed successfully")),
            isError = false,
        )

        val mcpClients = McpClients(setOf(namedClient1))

        val toolUse = ContentBlock.ToolUse(
            value = ToolUseBlock {
                toolUseId = "test-123"
                name = "client1___test_tool"
                input = Document.Map(
                    mapOf(
                        "param" to Document.String("value"),
                    ),
                )
            },
        )

        // When
        val result = mcpClients.callTool(toolUse)

        // Then
        assertEquals("test-123", result.value.toolUseId)
        assertFalse(result.value.status.toString().contains("Error"))
        assertTrue(result.value.content.isNotEmpty())
    }

    @Test
    fun `should handle tool execution errors`() = runBlocking {
        // Given
        val tool = McpTool(
            name = "error_tool",
            description = "Tool that errors",
            inputSchema = ToolSchema(properties = buildJsonObject {}, required = emptyList()),
        )

        coEvery { mockClient1.listTools() } returns ListToolsResult(
            tools = listOf(tool),
            nextCursor = null,
        )

        coEvery { mockClient1.callTool(any()) } returns CallToolResult(
            content = listOf(TextContent("Tool execution failed")),
            isError = true,
        )

        val mcpClients = McpClients(setOf(namedClient1))

        val toolUse = ContentBlock.ToolUse(
            value = ToolUseBlock {
                toolUseId = "error-123"
                name = "client1___error_tool"
                input = null
            },
        )

        // When
        val result = mcpClients.callTool(toolUse)

        // Then
        assertEquals("error-123", result.value.toolUseId)
        assertTrue(result.value.status.toString().contains("Error"))
    }

    @Test
    fun `should use server version as client identifier when name is null`() = runBlocking {
        // Given
        val serverVersion = Implementation(name = "TestServer", version = "1.0")
        coEvery { mockClient1.serverVersion } returns serverVersion

        val namedClientWithoutName = NamedClient(
            name = null,
            client = mockClient1,
            deniedTools = emptySet(),
        )

        val tool = McpTool(
            name = "test",
            description = "Test",
            inputSchema = ToolSchema(properties = buildJsonObject {}, required = emptyList()),
        )

        coEvery { mockClient1.listTools() } returns ListToolsResult(
            tools = listOf(tool),
            nextCursor = null,
        )

        val mcpClients = McpClients(setOf(namedClientWithoutName))

        // When
        val tools = mcpClients.listTools()

        // Then
        assertEquals(1, tools.size)
        assertTrue(tools.first().value.name.startsWith("TestSer___")) // Truncated to 7 chars
    }

    @Test
    fun `should handle long tool names by truncating`() = runBlocking {
        // Given
        val veryLongToolName = "a".repeat(100) // Much longer than 64 char limit
        val tool = McpTool(
            name = veryLongToolName,
            description = "Long name tool",
            inputSchema = ToolSchema(properties = buildJsonObject {}, required = emptyList()),
        )

        coEvery { mockClient1.listTools() } returns ListToolsResult(
            tools = listOf(tool),
            nextCursor = null,
        )

        val mcpClients = McpClients(setOf(namedClient1))

        // When
        val tools = mcpClients.listTools()

        // Then
        assertEquals(1, tools.size)
        assertTrue(tools.first().value.name.length <= McpClients.BEDROCK_MAX_TOOL_NAME_LENGTH)
    }

    @Test
    fun `should handle tool with no required fields`() = runBlocking {
        // Given
        val tool = McpTool(
            name = "optional_tool",
            description = "Tool with no required fields",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    put(
                        "optional_param",
                        buildJsonObject {
                            put("type", "string")
                        },
                    )
                },
                required = null, // No required fields
            ),
        )

        coEvery { mockClient1.listTools() } returns ListToolsResult(
            tools = listOf(tool),
            nextCursor = null,
        )

        val mcpClients = McpClients(setOf(namedClient1))

        // When
        val tools = mcpClients.listTools()

        // Then
        assertEquals(1, tools.size)
        assertEquals("client1___optional_tool", tools.first().value.name)
    }

    @Test
    fun `should handle tool call with null input`() = runBlocking {
        // Given
        val tool = McpTool(
            name = "no_input_tool",
            description = "Tool with no input",
            inputSchema = ToolSchema(properties = buildJsonObject {}, required = emptyList()),
        )

        coEvery { mockClient1.listTools() } returns ListToolsResult(
            tools = listOf(tool),
            nextCursor = null,
        )

        coEvery { mockClient1.callTool(any()) } returns CallToolResult(
            content = listOf(TextContent("Success")),
            isError = false,
        )

        val mcpClients = McpClients(setOf(namedClient1))

        val toolUse = ContentBlock.ToolUse(
            value = ToolUseBlock {
                toolUseId = "null-input-123"
                name = "client1___no_input_tool"
                input = null // Null input
            },
        )

        // When
        val result = mcpClients.callTool(toolUse)

        // Then
        assertEquals("null-input-123", result.value.toolUseId)
        assertFalse(result.value.status.toString().contains("Error"))
    }

    @Test
    fun `should handle tool name without joiner`() = runBlocking {
        // Given
        val tool = McpTool(
            name = "simple",
            description = "Simple tool",
            inputSchema = ToolSchema(properties = buildJsonObject {}, required = emptyList()),
        )

        coEvery { mockClient1.listTools() } returns ListToolsResult(
            tools = listOf(tool),
            nextCursor = null,
        )

        coEvery { mockClient1.callTool(any()) } returns CallToolResult(
            content = listOf(TextContent("Result")),
            isError = false,
        )

        val mcpClients = McpClients(setOf(namedClient1))

        // Tool name without joiner - should use the whole name as fallback
        val toolUse = ContentBlock.ToolUse(
            value = ToolUseBlock {
                toolUseId = "no-joiner-123"
                name = "simpleToolWithoutJoiner" // No joiner in name
                input = null
            },
        )

        // When
        val result = mcpClients.callTool(toolUse)

        // Then
        assertEquals("no-joiner-123", result.value.toolUseId)
    }

    @Test
    fun `should handle client with null name and null server version`() = runBlocking {
        // Given
        coEvery { mockClient1.serverVersion } returns null

        val namedClientWithNulls = NamedClient(
            name = null,
            client = mockClient1,
            deniedTools = emptySet(),
        )

        val tool = McpTool(
            name = "test",
            description = "Test",
            inputSchema = ToolSchema(properties = buildJsonObject {}, required = emptyList()),
        )

        coEvery { mockClient1.listTools() } returns ListToolsResult(
            tools = listOf(tool),
            nextCursor = null,
        )

        val mcpClients = McpClients(setOf(namedClientWithNulls))

        // When
        val tools = mcpClients.listTools()

        // Then
        assertEquals(1, tools.size)
        // Should use empty string as fallback
        assertTrue(tools.first().value.name.startsWith("___"))
    }

    @Test
    fun `should handle tool result with JSON content`() = runBlocking {
        // Given
        val tool = McpTool(
            name = "json_tool",
            description = "Tool returning JSON",
            inputSchema = ToolSchema(properties = buildJsonObject {}, required = emptyList()),
        )

        coEvery { mockClient1.listTools() } returns ListToolsResult(
            tools = listOf(tool),
            nextCursor = null,
        )

        coEvery { mockClient1.callTool(any()) } returns CallToolResult(
            content = listOf(TextContent("""{"status": "success", "data": "result"}""")),
            isError = false,
        )

        val mcpClients = McpClients(setOf(namedClient1))

        val toolUse = ContentBlock.ToolUse(
            value = ToolUseBlock {
                toolUseId = "json-123"
                name = "client1___json_tool"
                input = null
            },
        )

        // When
        val result = mcpClients.callTool(toolUse)

        // Then
        assertEquals("json-123", result.value.toolUseId)
        assertTrue(result.value.content.isNotEmpty())
    }

    @Test
    fun `should handle tool result with plain text content`() = runBlocking {
        // Given
        val tool = McpTool(
            name = "text_tool",
            description = "Tool returning plain text",
            inputSchema = ToolSchema(properties = buildJsonObject {}, required = emptyList()),
        )

        coEvery { mockClient1.listTools() } returns ListToolsResult(
            tools = listOf(tool),
            nextCursor = null,
        )

        coEvery { mockClient1.callTool(any()) } returns CallToolResult(
            content = listOf(TextContent("Plain text result")),
            isError = false,
        )

        val mcpClients = McpClients(setOf(namedClient1))

        val toolUse = ContentBlock.ToolUse(
            value = ToolUseBlock {
                toolUseId = "text-123"
                name = "client1___text_tool"
                input = null
            },
        )

        // When
        val result = mcpClients.callTool(toolUse)

        // Then
        assertEquals("text-123", result.value.toolUseId)
        assertTrue(result.value.content.isNotEmpty())
    }
}
