package io.github.mbbhalla.agentio.data.env

import aws.sdk.kotlin.services.s3.S3Client
import aws.sdk.kotlin.services.s3.model.DeleteMarkerEntry
import aws.sdk.kotlin.services.s3.model.GetObjectResponse
import aws.sdk.kotlin.services.s3.model.ListObjectVersionsRequest
import aws.sdk.kotlin.services.s3.model.ListObjectVersionsResponse
import aws.sdk.kotlin.services.s3.model.ObjectVersion
import aws.smithy.kotlin.runtime.content.ByteStream
import io.github.mbbhalla.agentio.data.model.ColumnInfo
import io.github.mbbhalla.agentio.data.model.ColumnName
import io.github.mbbhalla.agentio.data.model.ColumnType
import io.github.mbbhalla.agentio.data.model.DataValue
import io.github.mbbhalla.agentio.data.model.DatabaseEnvironmentSnapshot
import io.github.mbbhalla.agentio.data.model.ExplainResult
import io.github.mbbhalla.agentio.data.model.S3ObjectKey
import io.github.mbbhalla.agentio.data.model.S3Uri
import io.github.mbbhalla.agentio.data.model.TableInfo
import io.github.mbbhalla.agentio.data.model.TableName
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.sql.DriverManager
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import aws.smithy.kotlin.runtime.time.Instant as AwsInstant

private data class VersionEntry(
    val key: String,
    val versionId: String,
    val lastModified: Instant,
)

private data class DeleteMarkerInfo(
    val key: String,
    val lastModified: Instant,
)

