package io.github.mbbhalla.agentio.examples.text2sql.server

import io.github.mbbhalla.agentio.core.lib.server.AbstractMcpServer
import io.modelcontextprotocol.kotlin.sdk.server.RegisteredTool
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities

internal object Text2SqlMcpServer : AbstractMcpServer() {

    override fun tools(): Set<RegisteredTool> = setOf(
        ListTablesTool()(),
        GetTablesTool()(),
        ExecuteSqlTool()(),
    )

    override fun capabilities() = ServerCapabilities(tools = ServerCapabilities.Tools(listChanged = false))

    override fun name() = "text2sql_retail"

    override fun version() = "1.0.0"
}
