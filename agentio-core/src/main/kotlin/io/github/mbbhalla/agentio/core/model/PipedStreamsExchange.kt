package io.github.mbbhalla.agentio.core.model

import io.modelcontextprotocol.kotlin.sdk.client.StdioClientTransport
import io.modelcontextprotocol.kotlin.sdk.server.StdioServerTransport
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered
import java.io.PipedInputStream
import java.io.PipedOutputStream

/*
    Streams for client ⇔ server communications
 */
data class PipedStreamsExchange(
    val outServerToClient: PipedOutputStream,
    val inClientFromServer: PipedInputStream,
    val outClientToServer: PipedOutputStream,
    val inServerFromClient: PipedInputStream,
) {
    object Provider {
        fun buildStreams(): PipedStreamsExchange {
            val outServerToClient = PipedOutputStream()
            val inClientFromServer = PipedInputStream(outServerToClient)

            val outClientToServer = PipedOutputStream()
            val inServerFromClient = PipedInputStream(outClientToServer)

            return PipedStreamsExchange(
                outServerToClient = outServerToClient,
                inClientFromServer = inClientFromServer,
                outClientToServer = outClientToServer,
                inServerFromClient = inServerFromClient,
            )
        }
    }

    fun stdioClientTransport(): StdioClientTransport =
        StdioClientTransport(
            input = this.inClientFromServer.asSource().buffered(),
            output = this.outClientToServer.asSink().buffered(),
        )

    fun stdioServerTransport(): StdioServerTransport =
        StdioServerTransport(
            inputStream = this.inServerFromClient.asSource().buffered(),
            outputStream = this.outServerToClient.asSink().buffered(),
        )
}
