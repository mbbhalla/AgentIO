package io.github.mbbhalla.agentio.data.model

import java.time.Instant

@JvmInline
value class S3ObjectKey(
    val value: String,
) {
    init {
        require(value.isNotBlank()) { "S3 object key must not be blank" }
        require(!value.startsWith("/")) { "S3 object key must not start with '/': '$value'" }
    }
}

data class S3Uri(
    val value: String,
) {
    val bucket: String
    val key: S3ObjectKey

    init {
        require(value.matches(VALID_PATTERN)) { "Invalid S3 URI: '$value'" }
        val withoutScheme = value.removePrefix("s3://")
        val slashIndex = withoutScheme.indexOf('/')
        bucket = withoutScheme.substring(0, slashIndex)
        key = S3ObjectKey(withoutScheme.substring(slashIndex + 1).trimEnd('/'))
    }

    companion object {
        private val VALID_PATTERN = Regex("s3://[a-z0-9][a-z0-9.\\-]{1,61}[a-z0-9]/.+")
    }
}

data class Version(
    val fileReference: S3ObjectKey,
    val versionId: String?,
)

data class VersionSet(
    val versions: Set<Version>,
)

data class DatabaseEnvironmentSnapshot(
    val timestamp: Instant,
    val versionSet: VersionSet?,
)
