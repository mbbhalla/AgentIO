package io.github.mbbhalla.agentio.core.lib.tool

import aws.sdk.kotlin.services.bedrockruntime.model.ContentBlock
import aws.sdk.kotlin.services.bedrockruntime.model.Tool

/*
    An interface used by AgentIO to integrate with External tools
    Tools Providers provide a listing of tools as well as the capability
    to call a tool with an input
 */
interface ToolsProvider {
    // List tools in the format required by Bedrock Converse API
    suspend fun listTools(): List<Tool.ToolSpec>

    // Call a Tool in the format required by Bedrock Converse API
    suspend fun callTool(contentBlock: ContentBlock.ToolUse): ContentBlock.ToolResult
}
