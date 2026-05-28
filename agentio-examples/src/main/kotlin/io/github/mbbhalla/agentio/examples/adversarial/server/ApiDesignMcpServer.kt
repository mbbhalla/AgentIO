package io.github.mbbhalla.agentio.examples.adversarial.server

import io.github.mbbhalla.agentio.core.lib.server.AbstractMcpServer
import io.modelcontextprotocol.kotlin.sdk.server.RegisteredTool
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities

internal class ApiDesignMcpServer : AbstractMcpServer() {
    override fun tools(): Set<RegisteredTool> =
        setOf(
            ParseRequirementsTool()(),
            ValidateSchemaConsistencyTool()(),
            CheckSecurityPatternsTool()(),
        )

    override fun capabilities() = ServerCapabilities(tools = ServerCapabilities.Tools(listChanged = false))

    override fun name() = "api-design"

    override fun version() = "1.0.0"
}