class DuckDbDatabaseEnvironmentTest {
    @Nested
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    inner class FromStatements {
        private lateinit var env: DuckDbDatabaseEnvironment

        @BeforeAll
        fun setup() {
            env =
                DuckDbDatabaseEnvironment.fromStatements(
                    ddl =
                        listOf(
                            """
                            CREATE TABLE product (
                                product_id VARCHAR NOT NULL PRIMARY KEY,
                                product_name VARCHAR NOT NULL,
                                unit_price DOUBLE NOT NULL
                            )
                            """.trimIndent(),
                            """
                            CREATE TABLE inventory (
                                product_id VARCHAR NOT NULL REFERENCES product(product_id),
                                quantity INTEGER NOT NULL,
                                PRIMARY KEY (product_id)
                            )
                            """.trimIndent(),
                        ),
                    dml =
                        listOf(
                            "INSERT INTO product VALUES ('SKU-001', 'Widget', 9.99)",
                            "INSERT INTO product VALUES ('SKU-002', 'Gadget', 19.99)",
                            "INSERT INTO inventory VALUES ('SKU-001', 100)",
                            "INSERT INTO inventory VALUES ('SKU-002', 50)",
                        ),
                    tableMetadata =
                        setOf(
                            TableInfo(
                                name = TableName("product"),
                                description = "Products",
                                columns =
                                    listOf(
                                        ColumnInfo(
                                            ColumnName("product_id"),
                                            ColumnType.VARCHAR,
                                            nullable = false,
                                            primaryKey = true,
                                            foreignKey = null,
                                            description = "Product ID",
                                        ),
                                        ColumnInfo(
                                            ColumnName("product_name"),
                                            ColumnType.VARCHAR,
                                            nullable = false,
                                            primaryKey = false,
                                            foreignKey = null,
                                            description = "Product name",
                                        ),
                                        ColumnInfo(
                                            ColumnName("unit_price"),
                                            ColumnType.DOUBLE,
                                            nullable = false,
                                            primaryKey = false,
                                            foreignKey = null,
                                            description = "Price",
                                        ),
                                    ),
                            ),
                            TableInfo(
                                name = TableName("inventory"),
                                description = "Inventory levels",
                                columns =
                                    listOf(
                                        ColumnInfo(
                                            ColumnName("product_id"),
                                            ColumnType.VARCHAR,
                                            nullable = false,
                                            primaryKey = true,
                                            foreignKey = null,
                                            description = "Product ID",
                                        ),
                                        ColumnInfo(
                                            ColumnName("quantity"),
                                            ColumnType.INTEGER,
                                            nullable = false,
                                            primaryKey = false,
                                            foreignKey = null,
                                            description = "Qty on hand",
                                        ),
                                    ),
                            ),
                        ),
                )
        }

        @Test
        fun `listTables returns all tables`() {
            val tables = env.listTables()
            assertEquals(setOf(TableName("product"), TableName("inventory")), tables)
        }

        @Test
        fun `getTableInfo returns correct metadata`() {
            val info = env.getTableInfo(TableName("product"))
            assertEquals("Products", info.description)
            assertEquals(3, info.columns.size)
            assertTrue(info.columns.first { it.name == ColumnName("product_id") }.primaryKey)
        }

        @Test
        fun `getTableInfo throws for unknown table`() {
            assertThrows<IllegalArgumentException> {
                env.getTableInfo(TableName("nonexistent"))
            }
        }

        @Test
        fun `explain succeeds for valid SQL`() {
            val result = env.explain("SELECT * FROM product")
            assertIs<ExplainResult.Success>(result)
        }

        @Test
        fun `explain fails for invalid SQL`() {
            val result = env.explain("SELECT * FROM no_such_table")
            assertIs<ExplainResult.Failure>(result)
        }

        @Test
        fun `executeQuery returns typed dataset`() {
            val sql = SelectSqlStatement("SELECT product_id, unit_price FROM product ORDER BY product_id")
            val dataset = env.executeQuery(sql)
            assertEquals(2, dataset.columns.size)
            assertEquals(2, dataset.records.size)
            assertEquals(DataValue.StringValue("SKU-001"), dataset.records[0][ColumnName("product_id")])
            assertEquals(DataValue.DoubleValue(9.99), dataset.records[0][ColumnName("unit_price")])
        }

        @Test
        fun `executeQuery with join`() {
            val sql =
                SelectSqlStatement(
                    "SELECT p.product_name, i.quantity FROM product p JOIN inventory i ON p.product_id = i.product_id ORDER BY p.product_id",
                )
            val dataset = env.executeQuery(sql)
            assertEquals(2, dataset.records.size)
            assertEquals(DataValue.StringValue("Widget"), dataset.records[0][ColumnName("product_name")])
            assertEquals(DataValue.LongValue(100), dataset.records[0][ColumnName("quantity")])
        }

        @Test
        fun `environment is set as current`() {
            assertEquals(env, DatabaseEnvironment.current)
        }

        @Test
        fun `snapshot is null for fromStatements`() {
            assertEquals(null, env.snapshot)
        }

        @Test
        fun `invalid DDL prevents environment creation`() {
            assertThrows<Exception> {
                DuckDbDatabaseEnvironment.fromStatements(
                    ddl = listOf("NOT VALID SQL"),
                    dml = emptyList(),
                    tableMetadata = emptySet(),
                )
            }
        }

        @Test
        fun `invalid DML prevents environment creation`() {
            assertThrows<Exception> {
                DuckDbDatabaseEnvironment.fromStatements(
                    ddl = listOf("CREATE TABLE t (id INTEGER)"),
                    dml = listOf("INSERT INTO nonexistent VALUES (1)"),
                    tableMetadata = emptySet(),
                )
            }
        }
    }

