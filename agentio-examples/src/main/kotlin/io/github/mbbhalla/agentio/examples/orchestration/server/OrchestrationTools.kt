package io.github.mbbhalla.agentio.examples.orchestration.server

import io.github.mbbhalla.agentio.core.common.Description
import io.github.mbbhalla.agentio.core.lib.tool.AbstractMcpTool
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

internal class ScanDependenciesTool(
    private val projectPath: String,
) : AbstractMcpTool<ScanDependenciesTool.Input, ScanDependenciesTool.Output>() {

    @Serializable
    data class Input(
        @field:Description("File patterns to scan for dependency declarations, e.g. build.gradle.kts, package.json")
        val filePattern: String,
    )

    @Serializable
    data class Output(
        @field:Description("Dependency files found")
        val dependencyFiles: List<String>,
        @field:Description("Extracted dependency declarations")
        val dependencies: List<DependencyInfo>,
    )

    @Serializable
    data class DependencyInfo(
        val source: String,
        val name: String,
        val version: String,
    )

    override fun name() = "scan_dependencies"
    override fun description() = "Scan project for dependency declaration files and extract dependency information"
    override fun getInputKClass() = Input::class
    override fun getOutputKClass() = Output::class
    override fun getToolConfig() = ToolConfig()

    override fun buildInput(callToolRequest: CallToolRequest): Input {
        val pattern = callToolRequest.params.arguments?.get("filePattern")?.jsonPrimitive?.content ?: "build.gradle.kts"
        return Input(filePattern = pattern)
    }

    override fun invoke(input: Input): Output {
        val root = File(projectPath)
        val depFiles = root.walkTopDown()
            .filter { it.isFile && it.name == input.filePattern }
            .filter { !it.path.contains("/build/") }
            .toList()

        val deps = depFiles.flatMap { file ->
            val lines = file.readLines()
            lines.mapNotNull { line ->
                val trimmed = line.trim()
                when {
                    trimmed.startsWith("implementation(") || trimmed.startsWith("testImplementation(") -> {
                        val content = trimmed.substringAfter("(\"").substringBefore("\")")
                        val parts = content.split(":")
                        if (parts.size >= 2) {
                            DependencyInfo(
                                source = file.relativeTo(root).path,
                                name = "${parts[0]}:${parts[1]}",
                                version = parts.getOrElse(2) { "unspecified" },
                            )
                        } else null
                    }
                    else -> null
                }
            }
        }

        return Output(
            dependencyFiles = depFiles.map { it.relativeTo(root).path },
            dependencies = deps,
        )
    }
}

internal class AnalyzeTestCoverageTool(
    private val projectPath: String,
) : AbstractMcpTool<AnalyzeTestCoverageTool.Input, AnalyzeTestCoverageTool.Output>() {

    @Serializable
    data class Input(
        @field:Description("File extension to analyze, e.g. kt, java, ts")
        val extension: String,
    )

    @Serializable
    data class Output(
        @field:Description("Source files count")
        val sourceFileCount: Int,
        @field:Description("Test files count")
        val testFileCount: Int,
        @field:Description("Test-to-source ratio")
        val testRatio: Double,
        @field:Description("Source files without corresponding test")
        val untestedFiles: List<String>,
    )

    override fun name() = "analyze_test_coverage"
    override fun description() = "Analyze test coverage by comparing source files to test files"
    override fun getInputKClass() = Input::class
    override fun getOutputKClass() = Output::class
    override fun getToolConfig() = ToolConfig()

    override fun buildInput(callToolRequest: CallToolRequest): Input {
        val ext = callToolRequest.params.arguments?.get("extension")?.jsonPrimitive?.content ?: "kt"
        return Input(extension = ext)
    }

    override fun invoke(input: Input): Output {
        val root = File(projectPath)
        val allFiles = root.walkTopDown()
            .filter { it.isFile && it.extension == input.extension }
            .filter { !it.path.contains("/build/") }
            .toList()

        val sourceFiles = allFiles.filter { it.path.contains("/src/main/") }
        val testFiles = allFiles.filter { it.path.contains("/src/test/") }

        val testFileNames = testFiles.map { it.nameWithoutExtension.removeSuffix("Test") }.toSet()
        val untested = sourceFiles.filter { src ->
            src.nameWithoutExtension !in testFileNames
        }.map { it.relativeTo(root).path }

        val ratio = if (sourceFiles.isEmpty()) 0.0 else testFiles.size.toDouble() / sourceFiles.size

        return Output(
            sourceFileCount = sourceFiles.size,
            testFileCount = testFiles.size,
            testRatio = ratio,
            untestedFiles = untested.take(20),
        )
    }
}

