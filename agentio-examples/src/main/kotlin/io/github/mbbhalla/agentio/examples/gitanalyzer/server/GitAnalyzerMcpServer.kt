package io.github.mbbhalla.agentio.examples.gitanalyzer.server

import io.github.mbbhalla.agentio.core.lib.server.AbstractMcpServer
import io.modelcontextprotocol.kotlin.sdk.server.RegisteredTool
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities

internal class GitAnalyzerMcpServer(
    private val repoPath: String,
) : AbstractMcpServer() {
    override fun tools(): Set<RegisteredTool> =
        setOf(
            GitLogTool(repoPath)(),
            GitDiffStatTool(repoPath)(),
            GitFileAuthorsTool(repoPath)(),
        )

    override fun capabilities() = ServerCapabilities(tools = ServerCapabilities.Tools(listChanged = false))

    override fun name() = "git-analyzer"

    override fun version() = "1.0.0"
}
