package io.github.mbbhalla.agentio.data.model

import org.junit.jupiter.api.assertThrows
import kotlin.test.Test
import kotlin.test.assertEquals

class S3UriTest {
    @Test
    fun `valid S3 URI parses bucket and prefix`() {
        val uri = S3Uri("s3://my-bucket/path/to/data")
        assertEquals("my-bucket", uri.bucket)
        assertEquals("path/to/data", uri.prefix)
    }

    @Test
    fun `valid S3 URI with single file`() {
        val uri = S3Uri("s3://my-bucket/orders.parquet")
        assertEquals("my-bucket", uri.bucket)
        assertEquals("orders.parquet", uri.prefix)
    }

    @Test
    fun `valid S3 URI with deep prefix`() {
        val uri = S3Uri("s3://data-lake-prod/retail/2025/05/26")
        assertEquals("data-lake-prod", uri.bucket)
        assertEquals("retail/2025/05/26", uri.prefix)
    }

    @Test
    fun `trailing slash is trimmed from prefix`() {
        val uri = S3Uri("s3://my-bucket/path/to/data/")
        assertEquals("path/to/data", uri.prefix)
    }

    @Test
    fun `bucket with dots is valid`() {
        val uri = S3Uri("s3://my.data.bucket/prefix")
        assertEquals("my.data.bucket", uri.bucket)
    }

    @Test
    fun `bucket with hyphens is valid`() {
        val uri = S3Uri("s3://my-data-bucket/prefix")
        assertEquals("my-data-bucket", uri.bucket)
    }

    @Test
    fun `rejects missing s3 scheme`() {
        assertThrows<IllegalArgumentException> { S3Uri("https://my-bucket/path") }
    }

    @Test
    fun `rejects empty string`() {
        assertThrows<IllegalArgumentException> { S3Uri("") }
    }

    @Test
    fun `rejects s3 URI without prefix`() {
        assertThrows<IllegalArgumentException> { S3Uri("s3://my-bucket") }
    }

    @Test
    fun `rejects bucket starting with hyphen`() {
        assertThrows<IllegalArgumentException> { S3Uri("s3://-bucket/path") }
    }

    @Test
    fun `rejects bucket with uppercase`() {
        assertThrows<IllegalArgumentException> { S3Uri("s3://MyBucket/path") }
    }
}
