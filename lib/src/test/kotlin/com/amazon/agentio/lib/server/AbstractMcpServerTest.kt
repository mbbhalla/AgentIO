package com.amazon.agentio.lib.server

import io.modelcontextprotocol.kotlin.sdk.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.Tool
import io.modelcontextprotocol.kotlin.sdk.server.RegisteredTool
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test

internal class AbstractMcpServerTest {

    private class TestMcpServer : AbstractMcpServer() {
        override fun tools(): Set<RegisteredTool> {
            return setOf(
                RegisteredTool(
                    tool = Tool(
                        name = "test_tool",
                        description = "A test tool",
                        inputSchema = Tool.Input(
                            properties = kotlinx.serialization.json.buildJsonObject {},
                            required = emptyList(),
                        ),
                    ),
                ) { _ ->
                    io.modelcontextprotocol.kotlin.sdk.CallToolResult(
                        content = listOf(
                            io.modelcontextprotocol.kotlin.sdk.TextContent("Test response"),
                        ),
                        isError = false,
                    )
                },
            )
        }

        override fun capabilities(): ServerCapabilities {
            return ServerCapabilities(
                tools = ServerCapabilities.Tools(
                    listChanged = true,
                ),
            )
        }

        override fun name(): String = "TestServer"

        override fun version(): String = "1.0.0"
    }

    private class EmptyMcpServer : AbstractMcpServer() {
        override fun tools(): Set<RegisteredTool> = emptySet()

        override fun capabilities(): ServerCapabilities {
            return ServerCapabilities(
                tools = ServerCapabilities.Tools(
                    listChanged = true,
                ),
            )
        }

        override fun name(): String = "EmptyServer"

        override fun version(): String = "0.1.0"
    }

    @Test
    fun `should create server with tools and capabilities`() = runBlocking {
        // Given
        val server = TestMcpServer()

        // When
        val pipedStreamsExchange = server.pipedStreamsExchange()

        // Then
        assertNotNull(pipedStreamsExchange)
        assertNotNull(pipedStreamsExchange.stdioServerTransport())
        assertNotNull(pipedStreamsExchange.stdioClientTransport())

        // Verify server properties
        assertEquals("TestServer", server.name())
        assertEquals("1.0.0", server.version())
        assertEquals(1, server.tools().size)

        val tool = server.tools().first()
        assertEquals("test_tool", tool.tool.name)
        assertEquals("A test tool", tool.tool.description)

        val capabilities = server.capabilities()
        assertNotNull(capabilities.tools)
        assertEquals(true, capabilities.tools?.listChanged)
    }

    @Test
    fun `should create server with empty tools`() = runBlocking {
        // Given
        val server = EmptyMcpServer()

        // When
        val pipedStreamsExchange = server.pipedStreamsExchange()

        // Then
        assertNotNull(pipedStreamsExchange)

        // Verify server properties
        assertEquals("EmptyServer", server.name())
        assertEquals("0.1.0", server.version())
        assertEquals(0, server.tools().size)

        val capabilities = server.capabilities()
        assertNotNull(capabilities)
    }

    @Test
    fun `should handle multiple tools`() {
        // Given
        val multiToolServer = object : AbstractMcpServer() {
            override fun tools(): Set<RegisteredTool> {
                return setOf(
                    RegisteredTool(
                        tool = Tool(
                            name = "tool1",
                            description = "First tool",
                            inputSchema = Tool.Input(
                                properties = kotlinx.serialization.json.buildJsonObject {},
                                required = emptyList(),
                            ),
                        ),
                    ) { _ ->
                        io.modelcontextprotocol.kotlin.sdk.CallToolResult(
                            content = listOf(
                                io.modelcontextprotocol.kotlin.sdk.TextContent("Tool1 response"),
                            ),
                            isError = false,
                        )
                    },
                    RegisteredTool(
                        tool = Tool(
                            name = "tool2",
                            description = "Second tool",
                            inputSchema = Tool.Input(
                                properties = kotlinx.serialization.json.buildJsonObject {},
                                required = emptyList(),
                            ),
                        ),
                    ) { _ ->
                        io.modelcontextprotocol.kotlin.sdk.CallToolResult(
                            content = listOf(
                                io.modelcontextprotocol.kotlin.sdk.TextContent("Tool2 response"),
                            ),
                            isError = false,
                        )
                    },
                )
            }

            override fun capabilities(): ServerCapabilities {
                return ServerCapabilities(
                    tools = ServerCapabilities.Tools(
                        listChanged = false,
                    ),
                )
            }

            override fun name(): String = "MultiToolServer"
            override fun version(): String = "2.0.0"
        }

        // When & Then
        assertEquals(2, multiToolServer.tools().size)
        assertEquals("MultiToolServer", multiToolServer.name())
        assertEquals("2.0.0", multiToolServer.version())

        val toolNames = multiToolServer.tools().map { it.tool.name }.toSet()
        assertEquals(setOf("tool1", "tool2"), toolNames)

        val capabilities = multiToolServer.capabilities()
        assertEquals(false, capabilities.tools?.listChanged)
    }

    @Test
    fun `should handle different server capabilities`() {
        // Given
        val advancedServer = object : AbstractMcpServer() {
            override fun tools(): Set<RegisteredTool> = emptySet()

            override fun capabilities(): ServerCapabilities {
                return ServerCapabilities(
                    tools = ServerCapabilities.Tools(
                        listChanged = true,
                    ),
                    resources = ServerCapabilities.Resources(
                        subscribe = true,
                        listChanged = false,
                    ),
                )
            }

            override fun name(): String = "AdvancedServer"
            override fun version(): String = "3.0.0"
        }

        // When
        val capabilities = advancedServer.capabilities()

        // Then
        assertNotNull(capabilities.tools)
        assertNotNull(capabilities.resources)
        assertEquals(true, capabilities.tools?.listChanged)
        assertEquals(true, capabilities.resources?.subscribe)
        assertEquals(false, capabilities.resources?.listChanged)
    }
}
