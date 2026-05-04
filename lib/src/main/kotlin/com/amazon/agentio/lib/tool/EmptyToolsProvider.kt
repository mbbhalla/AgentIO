package com.amazon.agentio.lib.tool

import aws.sdk.kotlin.services.bedrockruntime.model.ContentBlock
import aws.sdk.kotlin.services.bedrockruntime.model.Tool

/**
 * A no-op [ToolsProvider] that provides no tools.
 * Used by internal agents (e.g., compaction) that perform pure LLM reasoning
 * without tool access.
 */
object EmptyToolsProvider : ToolsProvider {
    override suspend fun listTools(): List<Tool.ToolSpec> = emptyList()

    override suspend fun callTool(
        contentBlock: ContentBlock.ToolUse,
    ): ContentBlock.ToolResult {
        throw UnsupportedOperationException(
            "EmptyToolsProvider does not support tool calls",
        )
    }
}
