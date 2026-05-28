package io.github.mbbhalla.agentio.data.env

import aws.sdk.kotlin.services.s3.S3Client
import aws.sdk.kotlin.services.s3.model.GetObjectResponse
import aws.sdk.kotlin.services.s3.model.ListObjectsV2Response
import aws.smithy.kotlin.runtime.content.ByteStream
import io.github.mbbhalla.agentio.data.model.ColumnInfo
import io.github.mbbhalla.agentio.data.model.ColumnName
import io.github.mbbhalla.agentio.data.model.ColumnType
import io.github.mbbhalla.agentio.data.model.DataValue
import io.github.mbbhalla.agentio.data.model.ExplainResult
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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import aws.sdk.kotlin.services.s3.model.Object as S3Object

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
                                        ColumnInfo(ColumnName("product_id"), ColumnType.VARCHAR, false, true, null, "Product ID"),
                                        ColumnInfo(ColumnName("product_name"), ColumnType.VARCHAR, false, false, null, "Product name"),
                                        ColumnInfo(ColumnName("unit_price"), ColumnType.DOUBLE, false, false, null, "Price"),
                                    ),
                            ),
                            TableInfo(
                                name = TableName("inventory"),
                                description = "Inventory levels",
                                columns =
                                    listOf(
                                        ColumnInfo(ColumnName("product_id"), ColumnType.VARCHAR, false, true, null, "Product ID"),
                                        ColumnInfo(ColumnName("quantity"), ColumnType.INTEGER, false, false, null, "Qty on hand"),
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
        fun `rejects when no parquet files at prefix`() =
            runBlocking {
                val s3Client = mockS3Client(files = emptyMap())

                assertThrows<IllegalArgumentException> {
                    DuckDbDatabaseEnvironment.fromS3(
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
                        s3Uri = S3Uri("s3://my-bucket/data"),
                        s3Client = s3Client,
                    )

                assertEquals(setOf(TableName("orders")), env.listTables())
            }

        private fun mockS3Client(files: Map<String, ByteArray>): S3Client {
            val s3Client = mockk<S3Client>()

            coEvery { s3Client.listObjectsV2(any<aws.sdk.kotlin.services.s3.model.ListObjectsV2Request>()) } returns
                ListObjectsV2Response {
                    contents = files.keys.map { key -> S3Object { this.key = key } }
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
}
