package io.github.mbbhalla.agentio.data.model

import kotlinx.serialization.Serializable

@Serializable
data class ColumnName(val value: String) {
    init {
        require(value.matches(VALID_PATTERN)) { "Invalid column name: '$value'" }
    }

    companion object {
        private val VALID_PATTERN = Regex("[a-z][a-z0-9_]*")
    }
}
