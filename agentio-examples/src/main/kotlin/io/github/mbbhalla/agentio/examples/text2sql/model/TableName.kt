package io.github.mbbhalla.agentio.examples.text2sql.model

@JvmInline
value class TableName(val value: String) {
    init {
        require(value.matches(VALID_PATTERN)) { "Invalid table name: '$value'" }
    }

    companion object {
        private val VALID_PATTERN = Regex("[a-z][a-z0-9_]*")
    }
}