    @Nested
    inner class FromParquet {
        @TempDir
        lateinit var tempDir: Path

        @Test
        fun `loads parquet files as tables`() {
            createParquetFile("items")

            val env = DuckDbDatabaseEnvironment.fromParquet(tempDir)

            val tables = env.listTables()
            assertEquals(setOf(TableName("items")), tables)
        }

        @Test
        fun `infers columns from parquet metadata`() {
            createParquetFile("items")

            val env = DuckDbDatabaseEnvironment.fromParquet(tempDir)
            val info = env.getTableInfo(TableName("items"))

            assertTrue(info.columns.any { it.name.value == "id" })
            assertTrue(info.columns.any { it.name.value == "name" })
            assertTrue(info.columns.any { it.name.value == "price" })
        }

        @Test
        fun `allows queries against loaded data`() {
            createParquetFile("items")

            val env = DuckDbDatabaseEnvironment.fromParquet(tempDir)
            DatabaseEnvironment.activate(env)

            val sql = SelectSqlStatement("SELECT name, price FROM items WHERE price > 15")
            val dataset = env.executeQuery(sql)

            assertEquals(1, dataset.records.size)
            assertEquals(DataValue.StringValue("Gadget"), dataset.records[0][ColumnName("name")])
        }

        @Test
        fun `snapshot is null for fromParquet`() {
            createParquetFile("items")
            val env = DuckDbDatabaseEnvironment.fromParquet(tempDir)
            assertEquals(null, env.snapshot)
        }

        @Test
        fun `rejects empty directory`() {
            assertThrows<IllegalArgumentException> {
                DuckDbDatabaseEnvironment.fromParquet(tempDir)
            }
        }

        @Test
        fun `rejects nonexistent directory`() {
            assertThrows<IllegalArgumentException> {
                DuckDbDatabaseEnvironment.fromParquet(tempDir.resolve("nonexistent"))
            }
        }

        private fun createParquetFile(tableName: String) {
            Class.forName("org.duckdb.DuckDBDriver")
            DriverManager.getConnection("jdbc:duckdb:").use { conn ->
                conn.createStatement().use { stmt ->
                    stmt.execute("CREATE TABLE $tableName (id INTEGER, name VARCHAR, price DOUBLE)")
                    stmt.execute("INSERT INTO $tableName VALUES (1, 'Widget', 9.99), (2, 'Gadget', 19.99)")
                    stmt.execute("COPY $tableName TO '${tempDir.resolve("$tableName.parquet")}'")
                }
            }
        }
    }

