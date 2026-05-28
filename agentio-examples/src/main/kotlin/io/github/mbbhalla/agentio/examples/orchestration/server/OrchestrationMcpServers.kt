package io.github.mbbhalla.agentio.examples.orchestration.server

import io.github.mbbhalla.agentio.core.lib.server.AbstractMcpServer
import io.modelcontextprotocol.kotlin.sdk.server.RegisteredTool
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities

internal class SecurityWorkerMcpServer(
    private val projectPath: String,
) : AbstractMcpServer() {
    override fun tools(): Set<RegisteredTool> =
        setOf(
            ScanDependenciesTool(projectPath)(),
        )

    override fun capabilities() = ServerCapabilities(tools = ServerCapabilities.Tools(listChanged = false))

    override fun name() = "security-worker"

    override fun version() = "1.0.0"
}

internal class QualityWorkerMcpServer(
    private val projectPath: String,
) : AbstractMcpServer() {
    override fun tools(): Set<RegisteredTool> =
        setOf(
            AnalyzeTestCoverageTool(projectPath)(),
            MeasureCodeComplexityTool(projectPath)(),
        )

    override fun capabilities() = ServerCapabilities(tools = ServerCapabilities.Tools(listChanged = false))

    override fun name() = "quality-worker"

    override fun version() = "1.0.0"
}

internal class DocumentationWorkerMcpServer(
    private val projectPath: String,
) : AbstractMcpServer() {
    override fun tools(): Set<RegisteredTool> =
        setOf(
            ScanDocumentationTool(projectPath)(),
        )

    override fun capabilities() = ServerCapabilities(tools = ServerCapabilities.Tools(listChanged = false))

    override fun name() = "documentation-worker"

    override fun version() = "1.0.0"
}
