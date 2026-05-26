package io.github.mbbhalla.agentio.data.model

import org.mvel2.MVEL
import org.mvel2.ParserContext

data class MVELExpression(val value: String) {
    init {
        require(
            runCatching { MVEL.compileExpression(value, ParserContext()) }.isSuccess,
        ) { "Invalid MVEL expression: $value" }
    }

    companion object {
        const val DEFAULT = "!this.records.empty"
    }
}