    @Nested
    inner class FromS3 {
        @TempDir
        lateinit var tempDir: Path

        private val testTimestamp = Instant.parse("2026-01-15T12:00:00Z")
        private val beforeTimestamp = AwsInstant.fromEpochSeconds(testTimestamp.epochSecond - 3600)

        private fun snapshot() = DatabaseEnvironmentSnapshot(timestamp = testTimestamp, versionSet = null)

        @Test
        fun `loads parquet files from mocked S3`() =
            runBlocking {
                val parquetBytes = createParquetBytes("orders")
                val s3Client =
                    mockS3Client(
                        files = mapOf("data/orders.parquet" to parquetBytes),
                    )

                val env =
                    DuckDbDatabaseEnvironment.fromS3(
                        snapshot = snapshot(),
                        s3Uri = S3Uri("s3://my-bucket/data"),
                        s3Client = s3Client,
                    )

                val tables = env.listTables()
                assertEquals(setOf(TableName("orders")), tables)
            }

        @Test
        fun `loads multiple parquet files from mocked S3`() =
            runBlocking {
                val ordersBytes = createParquetBytes("orders")
                val productsBytes = createParquetBytes("products")
                val s3Client =
                    mockS3Client(
                        files =
                            mapOf(
                                "data/orders.parquet" to ordersBytes,
                                "data/products.parquet" to productsBytes,
                            ),
                    )

                val env =
                    DuckDbDatabaseEnvironment.fromS3(
                        snapshot = snapshot(),
                        s3Uri = S3Uri("s3://my-bucket/data"),
                        s3Client = s3Client,
                    )

                assertEquals(setOf(TableName("orders"), TableName("products")), env.listTables())
            }

        @Test
        fun `queries data loaded from mocked S3`() =
            runBlocking {
                val parquetBytes = createParquetBytes("orders")
                val s3Client =
                    mockS3Client(
                        files = mapOf("data/orders.parquet" to parquetBytes),
                    )

                val env =
                    DuckDbDatabaseEnvironment.fromS3(
                        snapshot = snapshot(),
                        s3Uri = S3Uri("s3://my-bucket/data"),
                        s3Client = s3Client,
                    )
                DatabaseEnvironment.activate(env)

                val sql = SelectSqlStatement("SELECT name, price FROM orders WHERE price > 15")
                val dataset = env.executeQuery(sql)

                assertEquals(1, dataset.records.size)
                assertEquals(DataValue.StringValue("Gadget"), dataset.records[0][ColumnName("name")])
            }

        @Test
        fun `rejects when no parquet files at prefix`(): Unit =
            runBlocking {
                val s3Client = mockS3Client(files = emptyMap())

                assertThrows<IllegalArgumentException> {
                    DuckDbDatabaseEnvironment.fromS3(
                        snapshot = snapshot(),
                        s3Uri = S3Uri("s3://my-bucket/empty-prefix"),
                        s3Client = s3Client,
                    )
                }
            }

        @Test
        fun `ignores non-parquet files at prefix`() =
            runBlocking {
                val parquetBytes = createParquetBytes("orders")
                val s3Client =
                    mockS3Client(
                        files =
                            mapOf(
                                "data/orders.parquet" to parquetBytes,
                                "data/readme.txt" to "not parquet".toByteArray(),
                            ),
                    )

                val env =
                    DuckDbDatabaseEnvironment.fromS3(
                        snapshot = snapshot(),
                        s3Uri = S3Uri("s3://my-bucket/data"),
                        s3Client = s3Client,
                    )

                assertEquals(setOf(TableName("orders")), env.listTables())
            }

        @Test
        fun `snapshot contains resolved version set`() =
            runBlocking {
                val parquetBytes = createParquetBytes("orders")
                val s3Client =
                    mockS3Client(
                        files = mapOf("data/orders.parquet" to parquetBytes),
                    )

                val env =
                    DuckDbDatabaseEnvironment.fromS3(
                        snapshot = snapshot(),
                        s3Uri = S3Uri("s3://my-bucket/data"),
                        s3Client = s3Client,
                    )

                val resolved = env.snapshot
                assertEquals(testTimestamp, resolved?.timestamp)
                assertEquals(1, resolved?.versionSet?.versions?.size)
                val version = resolved?.versionSet?.versions?.first()
                assertEquals(S3ObjectKey("data/orders.parquet"), version?.fileReference)
                assertEquals("v1", version?.versionId)
            }

        private fun mockS3Client(files: Map<String, ByteArray>): S3Client {
            val s3Client = mockk<S3Client>()

            coEvery { s3Client.listObjectVersions(any<ListObjectVersionsRequest>()) } returns
                ListObjectVersionsResponse {
                    versions =
                        files.keys.map { key ->
                            ObjectVersion {
                                this.key = key
                                this.versionId = "v1"
                                this.lastModified = beforeTimestamp
                            }
                        }
                    isTruncated = false
                }

            files.forEach { (key, bytes) ->
                coEvery {
                    s3Client.getObject(
                        match<aws.sdk.kotlin.services.s3.model.GetObjectRequest> { it.key == key },
                        any<suspend (GetObjectResponse) -> Unit>(),
                    )
                } coAnswers {
                    val block = secondArg<suspend (GetObjectResponse) -> Unit>()
                    val response = GetObjectResponse { body = ByteStream.fromBytes(bytes) }
                    block(response)
                }
            }

            coEvery { s3Client.close() } returns Unit

            return s3Client
        }

        private fun createParquetBytes(tableName: String): ByteArray {
            val parquetFile = tempDir.resolve("$tableName.parquet")
            Class.forName("org.duckdb.DuckDBDriver")
            DriverManager.getConnection("jdbc:duckdb:").use { conn ->
                conn.createStatement().use { stmt ->
                    stmt.execute("CREATE TABLE $tableName (id INTEGER, name VARCHAR, price DOUBLE)")
                    stmt.execute("INSERT INTO $tableName VALUES (1, 'Widget', 9.99), (2, 'Gadget', 19.99)")
                    stmt.execute("COPY $tableName TO '${parquetFile.toAbsolutePath()}'")
                }
            }
            return java.nio.file.Files
                .readAllBytes(parquetFile)
        }
    }

