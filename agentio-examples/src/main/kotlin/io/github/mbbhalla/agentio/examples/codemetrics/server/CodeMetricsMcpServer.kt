package io.github.mbbhalla.agentio.examples.codemetrics.server

import io.github.mbbhalla.agentio.core.lib.server.AbstractMcpServer
import io.modelcontextprotocol.kotlin.sdk.server.RegisteredTool
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities

internal class CodeMetricsMcpServer(
    private val rootPath: String,
) : AbstractMcpServer() {

    override fun tools(): Set<RegisteredTool> = setOf(
        ListSourceFilesTool(rootPath)(),
        FileComplexityTool(rootPath)(),
        DependencyGraphTool(rootPath)(),
    )

    override fun capabilities() = ServerCapabilities(tools = ServerCapabilities.Tools(listChanged = false))

    override fun name() = "code-metrics"

    override fun version() = "1.0.0"
}
