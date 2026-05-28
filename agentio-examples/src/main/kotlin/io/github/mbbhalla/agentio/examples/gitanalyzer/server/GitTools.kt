package io.github.mbbhalla.agentio.examples.gitanalyzer.server

import io.github.mbbhalla.agentio.core.common.Description
import io.github.mbbhalla.agentio.core.lib.tool.AbstractMcpTool
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.util.concurrent.TimeUnit

private fun runGitCommand(
    repoPath: String,
    vararg args: String,
): String {
    val process =
        ProcessBuilder("git", *args)
            .directory(File(repoPath))
            .redirectErrorStream(true)
            .start()
    val output = process.inputStream.bufferedReader().readText()
    process.waitFor(30, TimeUnit.SECONDS)
    return output.trim()
}

internal class GitLogTool(
    private val repoPath: String,
) : AbstractMcpTool<GitLogTool.Input, GitLogTool.Output>() {
    @Serializable
    data class Input(
        @field:Description("Number of recent commits to retrieve")
        val count: Int,
    )

    @Serializable
    data class Output(
        @field:Description("List of recent commits with hash, author, date, and message")
        val commits: List<CommitEntry>,
    )

    @Serializable
    data class CommitEntry(
        val hash: String,
        val author: String,
        val date: String,
        val message: String,
    )

    override fun name() = "git_log"

    override fun description() = "Retrieve recent git commit history with author, date, and message"

    override fun getInputKClass() = Input::class

    override fun getOutputKClass() = Output::class

    override fun getToolConfig() = ToolConfig()

    override fun buildInput(callToolRequest: CallToolRequest): Input {
        val count =
            callToolRequest.params.arguments
                ?.get("count")
                ?.jsonPrimitive
                ?.content
                ?.toIntOrNull() ?: 10
        return Input(count = count)
    }

    override fun invoke(input: Input): Output {
        val raw =
            runGitCommand(
                repoPath,
                "log",
                "--format=%H|%an|%aI|%s",
                "-n",
                input.count.toString(),
            )
        val commits =
            raw.lines().filter { it.isNotBlank() }.map { line ->
                val parts = line.split("|", limit = 4)
                CommitEntry(
                    hash = parts.getOrElse(0) { "" }.take(8),
                    author = parts.getOrElse(1) { "unknown" },
                    date = parts.getOrElse(2) { "" },
                    message = parts.getOrElse(3) { "" },
                )
            }
        return Output(commits = commits)
    }
}

internal class GitDiffStatTool(
    private val repoPath: String,
) : AbstractMcpTool<GitDiffStatTool.Input, GitDiffStatTool.Output>() {
    @Serializable
    data class Input(
        @field:Description("Number of recent commits to analyze for diff stats")
        val count: Int,
    )

    @Serializable
    data class Output(
        @field:Description("Files changed with lines added/removed across recent commits")
        val stats: List<FileStat>,
        @field:Description("Total lines added")
        val totalAdded: Int,
        @field:Description("Total lines removed")
        val totalRemoved: Int,
    )

    @Serializable
    data class FileStat(
        val file: String,
        val added: Int,
        val removed: Int,
    )

    override fun name() = "git_diff_stat"

    override fun description() = "Get diff statistics (files changed, lines added/removed) for recent commits"

    override fun getInputKClass() = Input::class

    override fun getOutputKClass() = Output::class

    override fun getToolConfig() = ToolConfig()

    override fun buildInput(callToolRequest: CallToolRequest): Input {
        val count =
            callToolRequest.params.arguments
                ?.get("count")
                ?.jsonPrimitive
                ?.content
                ?.toIntOrNull() ?: 5
        return Input(count = count)
    }

    override fun invoke(input: Input): Output {
        val raw =
            runGitCommand(
                repoPath,
                "diff",
                "--numstat",
                "HEAD~${input.count}..HEAD",
            )
        val stats =
            raw.lines().filter { it.isNotBlank() }.mapNotNull { line ->
                val parts = line.split("\t")
                if (parts.size >= 3) {
                    FileStat(
                        file = parts[2],
                        added = parts[0].toIntOrNull() ?: 0,
                        removed = parts[1].toIntOrNull() ?: 0,
                    )
                } else {
                    null
                }
            }
        return Output(
            stats = stats,
            totalAdded = stats.sumOf { it.added },
            totalRemoved = stats.sumOf { it.removed },
        )
    }
}

internal class GitFileAuthorsTool(
    private val repoPath: String,
) : AbstractMcpTool<GitFileAuthorsTool.Input, GitFileAuthorsTool.Output>() {
    @Serializable
    data class Input(
        @field:Description("File path relative to repository root")
        val filePath: String,
    )

    @Serializable
    data class Output(
        @field:Description("Authors who have contributed to this file with their commit counts")
        val authors: List<AuthorContribution>,
    )

    @Serializable
    data class AuthorContribution(
        val author: String,
        val commits: Int,
    )

    override fun name() = "git_file_authors"

    override fun description() = "List authors who contributed to a specific file with commit counts"

    override fun getInputKClass() = Input::class

    override fun getOutputKClass() = Output::class

    override fun getToolConfig() = ToolConfig()

    override fun buildInput(callToolRequest: CallToolRequest): Input {
        val filePath =
            callToolRequest.params.arguments
                ?.get("filePath")
                ?.jsonPrimitive
                ?.content ?: ""
        return Input(filePath = filePath)
    }

    override fun invoke(input: Input): Output {
        val raw =
            runGitCommand(
                repoPath,
                "log",
                "--format=%an",
                "--follow",
                "--",
                input.filePath,
            )
        val authors =
            raw
                .lines()
                .filter { it.isNotBlank() }
                .groupingBy { it }
                .eachCount()
                .entries
                .sortedByDescending { it.value }
                .map { AuthorContribution(author = it.key, commits = it.value) }
        return Output(authors = authors)
    }
}
