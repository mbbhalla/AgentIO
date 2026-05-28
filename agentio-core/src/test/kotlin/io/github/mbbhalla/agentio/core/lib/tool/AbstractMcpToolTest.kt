package io.github.mbbhalla.agentio.core.lib.tool

import io.mockk.mockk
import io.modelcontextprotocol.kotlin.sdk.server.ClientConnection
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequestParams
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.reflect.KClass

class AbstractMcpToolTest {
    @Serializable
    data class TestInput(
        val query: String,
        val maxResults: Int = 10,
    )

    @Serializable
    data class TestOutput(
        val results: List<String>,
        val count: Int,
        val success: Boolean,
    )

    private class TestMcpTool : AbstractMcpTool<TestInput, TestOutput>() {
        override fun name(): String = "test_search_tool"

        override fun description(): String = "A test tool for searching"

        override fun invoke(input: TestInput): TestOutput =
            TestOutput(
                results = listOf("result1", "result2", "result3").take(input.maxResults),
                count = minOf(3, input.maxResults),
                success = true,
            )

        override fun buildInput(callToolRequest: CallToolRequest): TestInput {
            val args = callToolRequest.arguments ?: JsonObject(emptyMap())
            return TestInput(
                query = args["query"]?.toString() ?: "",
                maxResults = args["maxResults"]?.toString()?.toIntOrNull() ?: 10,
            )
        }

        override fun getInputKClass(): KClass<TestInput> = TestInput::class

        override fun getOutputKClass(): KClass<TestOutput> = TestOutput::class

        override fun getToolConfig(): ToolConfig =
            ToolConfig(
                emitSchemaAndRequiredAttributesForAllToolCalls = false,
            )
    }

    @Test
    fun `should create registered tool with correct name and description`() {
        // Given
        val tool = TestMcpTool()

        // When
        val registeredTool = tool()

        // Then
        assertEquals("test_search_tool", registeredTool.tool.name)
        assertEquals("A test tool for searching", registeredTool.tool.description)
        assertNotNull(registeredTool.tool.inputSchema)
        assertNotNull(registeredTool.tool.inputSchema.properties)
    }

    @Test
    fun `should handle successful tool invocation`() =
        runBlocking {
            // Given
            val tool = TestMcpTool()
            val registeredTool = tool()

            val request =
                CallToolRequest(
                    params =
                        CallToolRequestParams(
                            name = "test_search_tool",
                            arguments =
                                buildJsonObject {
                                    put("query", "test search")
                                    put("maxResults", 2)
                                },
                        ),
                )

            // When
            val result = registeredTool.handler.invoke(mockk<ClientConnection>(relaxed = true), request)

            // Then
            assertEquals(false, result.isError)
            assertTrue(result.content.isNotEmpty())

            // Should contain schema, required attributes, and response
            val contentTexts = result.content.filterIsInstance<TextContent>().mapNotNull { it.text }
            assertTrue(contentTexts.any { it.contains("JSON Schema") })
            assertTrue(contentTexts.any { it.contains("Required Attributes") })
            assertTrue(contentTexts.any { it.contains("Response") })
            assertTrue(contentTexts.any { it.contains("\"count\":2") })
        }

    @Test
    fun `should handle tool invocation with default parameters`() =
        runBlocking {
            // Given
            val tool = TestMcpTool()
            val registeredTool = tool()

            val request =
                CallToolRequest(
                    params =
                        CallToolRequestParams(
                            name = "test_search_tool",
                            arguments =
                                buildJsonObject {
                                    put("query", "default test")
                                },
                        ),
                )

            // When
            val result = registeredTool.handler.invoke(mockk<ClientConnection>(relaxed = true), request)

            // Then
            assertEquals(false, result.isError)
            val responseContent =
                result.content
                    .filterIsInstance<TextContent>()
                    .find { it.text?.contains("Response") == true }
                    ?.text
            assertNotNull(responseContent)
            assertTrue(responseContent?.contains("\"count\":3") == true) // Default maxResults should give 3 results
        }

    @Test
    fun `should handle tool invocation errors gracefully`() =
        runBlocking {
            // Given
            val errorTool =
                object : AbstractMcpTool<TestInput, TestOutput>() {
                    override fun name(): String = "error_tool"

                    override fun description(): String = "A tool that throws errors"

                    override fun invoke(input: TestInput): TestOutput = throw RuntimeException("Tool execution failed")

                    override fun buildInput(callToolRequest: CallToolRequest): TestInput = TestInput("error", 1)

                    override fun getInputKClass(): KClass<TestInput> = TestInput::class

                    override fun getOutputKClass(): KClass<TestOutput> = TestOutput::class

                    override fun getToolConfig(): ToolConfig = ToolConfig()
                }

            val registeredTool = errorTool()
            val request =
                CallToolRequest(
                    params =
                        CallToolRequestParams(
                            name = "error_tool",
                            arguments = JsonObject(emptyMap()),
                        ),
                )

            // When
            val result = registeredTool.handler.invoke(mockk<ClientConnection>(relaxed = true), request)

            // Then
            assertEquals(true, result.isError)
            assertTrue(result.content.isNotEmpty())
            val firstContent = result.content.first() as TextContent
            assertTrue(firstContent.text?.contains("Error:") == true)
        }

    @Test
    fun `should respect tool config for schema emission`() =
        runBlocking {
            // Given
            val configTool =
                object : AbstractMcpTool<TestInput, TestOutput>() {
                    override fun name(): String = "config_tool"

                    override fun description(): String = "Tool with custom config"

                    override fun invoke(input: TestInput): TestOutput = TestOutput(emptyList(), 0, true)

                    override fun buildInput(callToolRequest: CallToolRequest): TestInput = TestInput("", 0)

                    override fun getInputKClass(): KClass<TestInput> = TestInput::class

                    override fun getOutputKClass(): KClass<TestOutput> = TestOutput::class

                    override fun getToolConfig(): ToolConfig =
                        ToolConfig(
                            emitSchemaAndRequiredAttributesForAllToolCalls = true,
                        )
                }

            val registeredTool = configTool()
            val request =
                CallToolRequest(
                    params =
                        CallToolRequestParams(
                            name = "config_tool",
                            arguments = JsonObject(emptyMap()),
                        ),
                )

            // When - First call
            val result1 = registeredTool.handler.invoke(mockk<ClientConnection>(relaxed = true), request)
            // When - Second call
            val result2 = registeredTool.handler.invoke(mockk<ClientConnection>(relaxed = true), request)

            // Then - Both calls should include schema due to config
            assertEquals(false, result1.isError)
            assertEquals(false, result2.isError)

            val result1Texts = result1.content.filterIsInstance<TextContent>().mapNotNull { it.text }
            val result2Texts = result2.content.filterIsInstance<TextContent>().mapNotNull { it.text }

            assertTrue(result1Texts.any { it.contains("JSON Schema") })
            assertTrue(result2Texts.any { it.contains("JSON Schema") })
        }

    @Test
    fun `ToolConfig should have correct default values`() {
        // Given & When
        val defaultConfig = AbstractMcpTool.ToolConfig()

        // Then
        assertFalse(defaultConfig.emitSchemaAndRequiredAttributesForAllToolCalls)
    }
}
