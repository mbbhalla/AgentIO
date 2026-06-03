package io.github.mbbhalla.agentio.module.solver

import com.microsoft.z3.Context
import com.microsoft.z3.FuncDecl
import com.microsoft.z3.Sort
import com.microsoft.z3.Symbol
import io.github.mbbhalla.agentio.core.common.Description
import kotlinx.serialization.Serializable

@Serializable
data class SMTLIBv2Formula(
    @field:Description("SMTLIBv2 formula text")
    val smtlibv2Formula: String,
) {
    init {
        require(smtlibv2Formula.isNotBlank()) { "smtlibv2Formula must not be blank" }
        require(syntaxOk(smtlibv2Formula)) {
            "smtlibv2Formula is not syntactically valid: '$smtlibv2Formula'"
        }
    }

    companion object {
        fun syntaxOk(formula: String): Boolean {
            if (formula.isBlank()) return false
            return runCatching {
                Context().use { ctx ->
                    val sortNames = emptyArray<Symbol>()
                    val sorts = emptyArray<Sort>()
                    val declNames = emptyArray<Symbol>()
                    val decls = emptyArray<FuncDecl<*>>()
                    ctx.parseSMTLIB2String(formula, sortNames, sorts, declNames, decls)
                }
            }.isSuccess
        }
    }
}
