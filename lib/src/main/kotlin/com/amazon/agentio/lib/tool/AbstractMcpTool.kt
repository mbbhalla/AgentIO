package com.amazon.agentio.lib.tool

import com.amazon.agentio.common.JsonSchemaUtil
import com.amazon.agentio.common.JsonString
import io.modelcontextprotocol.kotlin.sdk.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.TextContent
import io.modelcontextprotocol.kotlin.sdk.Tool
import io.modelcontextprotocol.kotlin.sdk.server.RegisteredTool
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

        return RegisteredTool(
            tool = Tool(
                name = this.name(),
                description = this.description(),
                inputSchema = Tool.Input(
                    properties = inputSchema,
                    required = inputRequired.toList(),
                ),
            ),
        ) { request ->
            Try {
                val input = this.buildInput(request)

                val output = this.invoke(input)
                val outputAsJson = JsonString(
                    value = JsonSchemaUtil.jacksonObjectMapper.writeValueAsString(output),
                )

                val outputSchema = JsonSchemaUtil.generateJsonSchema(this.getOutputKClass())
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
                                    "Tool '${name()}' JSON Schema: ${
                                        JsonSchemaUtil.json.encodeToString(
                                            JsonObject.serializer(),
                                            outputSchema,
                                        )
                                    }",
                                ),
                            )
                            add(
                                TextContent(
                                    "Tool '${name()}' Required Attributes: ${
                                        JsonSchemaUtil.jacksonObjectMapper.writeValueAsString(outputRequired)
                                    }",
                                ),
                            )
                        }
                        add(
                            TextContent(
                                "Tool '${name()}' Response: ${
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
