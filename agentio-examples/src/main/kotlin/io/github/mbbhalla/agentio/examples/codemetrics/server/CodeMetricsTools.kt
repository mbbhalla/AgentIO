package io.github.mbbhalla.agentio.examples.codemetrics.server

import io.github.mbbhalla.agentio.core.common.Description
import io.github.mbbhalla.agentio.core.lib.tool.AbstractMcpTool
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

internal class ListSourceFilesTool(
    private val rootPath: String,
) : AbstractMcpTool<ListSourceFilesTool.Input, ListSourceFilesTool.Output>() {
    @Serializable
    data class Input(
        @field:Description("File extension to filter by, e.g. kt, java, ts")
        val extension: String,
    )

    @Serializable
    data class Output(
        @field:Description("Source files found matching the extension")
        val files: List<FileInfo>,
        @field:Description("Total number of files found")
        val totalCount: Int,
    )

    @Serializable
    data class FileInfo(
        val path: String,
        val lines: Int,
        val sizeBytes: Long,
    )

    override fun name() = "list_source_files"

    override fun description() = "List source files by extension with line counts and file sizes"

    override fun getInputKClass() = Input::class

    override fun getOutputKClass() = Output::class

    override fun getToolConfig() = ToolConfig()

    override fun buildInput(callToolRequest: CallToolRequest): Input {
        val extension =
            callToolRequest.params.arguments
                ?.get("extension")
                ?.jsonPrimitive
                ?.content ?: "kt"
        return Input(extension = extension)
    }

    override fun invoke(input: Input): Output {
        val root = File(rootPath)
        val files =
            root
                .walkTopDown()
                .filter { it.isFile && it.extension == input.extension }
                .filter { !it.path.contains("/build/") && !it.path.contains("/bin/") }
                .map { file ->
                    FileInfo(
                        path = file.relativeTo(root).path,
                        lines = file.readLines().size,
                        sizeBytes = file.length(),
                    )
                }.sortedByDescending { it.lines }
                .toList()
        return Output(files = files, totalCount = files.size)
    }
}

internal class FileComplexityTool(
    private val rootPath: String,
) : AbstractMcpTool<FileComplexityTool.Input, FileComplexityTool.Output>() {
    @Serializable
    data class Input(
        @field:Description("File path relative to the root directory")
        val filePath: String,
    )

    @Serializable
    data class Output(
        @field:Description("Complexity metrics for the file")
        val metrics: FileMetrics,
    )

    @Serializable
    data class FileMetrics(
        val totalLines: Int,
        val codeLines: Int,
        val blankLines: Int,
        val commentLines: Int,
        val functionCount: Int,
        val classCount: Int,
        val maxIndentDepth: Int,
    )

    override fun name() = "file_complexity"

    override fun description() =
        "Analyze complexity metrics for a single source file including line counts, function count, class count, and nesting depth"

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
        val file = File(rootPath, input.filePath)
        if (!file.exists()) {
            return Output(metrics = FileMetrics(0, 0, 0, 0, 0, 0, 0))
        }
        val lines = file.readLines()
        val blankLines = lines.count { it.isBlank() }
        val commentLines =
            lines.count {
                it.trimStart().startsWith("//") ||
                    it.trimStart().startsWith("*") ||
                    it.trimStart().startsWith("/*")
            }
        val codeLines = lines.size - blankLines - commentLines
        val functionCount = lines.count { it.contains("fun ") }
        val classCount = lines.count { it.contains("class ") && !it.trimStart().startsWith("//") }
        val maxIndentDepth =
            lines.maxOfOrNull { line ->
                line.takeWhile { it == ' ' }.length / 4
            } ?: 0

        return Output(
            metrics =
                FileMetrics(
                    totalLines = lines.size,
                    codeLines = codeLines,
                    blankLines = blankLines,
                    commentLines = commentLines,
                    functionCount = functionCount,
                    classCount = classCount,
                    maxIndentDepth = maxIndentDepth,
                ),
        )
    }
}

internal class DependencyGraphTool(
    private val rootPath: String,
) : AbstractMcpTool<DependencyGraphTool.Input, DependencyGraphTool.Output>() {
    @Serializable
    data class Input(
        @field:Description("File extension to scan for import analysis, e.g. kt, java")
        val extension: String,
    )

    @Serializable
    data class Output(
        @field:Description("Packages and their import dependencies")
        val packages: List<PackageDeps>,
        @field:Description("Total unique packages found")
        val totalPackages: Int,
    )

    @Serializable
    data class PackageDeps(
        val packageName: String,
        val fileCount: Int,
        val importedPackages: List<String>,
    )

    override fun name() = "dependency_graph"

    override fun description() = "Analyze import statements across source files to build a package-level dependency graph"

    override fun getInputKClass() = Input::class

    override fun getOutputKClass() = Output::class

    override fun getToolConfig() = ToolConfig()

    override fun buildInput(callToolRequest: CallToolRequest): Input {
        val extension =
            callToolRequest.params.arguments
                ?.get("extension")
                ?.jsonPrimitive
                ?.content ?: "kt"
        return Input(extension = extension)
    }

    override fun invoke(input: Input): Output {
        val root = File(rootPath)
        val packageRegex = "^package\\s+(.+)$".toRegex()
        val importRegex = "^import\\s+(.+)$".toRegex()

        val filesByPackage = mutableMapOf<String, MutableList<Set<String>>>()

        root
            .walkTopDown()
            .filter { it.isFile && it.extension == input.extension }
            .filter { !it.path.contains("/build/") && !it.path.contains("/bin/") }
            .forEach { file ->
                val lines = file.readLines()
                val pkg =
                    lines.firstNotNullOfOrNull { line ->
                        packageRegex.find(line.trim())?.groupValues?.get(1)
                    } ?: "default"
                val imports =
                    lines
                        .mapNotNull { line ->
                            importRegex.find(line.trim())?.groupValues?.get(1)
                        }.map { it.substringBeforeLast('.') }
                        .toSet()

                filesByPackage.getOrPut(pkg) { mutableListOf() }.add(imports)
            }

        val packages =
            filesByPackage
                .map { (pkg, importSets) ->
                    PackageDeps(
                        packageName = pkg,
                        fileCount = importSets.size,
                        importedPackages =
                            importSets
                                .flatten()
                                .distinct()
                                .filter { it != pkg }
                                .sorted(),
                    )
                }.sortedByDescending { it.fileCount }

        return Output(packages = packages, totalPackages = packages.size)
    }
}
