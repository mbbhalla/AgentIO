package io.github.mbbhalla.agentio.module.compass.tool

import io.github.mbbhalla.agentio.core.common.Description
import io.github.mbbhalla.agentio.core.lib.tool.AbstractMcpTool
import io.github.mbbhalla.agentio.module.solver.Logic
import io.github.mbbhalla.agentio.module.solver.SMTLIBv2Formula
import io.github.mbbhalla.agentio.module.solver.Z3SolverFacade
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory

/**
 * Validates an SMTLIB2 formula by:
 * 1. parsing it through Z3 (catches syntax errors), and
 * 2. asking Z3 to solve once with limit=1 (catches semantic issues like unknown functions
 *    that pass syntax checking but fail at solve time).
 */
data object SmtLibV2SyntaxCheckerTool : AbstractMcpTool<SmtLibV2SyntaxCheckerTool.Input, SmtLibV2SyntaxCheckerTool.Output>() {
    @Serializable
    data class Input(
        @field:Description("SMTLIB2 formula text to validate")
        val smtlibv2Formula: String,
    )

    @Serializable
    data class Output(
        @field:Description("True if formula is syntactically AND semantically valid (parses and solves)")
        val valid: Boolean,
        @field:Description("Error message when valid = False; null otherwise")
        val error: String?,
    )

    override fun name() = "smtlibv2_syntax_checker"

    override fun description() = "Validates an SMTLIB2 formula via Z3 (parse + sanity solve). Use this BEFORE returning a formula."

    override fun getInputKClass() = Input::class

    override fun getOutputKClass() = Output::class

    override fun getToolConfig() = ToolConfig(emitSchemaAndRequiredAttributesForAllToolCalls = true)

    override fun buildInput(callToolRequest: CallToolRequest): Input =
        Input(
            smtlibv2Formula =
                callToolRequest.params.arguments
                    ?.get("smtlibv2Formula")
                    ?.jsonPrimitive
                    ?.content
                    ?: throw IllegalArgumentException("'smtlibv2Formula' is required"),
        )

    override fun invoke(input: Input): Output =
        runCatching {
            val formula = SMTLIBv2Formula(smtlibv2Formula = input.smtlibv2Formula)
            Z3SolverFacade.solve(argSmtlibv2Formula = formula, limit = 1, logic = Logic.QF_LIA)
        }.fold(
            onSuccess = { Output(valid = true, error = null) },
            onFailure = {
                LOG.debug("SMTLIB2 validation failed: {}", it.message)
                Output(valid = false, error = it.message ?: it.javaClass.simpleName)
            },
        )

    private val LOG = LoggerFactory.getLogger(SmtLibV2SyntaxCheckerTool::class.java)
}
