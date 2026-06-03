package io.github.mbbhalla.agentio.module.compass.server

import io.github.mbbhalla.agentio.core.lib.server.AbstractMcpServer
import io.github.mbbhalla.agentio.data.env.DatabaseEnvironment
import io.github.mbbhalla.agentio.data.tool.ExecuteSqlTool
import io.github.mbbhalla.agentio.data.tool.GetTablesTool
import io.github.mbbhalla.agentio.data.tool.ListTablesTool
import io.github.mbbhalla.agentio.module.compass.tool.AnalysisResultValidatorTool
import io.github.mbbhalla.agentio.module.compass.tool.SmtLibV2SyntaxCheckerTool
import io.modelcontextprotocol.kotlin.sdk.server.RegisteredTool
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities

class AnalyzerMcpServer(
    private val env: DatabaseEnvironment,
) : AbstractMcpServer() {
    override fun tools(): Set<RegisteredTool> =
        setOf(
            ListTablesTool(env)(),
            GetTablesTool(env)(),
            ExecuteSqlTool(env)(),
            AnalysisResultValidatorTool(env)(),
        )

    override fun capabilities() = ServerCapabilities(tools = ServerCapabilities.Tools(listChanged = false))

    override fun name() = "compass-analyzer"

    override fun version() = "1.0.0"
}

class ConstraintGeneratorMcpServer(
    private val env: DatabaseEnvironment,
) : AbstractMcpServer() {
    override fun tools(): Set<RegisteredTool> =
        setOf(
            ListTablesTool(env)(),
            GetTablesTool(env)(),
            ExecuteSqlTool(env)(),
            SmtLibV2SyntaxCheckerTool(),
        )

    override fun capabilities() = ServerCapabilities(tools = ServerCapabilities.Tools(listChanged = false))

    override fun name() = "compass-constraint-generator"

    override fun version() = "1.0.0"
}