    @Nested
    inner class ResolveVersionsAtTimestamp {
        @TempDir
        lateinit var tempDir: Path

        private val t0 = Instant.parse("2026-01-01T00:00:00Z")
        private val t1 = Instant.parse("2026-01-10T00:00:00Z")
        private val t2 = Instant.parse("2026-01-20T00:00:00Z")
        private val t3 = Instant.parse("2026-01-30T00:00:00Z")
        private val t4 = Instant.parse("2026-02-10T00:00:00Z")

        private fun awsInstant(instant: Instant): AwsInstant = AwsInstant.fromEpochSeconds(instant.epochSecond)

        private fun snapshot(timestamp: Instant) = DatabaseEnvironmentSnapshot(timestamp = timestamp, versionSet = null)

        @Test
        fun `resolves latest version before timestamp`() =
            runBlocking {
                val parquetBytes = createParquetBytes("orders")
                val s3Client =
                    mockVersionedS3Client(
                        versions =
                            listOf(
                                VersionEntry("data/orders.parquet", "v1", t1),
                                VersionEntry("data/orders.parquet", "v2", t2),
                                VersionEntry("data/orders.parquet", "v3", t4),
                            ),
                        fileContent = parquetBytes,
                    )

                val env =
                    DuckDbDatabaseEnvironment.fromS3(
                        snapshot = snapshot(t3),
                        s3Uri = S3Uri("s3://my-bucket/data"),
                        s3Client = s3Client,
                    )

                val version =
                    env.snapshot
                        ?.versionSet
                        ?.versions
                        ?.first()
                assertEquals(S3ObjectKey("data/orders.parquet"), version?.fileReference)
                assertEquals("v2", version?.versionId)
            }

        @Test
        fun `resolves version at exact timestamp boundary`() =
            runBlocking {
                val parquetBytes = createParquetBytes("orders")
                val s3Client =
                    mockVersionedS3Client(
                        versions =
                            listOf(
                                VersionEntry("data/orders.parquet", "v1", t1),
                                VersionEntry("data/orders.parquet", "v2", t2),
                            ),
                        fileContent = parquetBytes,
                    )

                val env =
                    DuckDbDatabaseEnvironment.fromS3(
                        snapshot = snapshot(t2),
                        s3Uri = S3Uri("s3://my-bucket/data"),
                        s3Client = s3Client,
                    )

                val version =
                    env.snapshot
                        ?.versionSet
                        ?.versions
                        ?.first()
                assertEquals("v2", version?.versionId)
            }

        @Test
        fun `excludes files created after timestamp`() =
            runBlocking {
                val parquetBytes = createParquetBytes("orders")
                val s3Client =
                    mockVersionedS3Client(
                        versions =
                            listOf(
                                VersionEntry("data/orders.parquet", "v1", t1),
                                VersionEntry("data/future.parquet", "v1", t4),
                            ),
                        fileContent = parquetBytes,
                    )

                val env =
                    DuckDbDatabaseEnvironment.fromS3(
                        snapshot = snapshot(t3),
                        s3Uri = S3Uri("s3://my-bucket/data"),
                        s3Client = s3Client,
                    )

                assertEquals(
                    1,
                    env.snapshot
                        ?.versionSet
                        ?.versions
                        ?.size,
                )
                assertEquals(
                    S3ObjectKey("data/orders.parquet"),
                    env.snapshot
                        ?.versionSet
                        ?.versions
                        ?.first()
                        ?.fileReference,
                )
            }

        @Test
        fun `fails when all files are after timestamp`(): Unit =
            runBlocking {
                val s3Client =
                    mockVersionedS3Client(
                        versions =
                            listOf(
                                VersionEntry("data/orders.parquet", "v1", t4),
                            ),
                        fileContent = ByteArray(0),
                    )

                assertThrows<IllegalArgumentException> {
                    DuckDbDatabaseEnvironment.fromS3(
                        snapshot = snapshot(t1),
                        s3Uri = S3Uri("s3://my-bucket/data"),
                        s3Client = s3Client,
                    )
                }
            }

        @Test
        fun `excludes file deleted before timestamp`(): Unit =
            runBlocking {
                val s3Client =
                    mockVersionedS3Client(
                        versions =
                            listOf(
                                VersionEntry("data/orders.parquet", "v1", t1),
                            ),
                        deleteMarkers =
                            listOf(
                                DeleteMarkerInfo("data/orders.parquet", t2),
                            ),
                        fileContent = ByteArray(0),
                    )

                assertThrows<IllegalArgumentException> {
                    DuckDbDatabaseEnvironment.fromS3(
                        snapshot = snapshot(t3),
                        s3Uri = S3Uri("s3://my-bucket/data"),
                        s3Client = s3Client,
                    )
                }
            }

        @Test
        fun `includes file re-uploaded after delete`() =
            runBlocking {
                val parquetBytes = createParquetBytes("orders")
                val s3Client =
                    mockVersionedS3Client(
                        versions =
                            listOf(
                                VersionEntry("data/orders.parquet", "v1", t1),
                                VersionEntry("data/orders.parquet", "v3", t3),
                            ),
                        deleteMarkers =
                            listOf(
                                DeleteMarkerInfo("data/orders.parquet", t2),
                            ),
                        fileContent = parquetBytes,
                    )

                val env =
                    DuckDbDatabaseEnvironment.fromS3(
                        snapshot = snapshot(t4),
                        s3Uri = S3Uri("s3://my-bucket/data"),
                        s3Client = s3Client,
                    )

                val version =
                    env.snapshot
                        ?.versionSet
                        ?.versions
                        ?.first()
                assertEquals("v3", version?.versionId)
            }

        @Test
        fun `delete marker after timestamp does not affect resolution`() =
            runBlocking {
                val parquetBytes = createParquetBytes("orders")
                val s3Client =
                    mockVersionedS3Client(
                        versions =
                            listOf(
                                VersionEntry("data/orders.parquet", "v1", t1),
                            ),
                        deleteMarkers =
                            listOf(
                                DeleteMarkerInfo("data/orders.parquet", t4),
                            ),
                        fileContent = parquetBytes,
                    )

                val env =
                    DuckDbDatabaseEnvironment.fromS3(
                        snapshot = snapshot(t2),
                        s3Uri = S3Uri("s3://my-bucket/data"),
                        s3Client = s3Client,
                    )

                val version =
                    env.snapshot
                        ?.versionSet
                        ?.versions
                        ?.first()
                assertEquals(S3ObjectKey("data/orders.parquet"), version?.fileReference)
                assertEquals("v1", version?.versionId)
            }

        @Test
        fun `resolves multiple files independently`() =
            runBlocking {
                val parquetBytes = createParquetBytes("orders")
                val s3Client =
                    mockVersionedS3Client(
                        versions =
                            listOf(
                                VersionEntry("data/orders.parquet", "v1", t1),
                                VersionEntry("data/orders.parquet", "v2", t3),
                                VersionEntry("data/products.parquet", "v1", t1),
                                VersionEntry("data/products.parquet", "v2", t2),
                            ),
                        fileContent = parquetBytes,
                    )

                val env =
                    DuckDbDatabaseEnvironment.fromS3(
                        snapshot = snapshot(t2),
                        s3Uri = S3Uri("s3://my-bucket/data"),
                        s3Client = s3Client,
                    )

                val versions = env.snapshot?.versionSet?.versions ?: emptySet()
                assertEquals(2, versions.size)

                val ordersVersion = versions.first { it.fileReference.value == "data/orders.parquet" }
                assertEquals("v1", ordersVersion.versionId)

                val productsVersion = versions.first { it.fileReference.value == "data/products.parquet" }
                assertEquals("v2", productsVersion.versionId)
            }

        @Test
        fun `pre-versioning object has null versionId`() =
            runBlocking {
                val parquetBytes = createParquetBytes("orders")
                val s3Client =
                    mockVersionedS3Client(
                        versions =
                            listOf(
                                VersionEntry("data/orders.parquet", "null", t1),
                            ),
                        fileContent = parquetBytes,
                    )

                val env =
                    DuckDbDatabaseEnvironment.fromS3(
                        snapshot = snapshot(t2),
                        s3Uri = S3Uri("s3://my-bucket/data"),
                        s3Client = s3Client,
                    )

                val version =
                    env.snapshot
                        ?.versionSet
                        ?.versions
                        ?.first()
                assertNull(version?.versionId)
            }

        @Test
        fun `handles paginated version listing`() =
            runBlocking {
                val parquetBytes = createParquetBytes("orders")
                val s3Client = mockk<S3Client>()

                var callCount = 0
                coEvery { s3Client.listObjectVersions(any<ListObjectVersionsRequest>()) } answers {
                    callCount++
                    if (callCount == 1) {
                        ListObjectVersionsResponse {
                            versions =
                                listOf(
                                    ObjectVersion {
                                        key = "data/orders.parquet"
                                        versionId = "v1"
                                        lastModified = awsInstant(t1)
                                    },
                                )
                            isTruncated = true
                            nextKeyMarker = "data/orders.parquet"
                            nextVersionIdMarker = "v1"
                        }
                    } else {
                        ListObjectVersionsResponse {
                            versions =
                                listOf(
                                    ObjectVersion {
                                        key = "data/products.parquet"
                                        versionId = "v1"
                                        lastModified = awsInstant(t1)
                                    },
                                )
                            isTruncated = false
                        }
                    }
                }

                coEvery {
                    s3Client.getObject(
                        any<aws.sdk.kotlin.services.s3.model.GetObjectRequest>(),
                        any<suspend (GetObjectResponse) -> Unit>(),
                    )
                } coAnswers {
                    val block = secondArg<suspend (GetObjectResponse) -> Unit>()
                    block(GetObjectResponse { body = ByteStream.fromBytes(parquetBytes) })
                }

                coEvery { s3Client.close() } returns Unit

                val env =
                    DuckDbDatabaseEnvironment.fromS3(
                        snapshot = snapshot(t2),
                        s3Uri = S3Uri("s3://my-bucket/data"),
                        s3Client = s3Client,
                    )

                assertEquals(
                    2,
                    env.snapshot
                        ?.versionSet
                        ?.versions
                        ?.size,
                )
                assertEquals(2, callCount)
            }

        @Test
        fun `ignores non-parquet versions`() =
            runBlocking {
                val parquetBytes = createParquetBytes("orders")
                val s3Client =
                    mockVersionedS3Client(
                        versions =
                            listOf(
                                VersionEntry("data/orders.parquet", "v1", t1),
                                VersionEntry("data/metadata.json", "v1", t1),
                                VersionEntry("data/readme.txt", "v1", t1),
                            ),
                        fileContent = parquetBytes,
                    )

                val env =
                    DuckDbDatabaseEnvironment.fromS3(
                        snapshot = snapshot(t2),
                        s3Uri = S3Uri("s3://my-bucket/data"),
                        s3Client = s3Client,
                    )

                assertEquals(
                    1,
                    env.snapshot
                        ?.versionSet
                        ?.versions
                        ?.size,
                )
                assertEquals(
                    S3ObjectKey("data/orders.parquet"),
                    env.snapshot
                        ?.versionSet
                        ?.versions
                        ?.first()
                        ?.fileReference,
                )
            }

        @Test
        fun `delete marker on non-parquet file does not affect parquet resolution`() =
            runBlocking {
                val parquetBytes = createParquetBytes("orders")
                val s3Client =
                    mockVersionedS3Client(
                        versions =
                            listOf(
                                VersionEntry("data/orders.parquet", "v1", t1),
                                VersionEntry("data/readme.txt", "v1", t1),
                            ),
                        deleteMarkers =
                            listOf(
                                DeleteMarkerInfo("data/readme.txt", t2),
                            ),
                        fileContent = parquetBytes,
                    )

                val env =
                    DuckDbDatabaseEnvironment.fromS3(
                        snapshot = snapshot(t3),
                        s3Uri = S3Uri("s3://my-bucket/data"),
                        s3Client = s3Client,
                    )

                assertEquals(
                    1,
                    env.snapshot
                        ?.versionSet
                        ?.versions
                        ?.size,
                )
            }

        @Test
        fun `multiple delete markers picks latest before timestamp`(): Unit =
            runBlocking {
                val s3Client =
                    mockVersionedS3Client(
                        versions =
                            listOf(
                                VersionEntry("data/orders.parquet", "v1", t0),
                                VersionEntry("data/orders.parquet", "v2", t2),
                            ),
                        deleteMarkers =
                            listOf(
                                DeleteMarkerInfo("data/orders.parquet", t1),
                                DeleteMarkerInfo("data/orders.parquet", t3),
                            ),
                        fileContent = ByteArray(0),
                    )

                assertThrows<IllegalArgumentException> {
                    DuckDbDatabaseEnvironment.fromS3(
                        snapshot = snapshot(t4),
                        s3Uri = S3Uri("s3://my-bucket/data"),
                        s3Client = s3Client,
                    )
                }
            }

        @Test
        fun `version at same time as delete marker is not excluded`() =
            runBlocking {
                val parquetBytes = createParquetBytes("orders")
                val s3Client =
                    mockVersionedS3Client(
                        versions =
                            listOf(
                                VersionEntry("data/orders.parquet", "v1", t1),
                            ),
                        deleteMarkers =
                            listOf(
                                DeleteMarkerInfo("data/orders.parquet", t1),
                            ),
                        fileContent = parquetBytes,
                    )

                val env =
                    DuckDbDatabaseEnvironment.fromS3(
                        snapshot = snapshot(t2),
                        s3Uri = S3Uri("s3://my-bucket/data"),
                        s3Client = s3Client,
                    )

                val version =
                    env.snapshot
                        ?.versionSet
                        ?.versions
                        ?.first()
                assertEquals("v1", version?.versionId)
            }

        @Test
        fun `empty version list returns empty set`(): Unit =
            runBlocking {
                val s3Client =
                    mockVersionedS3Client(
                        versions = emptyList(),
                        fileContent = ByteArray(0),
                    )

                assertThrows<IllegalArgumentException> {
                    DuckDbDatabaseEnvironment.fromS3(
                        snapshot = snapshot(t2),
                        s3Uri = S3Uri("s3://my-bucket/data"),
                        s3Client = s3Client,
                    )
                }
            }

        private fun mockVersionedS3Client(
            versions: List<VersionEntry>,
            deleteMarkers: List<DeleteMarkerInfo> = emptyList(),
            fileContent: ByteArray,
        ): S3Client {
            val s3Client = mockk<S3Client>()

            coEvery { s3Client.listObjectVersions(any<ListObjectVersionsRequest>()) } returns
                ListObjectVersionsResponse {
                    this.versions =
                        versions.map { entry ->
                            ObjectVersion {
                                key = entry.key
                                versionId = entry.versionId
                                lastModified = awsInstant(entry.lastModified)
                            }
                        }
                    this.deleteMarkers =
                        deleteMarkers.map { marker ->
                            DeleteMarkerEntry {
                                key = marker.key
                                lastModified = awsInstant(marker.lastModified)
                            }
                        }
                    isTruncated = false
                }

            coEvery {
                s3Client.getObject(
                    any<aws.sdk.kotlin.services.s3.model.GetObjectRequest>(),
                    any<suspend (GetObjectResponse) -> Unit>(),
                )
            } coAnswers {
                val block = secondArg<suspend (GetObjectResponse) -> Unit>()
                block(GetObjectResponse { body = ByteStream.fromBytes(fileContent) })
            }

            coEvery { s3Client.close() } returns Unit

            return s3Client
        }

        private fun createParquetBytes(tableName: String): ByteArray {
            val parquetFile = tempDir.resolve("$tableName.parquet")
            Class.forName("org.duckdb.DuckDBDriver")
            DriverManager.getConnection("jdbc:duckdb:").use { conn ->
                conn.createStatement().use { stmt ->
                    stmt.execute("CREATE TABLE $tableName (id INTEGER, name VARCHAR, price DOUBLE)")
                    stmt.execute("INSERT INTO $tableName VALUES (1, 'Widget', 9.99), (2, 'Gadget', 19.99)")
                    stmt.execute("COPY $tableName TO '${parquetFile.toAbsolutePath()}'")
                }
            }
            return java.nio.file.Files
                .readAllBytes(parquetFile)
        }
    }
}