internal class ScanDocumentationTool(
    private val projectPath: String,
) : AbstractMcpTool<ScanDocumentationTool.Input, ScanDocumentationTool.Output>() {

    @Serializable
    data class Input(
        @field:Description("File extension for source code to check for documentation, e.g. kt, java")
        val extension: String,
    )

    @Serializable
    data class Output(
        @field:Description("README files found")
        val readmeFiles: List<String>,
        @field:Description("Percentage of public functions with KDoc/Javadoc")
        val documentedFunctionRatio: Double,
        @field:Description("Files with no documentation at all")
        val undocumentedFiles: List<String>,
    )

    override fun name() = "scan_documentation"
    override fun description() = "Scan the project for documentation coverage: READMEs, inline docs, and KDoc/Javadoc"
    override fun getInputKClass() = Input::class
    override fun getOutputKClass() = Output::class
    override fun getToolConfig() = ToolConfig()

    override fun buildInput(callToolRequest: CallToolRequest): Input {
        val ext = callToolRequest.params.arguments?.get("extension")?.jsonPrimitive?.content ?: "kt"
        return Input(extension = ext)
    }

    override fun invoke(input: Input): Output {
        val root = File(projectPath)

        val readmes = root.walkTopDown()
            .filter { it.isFile && it.name.lowercase().startsWith("readme") }
            .filter { !it.path.contains("/build/") }
            .map { it.relativeTo(root).path }
            .toList()

        val sourceFiles = root.walkTopDown()
            .filter { it.isFile && it.extension == input.extension }
            .filter { it.path.contains("/src/main/") && !it.path.contains("/build/") }
            .toList()

        var totalFunctions = 0
        var documentedFunctions = 0
        val undocumented = mutableListOf<String>()

        sourceFiles.forEach { file ->
            val lines = file.readLines()
            var hasDoc = false
            var fileHasAnyDoc = false

            lines.forEachIndexed { idx, line ->
                if (line.trim().startsWith("/**") || line.trim().startsWith("///")) {
                    hasDoc = true
                    fileHasAnyDoc = true
                }
                if (line.trim().startsWith("fun ") || line.trim().startsWith("suspend fun ")) {
                    totalFunctions++
                    if (hasDoc) documentedFunctions++
                    hasDoc = false
                }
            }

            if (!fileHasAnyDoc && lines.any { it.contains("fun ") }) {
                undocumented.add(file.relativeTo(root).path)
            }
        }

        val ratio = if (totalFunctions == 0) 0.0 else documentedFunctions.toDouble() / totalFunctions

        return Output(
            readmeFiles = readmes,
            documentedFunctionRatio = ratio,
            undocumentedFiles = undocumented.take(20),
        )
    }
}

internal class MeasureCodeComplexityTool(
    private val projectPath: String,
) : AbstractMcpTool<MeasureCodeComplexityTool.Input, MeasureCodeComplexityTool.Output>() {

    @Serializable
    data class Input(
        @field:Description("File extension to analyze for complexity, e.g. kt, java")
        val extension: String,
    )

    @Serializable
    data class Output(
        @field:Description("Average cyclomatic complexity proxy (branch points per function)")
        val averageComplexity: Double,
        @field:Description("Files exceeding complexity threshold")
        val highComplexityFiles: List<ComplexityEntry>,
        @field:Description("Total functions analyzed")
        val totalFunctions: Int,
    )

    @Serializable
    data class ComplexityEntry(
        val file: String,
        val complexity: Int,
        val functionCount: Int,
    )

    override fun name() = "measure_code_complexity"
    override fun description() = "Measure cyclomatic complexity proxy across source files using branch-point counting"
    override fun getInputKClass() = Input::class
    override fun getOutputKClass() = Output::class
    override fun getToolConfig() = ToolConfig()

    override fun buildInput(callToolRequest: CallToolRequest): Input {
        val ext = callToolRequest.params.arguments?.get("extension")?.jsonPrimitive?.content ?: "kt"
        return Input(extension = ext)
    }

    override fun invoke(input: Input): Output {
        val root = File(projectPath)
        val branchKeywords = listOf("if ", "else ", "when ", "for ", "while ", "catch ", "&&", "||")

        val entries = root.walkTopDown()
            .filter { it.isFile && it.extension == input.extension }
            .filter { it.path.contains("/src/main/") && !it.path.contains("/build/") }
            .map { file ->
                val lines = file.readLines()
                val branchPoints = lines.count { line ->
                    val trimmed = line.trim()
                    !trimmed.startsWith("//") && branchKeywords.any { kw -> trimmed.contains(kw) }
                }
                val funCount = lines.count { it.trim().let { t -> t.startsWith("fun ") || t.startsWith("suspend fun ") || t.contains(" fun ") } }
                Triple(file.relativeTo(root).path, branchPoints, funCount)
            }
            .toList()

        val totalBranches = entries.sumOf { it.second }
        val totalFunctions = entries.sumOf { it.third }.coerceAtLeast(1)
        val avgComplexity = totalBranches.toDouble() / totalFunctions

        val highComplexity = entries
            .filter { it.third > 0 && it.second.toDouble() / it.third > 5.0 }
            .sortedByDescending { it.second }
            .take(10)
            .map { ComplexityEntry(file = it.first, complexity = it.second, functionCount = it.third) }

        return Output(
            averageComplexity = avgComplexity,
            highComplexityFiles = highComplexity,
            totalFunctions = totalFunctions,
        )
    }
}
