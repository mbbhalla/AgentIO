package com.amazon.agentio.lib.server

import com.amazon.agentio.model.PipedStreamsExchange
import io.modelcontextprotocol.kotlin.sdk.Implementation
import io.modelcontextprotocol.kotlin.sdk.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.server.RegisteredTool
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions

abstract class AbstractMcpServer {
    suspend fun pipedStreamsExchange(): PipedStreamsExchange {
        val pipedStreamsExchange = PipedStreamsExchange.Provider.buildStreams()
        val server = Server(
            Implementation(
                name = name(),
                version = version(),
            ),
            ServerOptions(
                capabilities = capabilities(),
            ),
        )
        server.addTools(
            tools().toList(),
        )
        server.connect(pipedStreamsExchange.stdioServerTransport())

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
