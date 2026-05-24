package io.github.mbbhalla.agentio.examples.text2sql.model

sealed class ExplainResult {
    data object Success : ExplainResult()
    data class Failure(val error: String) : ExplainResult()
    val isSuccess: Boolean get() = this is Success
}
