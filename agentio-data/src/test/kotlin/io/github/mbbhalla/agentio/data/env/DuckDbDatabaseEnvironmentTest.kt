package io.github.mbbhalla.agentio.data.env

import io.github.mbbhalla.agentio.data.model.ColumnInfo
import io.github.mbbhalla.agentio.data.model.ColumnName
import io.github.mbbhalla.agentio.data.model.ColumnType
import io.github.mbbhalla.agentio.data.model.DataValue
import io.github.mbbhalla.agentio.data.model.ExplainResult
import io.github.mbbhalla.agentio.data.model.TableInfo
import io.github.mbbhalla.agentio.data.model.TableName

import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.assertThrows
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class DuckDbDatabaseEnvironmentTest {

    companion object {
        private lateinit var env: DuckDbDatabaseEnvironment

        @BeforeAll
        @JvmStatic
        fun setup() {
            env = DuckDbDatabaseEnvironment.fromStatements(
                ddl = listOf(
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
                dml = listOf(
                    "INSERT INTO product VALUES ('SKU-001', 'Widget', 9.99)",
                    "INSERT INTO product VALUES ('SKU-002', 'Gadget', 19.99)",
                    "INSERT INTO inventory VALUES ('SKU-001', 100)",
                    "INSERT INTO inventory VALUES ('SKU-002', 50)",
                ),
                tableMetadata = setOf(
                    TableInfo(
                        name = TableName("product"),
                        description = "Products",
                        columns = listOf(
                            ColumnInfo(ColumnName("product_id"), ColumnType.VARCHAR,
                                nullable = false,
                                primaryKey = true,
                                foreignKey = null,
                                description = "Product ID"
                            ),
                            ColumnInfo(ColumnName("product_name"), ColumnType.VARCHAR,
                                nullable = false,
                                primaryKey = false,
                                foreignKey = null,
                                description = "Product name"
                            ),
                            ColumnInfo(ColumnName("unit_price"), ColumnType.DOUBLE,
                                nullable = false,
                                primaryKey = false,
                                foreignKey = null,
                                description = "Price"
                            ),
                        ),
                    ),
                    TableInfo(
                        name = TableName("inventory"),
                        description = "Inventory levels",
                        columns = listOf(
                            ColumnInfo(ColumnName("product_id"), ColumnType.VARCHAR,
                                nullable = false,
                                primaryKey = true,
                                foreignKey = null,
                                description = "Product ID"
                            ),
                            ColumnInfo(ColumnName("quantity"), ColumnType.INTEGER,
                                nullable = false,
                                primaryKey = false,
                                foreignKey = null,
                                description = "Qty on hand"
                            ),
                        ),
                    ),
                ),
            )
        }
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
        val sql = SelectSqlStatement(
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
