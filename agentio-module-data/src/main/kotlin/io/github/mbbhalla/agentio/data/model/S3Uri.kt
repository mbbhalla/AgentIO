package io.github.mbbhalla.agentio.data.model

data class S3Uri(
    val value: String,
) {
    val bucket: String
    val prefix: String

    init {
        require(value.matches(VALID_PATTERN)) { "Invalid S3 URI: '$value'" }
        val withoutScheme = value.removePrefix("s3://")
        val slashIndex = withoutScheme.indexOf('/')
        bucket = withoutScheme.substring(0, slashIndex)
        prefix = withoutScheme.substring(slashIndex + 1).trimEnd('/')
    }

    companion object {
        private val VALID_PATTERN = Regex("s3://[a-z0-9][a-z0-9.\\-]{1,61}[a-z0-9]/.+")
    }
}
