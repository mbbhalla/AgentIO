package io.github.mbbhalla.agentio.core.lib.tool

import io.github.mbbhalla.agentio.core.common.JsonSchemaUtil
import io.github.mbbhalla.agentio.core.common.JsonString
import io.modelcontextprotocol.kotlin.sdk.server.RegisteredTool
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.Tool
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import io.vavr.kotlin.Try
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlin.reflect.KClass

abstract class AbstractMcpTool<I : Any, O : Any> {
    data class ToolConfig(
        /*
            Optimization to save on context length
            Emit Schema and Required attributes only for the first time
            Emit Response always
         */
        val emitSchemaAndRequiredAttributesForAllToolCalls: Boolean = false,

        // .. add more configs if/when needed...
    )

    abstract fun name(): String
    abstract fun description(): String
    abstract fun invoke(input: I): O
    abstract fun buildInput(callToolRequest: CallToolRequest): I
    abstract fun getInputKClass(): KClass<I>
    abstract fun getOutputKClass(): KClass<O>
    abstract fun getToolConfig(): ToolConfig

    private var invokeCounter = 0

    @Suppress("LongMethod")
    operator fun invoke(): RegisteredTool {
        val inputRequired = mutableSetOf<String>()
        val inputSchema = JsonSchemaUtil.generateSchemaJsonObject(this.getInputKClass(), inputRequired)
        val tool = this

        return RegisteredTool(
            tool = Tool(
                name = tool.name(),
                inputSchema = ToolSchema(
                    properties = inputSchema,
                    required = inputRequired.toList(),
                ),
                description = tool.description(),
            ),
        ) { request ->
            Try {
                val input = tool.buildInput(request)

                val output = tool.invoke(input)
                val outputAsJson = JsonString(
                    value = JsonSchemaUtil.jacksonObjectMapper.writeValueAsString(output),
                )

                val outputSchema = JsonSchemaUtil.generateJsonSchema(tool.getOutputKClass())
                val outputSchemaJson = JsonString(
                    value = JsonSchemaUtil.json.encodeToString(JsonObject.serializer(), outputSchema),
                )
                val errors = JsonSchemaUtil.validateJsonWithSchema(
                    json = outputAsJson,
                    schema = outputSchemaJson,
                ).map { it.error }
                check(errors.isEmpty()) { errors.joinToString { ", " } }

                val outputRequired = outputSchema["required"]?.jsonArray
                    ?.map { it.jsonPrimitive.content } ?: emptyList()

                CallToolResult(
                    content = mutableListOf<TextContent>().apply {
                        if (invokeCounter++ == 0 || getToolConfig().emitSchemaAndRequiredAttributesForAllToolCalls) {
                            add(
                                TextContent(
                                    "Tool '${tool.name()}' JSON Schema: ${
                                        JsonSchemaUtil.json.encodeToString(
                                            JsonObject.serializer(),
                                            outputSchema,
                                        )
                                    }",
                                ),
                            )
                            add(
                                TextContent(
                                    "Tool '${tool.name()}' Required Attributes: ${
                                        JsonSchemaUtil.jacksonObjectMapper.writeValueAsString(outputRequired)
                                    }",
                                ),
                            )
                        }
                        add(
                            TextContent(
                                "Tool '${tool.name()}' Response: ${
                                    JsonSchemaUtil.jacksonObjectMapper.writeValueAsString(output)
                                }",
                            ),
                        )
                    }.toList(),
                    isError = false,
                )
            }.getOrElseGet { t ->
                CallToolResult(
                    content = listOf(TextContent("Error: ${t.message}")),
                    isError = true,
                )
            }
        }
    }
}
