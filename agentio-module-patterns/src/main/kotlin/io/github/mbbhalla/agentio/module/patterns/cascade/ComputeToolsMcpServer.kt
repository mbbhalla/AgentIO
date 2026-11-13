package io.github.mbbhalla.agentio.module.patterns.cascade

import io.github.mbbhalla.agentio.core.common.Description
import io.github.mbbhalla.agentio.core.lib.server.AbstractMcpServer
import io.github.mbbhalla.agentio.core.lib.tool.AbstractMcpTool
import io.modelcontextprotocol.kotlin.sdk.server.RegisteredTool
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.jsonPrimitive
import java.security.MessageDigest

/** Exact string computations that no model can do reliably in its head. Only the upper cascade tiers get these. */
internal class ComputeToolsMcpServer : AbstractMcpServer() {
    override fun tools(): Set<RegisteredTool> = setOf(Sha256Tool()(), TextLengthTool()())

    override fun capabilities() = ServerCapabilities(tools = ServerCapabilities.Tools(listChanged = false))

    override fun name() = "compute"

    override fun version() = "1.0.0"
}

internal object Digests {
    @OptIn(ExperimentalStdlibApi::class)
    fun sha256Hex(text: String): String = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).toHexString()
}

private fun textArgument(request: CallToolRequest): String =
    requireNotNull(
        request.params.arguments
            ?.get("text")
            ?.jsonPrimitive
            ?.content,
    ) {
        "Missing argument 'text'"
    }

internal class Sha256Tool : AbstractMcpTool<Sha256Tool.Input, Sha256Tool.Output>() {
    @Serializable
    data class Input(
        @field:Description("Text to hash, UTF-8 encoded exactly as given")
        val text: String,
    )

    @Serializable
    data class Output(
        @field:Description("Lower-case hexadecimal SHA-256 digest")
        val sha256Hex: String,
    )

    override fun name() = "sha256_hex"

    override fun description() = "SHA-256 digest of a UTF-8 string as lower-case hex"

    override fun getInputKClass() = Input::class

    override fun getOutputKClass() = Output::class

    override fun getToolConfig() = ToolConfig()

    override fun buildInput(callToolRequest: CallToolRequest) = Input(text = textArgument(callToolRequest))

    override fun invoke(input: Input) = Output(sha256Hex = Digests.sha256Hex(input.text))
}

internal class TextLengthTool : AbstractMcpTool<TextLengthTool.Input, TextLengthTool.Output>() {
    @Serializable
    data class Input(
        @field:Description("Text to measure")
        val text: String,
    )

    @Serializable
    data class Output(
        @field:Description("Number of characters (Unicode code points)")
        val characters: Int,
        @field:Description("Number of whitespace-separated words")
        val words: Int,
    )

    override fun name() = "text_length"

    override fun description() = "Exact character and word counts of a string"

    override fun getInputKClass() = Input::class

    override fun getOutputKClass() = Output::class

    override fun getToolConfig() = ToolConfig()

    override fun buildInput(callToolRequest: CallToolRequest) = Input(text = textArgument(callToolRequest))

    override fun invoke(input: Input) =
        Output(
            characters = input.text.codePointCount(0, input.text.length),
            words = input.text.split(Regex("\\s+")).count { it.isNotBlank() },
        )
}
