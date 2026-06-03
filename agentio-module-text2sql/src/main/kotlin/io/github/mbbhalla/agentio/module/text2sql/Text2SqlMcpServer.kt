package io.github.mbbhalla.agentio.module.text2sql

import io.github.mbbhalla.agentio.core.lib.server.AbstractMcpServer
import io.github.mbbhalla.agentio.data.env.DatabaseEnvironment
import io.github.mbbhalla.agentio.data.tool.ExecuteSqlTool
import io.github.mbbhalla.agentio.data.tool.GetTablesTool
import io.github.mbbhalla.agentio.data.tool.ListTablesTool
import io.modelcontextprotocol.kotlin.sdk.server.RegisteredTool
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities

internal class Text2SqlMcpServer(
    private val env: DatabaseEnvironment,
) : AbstractMcpServer() {
    override fun tools(): Set<RegisteredTool> =
        setOf(
            ListTablesTool(env)(),
            GetTablesTool(env)(),
            ExecuteSqlTool(env)(),
        )

    override fun capabilities() = ServerCapabilities(tools = ServerCapabilities.Tools(listChanged = false))

    override fun name() = "text2sql"

    override fun version() = "1.0.0"
}
