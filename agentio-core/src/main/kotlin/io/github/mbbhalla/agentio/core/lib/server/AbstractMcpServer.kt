package io.github.mbbhalla.agentio.core.lib.server

import io.github.mbbhalla.agentio.core.model.PipedStreamsExchange
import io.modelcontextprotocol.kotlin.sdk.server.RegisteredTool
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities

abstract class AbstractMcpServer {
    suspend fun pipedStreamsExchange(): PipedStreamsExchange {
        val pipedStreamsExchange = PipedStreamsExchange.Provider.buildStreams()
        val server = Server(
            serverInfo = Implementation(
                name = name(),
                version = version(),
            ),
            options = ServerOptions(
                capabilities = capabilities(),
            ),
        )
        server.addTools(
            tools().toList(),
        )
        server.createSession(pipedStreamsExchange.stdioServerTransport())

        return pipedStreamsExchange
    }

    // Tools deployed to this server
    abstract fun tools(): Set<RegisteredTool>

    // Server capabilities
    abstract fun capabilities(): ServerCapabilities

    // Server name
    abstract fun name(): String

    // Server version
    abstract fun version(): String
}
