package io.github.mbbhalla.agentio.core.lib.tool

import aws.sdk.kotlin.services.bedrockruntime.model.ContentBlock
import aws.sdk.kotlin.services.bedrockruntime.model.Tool
import aws.sdk.kotlin.services.bedrockruntime.model.ToolInputSchema
import aws.sdk.kotlin.services.bedrockruntime.model.ToolResultBlock
import aws.sdk.kotlin.services.bedrockruntime.model.ToolResultContentBlock
import aws.sdk.kotlin.services.bedrockruntime.model.ToolResultStatus
import aws.sdk.kotlin.services.bedrockruntime.model.ToolSpecification
import aws.smithy.kotlin.runtime.content.Document
import io.github.mbbhalla.agentio.core.common.JsonSchemaUtil
import io.github.mbbhalla.agentio.core.common.listToolsAndSkipDenyList
import io.github.mbbhalla.agentio.core.common.toDocument
import io.github.mbbhalla.agentio.core.common.toJsonObject
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequestParams
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.vavr.kotlin.Try
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

data class NamedClient(
    val name: String?,
    val client: Client,
    // Tools which are available but not to be used
    val deniedTools: Set<String>,
)

data class McpClients(
    val set: Set<NamedClient>, // emptySet in case no tools for agent to use
) : ToolsProvider {
    companion object {
        /*
            Full tool name =
            client identifier (ci) + joined + tool name

            Hence max tool name supported = 64 - joiner len - ci len
            = 64 - 3 - 7 = 54
         */
        const val TOOL_NAME_JOINER = "___"
        const val CLIENT_IDENTIFIER_MAX_LENGTH = 7
        const val BEDROCK_MAX_TOOL_NAME_LENGTH = 64

        val TOOL_NAME_REPLACE_REGEX = "[^a-zA-Z0-9_-]".toRegex()
    }

    init {
        require(
            set.map { it.name }.distinct().size == set.size,
        ) {
            "Duplicate Client names are not allowed"
        }
    }

    private fun getFullToolName(
        clientIdentifier: String,
        toolName: String,
    ): String {
        /*
            Bedrock Tool name constraints
            https://docs.aws.amazon.com/bedrock/latest/APIReference/API_runtime_ToolSpecification.html
         */
        val newClientIdentifier =
            clientIdentifier
                .replace(TOOL_NAME_REPLACE_REGEX, "-")
                .take(CLIENT_IDENTIFIER_MAX_LENGTH)

        val newToolName =
            toolName
                .replace(TOOL_NAME_REPLACE_REGEX, "-")

        return "${newClientIdentifier}$TOOL_NAME_JOINER$newToolName".take(
            BEDROCK_MAX_TOOL_NAME_LENGTH,
        )
    }

    /*
        Given a toolName, to identify the client needed to call tool
     */
    private suspend fun mapToolNameToClient(): Map<String, Client> =
        this.set
            .flatMap { namedClient ->
                namedClient.client
                    .listToolsAndSkipDenyList(
                        deniedTools = namedClient.deniedTools,
                    ).map { tool ->
                        val clientIdentifier = namedClient.name ?: namedClient.client.serverVersion?.name ?: ""
                        getFullToolName(
                            clientIdentifier = clientIdentifier,
                            toolName = tool.name,
                        ) to namedClient.client
                    }
            }.associate { it }

    /*
        https://docs.aws.amazon.com/bedrock/latest/APIReference/API_runtime_ToolInputSchema.html
     */
    private suspend fun toBedrockConverseToolSpec(): List<Tool.ToolSpec> =
        this.set.flatMap { namedClient ->
            val clientIdentifier = namedClient.name ?: namedClient.client.serverVersion?.name ?: ""
            namedClient.client
                .listToolsAndSkipDenyList(
                    deniedTools = namedClient.deniedTools,
                ).map { tool ->
                    val schema =
                        Document.Map(
                            value =
                                mapOf(
                                    "type" to Document.String("object"),
                                    "required" to
                                        Document.List(
                                            value =
                                                (tool.inputSchema.required ?: emptyList()).map { requiredPropertyName ->
                                                    Document.String(requiredPropertyName)
                                                },
                                        ),
                                    "properties" to (tool.inputSchema.properties ?: JsonObject(emptyMap())).toDocument(),
                                ),
                        )

                    Tool.ToolSpec(
                        value =
                            ToolSpecification {
                                name =
                                    getFullToolName(
                                        clientIdentifier = clientIdentifier,
                                        toolName = tool.name,
                                    )
                                description = tool.description
                                inputSchema =
                                    ToolInputSchema.Json(
                                        value = schema,
                                    )
                            },
                    )
                }
        }

    override suspend fun listTools(): List<Tool.ToolSpec> = this.toBedrockConverseToolSpec()

    override suspend fun callTool(contentBlock: ContentBlock.ToolUse): ContentBlock.ToolResult {
        val toolUseId = contentBlock.value.toolUseId
        val toolInput = contentBlock.value.input

        /*
            This tool name is of the format <MCP Server Name>${McpClients.TOOL_NAME_JOINER}<MCP Server's Tool Name>
         */
        val serverAndToolName = contentBlock.value.name
        val toolName =
            serverAndToolName.split(TOOL_NAME_JOINER).getOrElse(1) {
                serverAndToolName
            }

        val mapToolNameToClient = this.mapToolNameToClient()
        val result =
            mapToolNameToClient[serverAndToolName]?.callTool(
                CallToolRequest(
                    params =
                        CallToolRequestParams(
                            name = toolName,
                            arguments = toolInput?.toJsonObject() ?: JsonObject(content = emptyMap()),
                        ),
                ),
            )

        return ContentBlock.ToolResult(
            value =
                ToolResultBlock {
                    this.toolUseId = toolUseId
                    this.status = if (result?.isError == true) ToolResultStatus.Error else ToolResultStatus.Success
                    this.content = result
                        ?.content
                        ?.filterIsInstance<TextContent>()
                        ?.map { it.text }
                        ?.map { text ->
                            val tryJsonObject =
                                Try {
                                    JsonSchemaUtil.json.parseToJsonElement(text).jsonObject
                                }
                            if (tryJsonObject.isSuccess) {
                                ToolResultContentBlock.Json(
                                    value = tryJsonObject.get().toDocument(),
                                )
                            } else {
                                ToolResultContentBlock.Text(
                                    value = text,
                                )
                            }
                        } ?: emptyList()
                },
        )
    }
}
