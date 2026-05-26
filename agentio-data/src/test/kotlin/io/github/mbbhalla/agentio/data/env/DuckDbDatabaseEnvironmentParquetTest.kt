package io.github.mbbhalla.agentio.data.env

import io.github.mbbhalla.agentio.data.model.ColumnName
import io.github.mbbhalla.agentio.data.model.DataValue
import io.github.mbbhalla.agentio.data.model.TableName
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DuckDbDatabaseEnvironmentParquetTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `fromParquet loads parquet files as tables`() {
        createParquetFile("items")

        val env = DuckDbDatabaseEnvironment.fromParquet(tempDir)

        val tables = env.listTables()
        assertEquals(setOf(TableName("items")), tables)
    }

    @Test
    fun `fromParquet infers columns from parquet metadata`() {
        createParquetFile("items")

        val env = DuckDbDatabaseEnvironment.fromParquet(tempDir)
        val info = env.getTableInfo(TableName("items"))

        assertTrue(info.columns.any { it.name.value == "id" })
        assertTrue(info.columns.any { it.name.value == "name" })
        assertTrue(info.columns.any { it.name.value == "price" })
    }

    @Test
    fun `fromParquet allows queries against loaded data`() {
        createParquetFile("items")

        val env = DuckDbDatabaseEnvironment.fromParquet(tempDir)
        DatabaseEnvironment.activate(env)

        val sql = SelectSqlStatement("SELECT name, price FROM items WHERE price > 15")
        val dataset = env.executeQuery(sql)

        assertEquals(1, dataset.records.size)
        assertEquals(DataValue.StringValue("Gadget"), dataset.records[0][ColumnName("name")])
    }

    @Test
    fun `fromParquet rejects empty directory`() {
        assertThrows<IllegalArgumentException> {
            DuckDbDatabaseEnvironment.fromParquet(tempDir)
        }
    }

    @Test
    fun `fromParquet rejects nonexistent directory`() {
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
