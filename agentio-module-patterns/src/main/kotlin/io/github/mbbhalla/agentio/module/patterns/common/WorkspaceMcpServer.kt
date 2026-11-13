package io.github.mbbhalla.agentio.module.patterns.common

import io.github.mbbhalla.agentio.core.common.Description
import io.github.mbbhalla.agentio.core.lib.server.AbstractMcpServer
import io.github.mbbhalla.agentio.core.lib.tool.AbstractMcpTool
import io.modelcontextprotocol.kotlin.sdk.server.RegisteredTool
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.io.path.relativeTo
import kotlin.streams.asSequence

/**
 * Read-only file-system tools confined to one directory tree. Every path argument is resolved
 * against the root and rejected if it escapes it, so an agent can explore a repository without
 * reaching anything else on the machine. Build output and dot-directories are hidden.
 */
internal class WorkspaceMcpServer(
    private val root: Path,
) : AbstractMcpServer() {
    override fun tools(): Set<RegisteredTool> =
        setOf(
            ListDirectoryTool(root)(),
            ReadFileHeadTool(root)(),
            CountFilesTool(root)(),
        )

    override fun capabilities() = ServerCapabilities(tools = ServerCapabilities.Tools(listChanged = false))

    override fun name() = "workspace"

    override fun version() = "1.0.0"
}

private val HIDDEN_DIRECTORY_NAMES = setOf("build", "bin", ".git", ".gradle", ".idea", ".kotlin")

private fun isVisible(path: Path): Boolean = path.name !in HIDDEN_DIRECTORY_NAMES && !path.name.startsWith(".")

/** Resolves [relativePath] under [root], failing if the result escapes the root. */
private fun resolveInside(
    root: Path,
    relativePath: String,
): Path {
    val normalizedRoot = root.toAbsolutePath().normalize()
    val resolved = normalizedRoot.resolve(relativePath.ifBlank { "." }).normalize()
    require(resolved.startsWith(normalizedRoot)) { "Path '$relativePath' escapes the workspace root" }
    return resolved
}

private fun stringArgument(
    request: CallToolRequest,
    name: String,
): String? =
    request.params.arguments
        ?.get(name)
        ?.jsonPrimitive
        ?.content

private fun intArgument(
    request: CallToolRequest,
    name: String,
): Int? = stringArgument(request, name)?.toIntOrNull()

internal class ListDirectoryTool(
    private val root: Path,
) : AbstractMcpTool<ListDirectoryTool.Input, ListDirectoryTool.Output>() {
    @Serializable
    data class Input(
        @field:Description("Directory path relative to the workspace root; empty string for the root itself")
        val relativePath: String = "",
    )

    @Serializable
    data class Output(
        @field:Description("Sub-directory names, sorted")
        val directories: List<String>,
        @field:Description("File names, sorted")
        val files: List<String>,
    )

    override fun name() = "list_directory"

    override fun description() = "List the sub-directories and files directly under a directory of the workspace"

    override fun getInputKClass() = Input::class

    override fun getOutputKClass() = Output::class

    override fun getToolConfig() = ToolConfig()

    override fun buildInput(callToolRequest: CallToolRequest) = Input(relativePath = stringArgument(callToolRequest, "relativePath") ?: "")

    override fun invoke(input: Input): Output {
        val directory = resolveInside(root, input.relativePath)
        require(directory.isDirectory()) { "'${input.relativePath}' is not a directory" }
        val children = Files.list(directory).use { stream -> stream.asSequence().filter(::isVisible).toList() }
        return Output(
            directories = children.filter { it.isDirectory() }.map { it.name }.sorted(),
            files = children.filter { it.isRegularFile() }.map { it.name }.sorted(),
        )
    }
}

internal class ReadFileHeadTool(
    private val root: Path,
) : AbstractMcpTool<ReadFileHeadTool.Input, ReadFileHeadTool.Output>() {
    companion object {
        /** Keeps tool results small enough to stay cheap in the agent's context. */
        const val MAX_LINES = 200
    }

    @Serializable
    data class Input(
        @field:Description("File path relative to the workspace root")
        val relativePath: String,
        @field:Description("Maximum number of lines to return from the start of the file (1..200)")
        val maxLines: Int = 60,
    )

    @Serializable
    data class Output(
        @field:Description("The first lines of the file")
        val lines: List<String>,
        @field:Description("Total number of lines in the file")
        val totalLines: Int,
    )

    override fun name() = "read_file_head"

    override fun description() = "Read the first lines of a text file in the workspace"

    override fun getInputKClass() = Input::class

    override fun getOutputKClass() = Output::class

    override fun getToolConfig() = ToolConfig()

    override fun buildInput(callToolRequest: CallToolRequest) =
        Input(
            relativePath = stringArgument(callToolRequest, "relativePath") ?: "",
            maxLines = intArgument(callToolRequest, "maxLines") ?: 60,
        )

    override fun invoke(input: Input): Output {
        val file = resolveInside(root, input.relativePath)
        require(file.isRegularFile()) { "'${input.relativePath}' is not a file" }
        val allLines = Files.readAllLines(file)
        return Output(
            lines = allLines.take(input.maxLines.coerceIn(1, MAX_LINES)),
            totalLines = allLines.size,
        )
    }
}

internal class CountFilesTool(
    private val root: Path,
) : AbstractMcpTool<CountFilesTool.Input, CountFilesTool.Output>() {
    @Serializable
    data class Input(
        @field:Description("Directory path relative to the workspace root to search recursively; empty for the root")
        val relativePath: String = "",
        @field:Description("File name suffix to count, for example '.kt' or 'Test.kt'")
        val suffix: String,
    )

    @Serializable
    data class Output(
        @field:Description("Number of matching files")
        val count: Int,
        @field:Description("Up to 20 matching paths relative to the workspace root, sorted")
        val samplePaths: List<String>,
    )

    override fun name() = "count_files"

    override fun description() = "Recursively count files under a directory whose name ends with a suffix"

    override fun getInputKClass() = Input::class

    override fun getOutputKClass() = Output::class

    override fun getToolConfig() = ToolConfig()

    override fun buildInput(callToolRequest: CallToolRequest) =
        Input(
            relativePath = stringArgument(callToolRequest, "relativePath") ?: "",
            suffix = stringArgument(callToolRequest, "suffix") ?: "",
        )

    override fun invoke(input: Input): Output {
        require(input.suffix.isNotBlank()) { "suffix must not be blank" }
        val directory = resolveInside(root, input.relativePath)
        require(directory.isDirectory()) { "'${input.relativePath}' is not a directory" }
        val normalizedRoot = root.toAbsolutePath().normalize()
        val matches =
            Files.walk(directory).use { stream ->
                stream
                    .asSequence()
                    .filter { path -> path.isRegularFile() && path.name.endsWith(input.suffix) }
                    .filter { path -> normalizedRoot.relativize(path).none { segment -> !isVisible(segment) } }
                    .map { path -> path.relativeTo(normalizedRoot).toString() }
                    .sorted()
                    .toList()
            }
        return Output(count = matches.size, samplePaths = matches.take(SAMPLE_LIMIT))
    }

    private companion object {
        const val SAMPLE_LIMIT = 20
    }
}
