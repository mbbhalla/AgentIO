package io.github.mbbhalla.agentio.data.env

import io.github.mbbhalla.agentio.data.model.ColumnInfo
import io.github.mbbhalla.agentio.data.model.ColumnName
import io.github.mbbhalla.agentio.data.model.ColumnType
import io.github.mbbhalla.agentio.data.model.TableInfo
import io.github.mbbhalla.agentio.data.model.TableName
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.assertThrows
import kotlin.test.Test
import kotlin.test.assertEquals

class SqlStatementsTest {
    companion object {
        @BeforeAll
        @JvmStatic
        fun setup() {
            DuckDbDatabaseEnvironment.fromStatements(
                ddl =
                    listOf(
                        "CREATE TABLE orders (order_id VARCHAR PRIMARY KEY, amount DOUBLE NOT NULL)",
                    ),
                dml =
                    listOf(
                        "INSERT INTO orders VALUES ('O-1', 42.0)",
                    ),
                tableMetadata =
                    setOf(
                        TableInfo(
                            name = TableName("orders"),
                            description = "Orders",
                            columns =
                                listOf(
                                    ColumnInfo(ColumnName("order_id"), ColumnType.VARCHAR, false, true, null, "Order ID"),
                                    ColumnInfo(ColumnName("amount"), ColumnType.DOUBLE, false, false, null, "Amount"),
                                ),
                        ),
                    ),
            )
        }
    }

    @Test
    fun `SelectSqlStatement validates correct SQL`() {
        val stmt = SelectSqlStatement("SELECT * FROM orders")
        assertEquals("SELECT * FROM orders", stmt.value)
    }

    @Test
    fun `SelectSqlStatement with WHERE clause`() {
        val stmt = SelectSqlStatement("SELECT order_id FROM orders WHERE amount > 10")
        assertEquals("SELECT order_id FROM orders WHERE amount > 10", stmt.value)
    }

    @Test
    fun `SelectSqlStatement with leading comment`() {
        val sql = "-- Find all orders\nSELECT * FROM orders"
        val stmt = SelectSqlStatement(sql)
        assertEquals(sql, stmt.value)
    }

    @Test
    fun `SelectSqlStatement rejects invalid table reference`() {
        assertThrows<IllegalArgumentException> {
            SelectSqlStatement("SELECT * FROM no_such_table")
        }
    }

    @Test
    fun `SelectSqlStatement rejects invalid column reference`() {
        assertThrows<IllegalArgumentException> {
            SelectSqlStatement("SELECT no_such_column FROM orders")
        }
    }

    @Test
    fun `SelectSqlStatement rejects syntax errors`() {
        assertThrows<IllegalArgumentException> {
            SelectSqlStatement("SELCET * FORM orders")
        }
    }

    @Test
    fun `SelectSqlStatement rejects INSERT disguised as SELECT`() {
        assertThrows<IllegalArgumentException> {
            SelectSqlStatement("INSERT INTO orders VALUES ('O-2', 100.0)")
        }
    }

    @Test
    fun `SelectSqlStatement rejects empty string`() {
        assertThrows<IllegalArgumentException> {
            SelectSqlStatement("")
        }
    }

    @Test
    fun `InsertSqlStatement validates correct INSERT`() {
        val stmt = InsertSqlStatement("INSERT INTO orders VALUES ('O-2', 100.0)")
        assertEquals("INSERT INTO orders VALUES ('O-2', 100.0)", stmt.value)
    }

    @Test
    fun `InsertSqlStatement with leading comment`() {
        val sql = "-- Add new order\nINSERT INTO orders VALUES ('O-3', 55.0)"
        val stmt = InsertSqlStatement(sql)
        assertEquals(sql, stmt.value)
    }

    @Test
    fun `InsertSqlStatement rejects invalid table reference`() {
        assertThrows<IllegalArgumentException> {
            InsertSqlStatement("INSERT INTO no_such_table VALUES (1)")
        }
    }

    @Test
    fun `InsertSqlStatement rejects garbage string`() {
        assertThrows<IllegalArgumentException> {
            InsertSqlStatement("INSERT!@#\$%^&")
        }
    }

    @Test
    fun `InsertSqlStatement rejects SELECT disguised as INSERT`() {
        assertThrows<IllegalArgumentException> {
            InsertSqlStatement("SELECT * FROM orders")
        }
    }

    @Test
    fun `InsertSqlStatement rejects empty string`() {
        assertThrows<IllegalArgumentException> {
            InsertSqlStatement("")
        }
    }

    @Test
    fun `UpdateSqlStatement validates correct UPDATE`() {
        val stmt = UpdateSqlStatement("UPDATE orders SET amount = 99.0 WHERE order_id = 'O-1'")
        assertEquals("UPDATE orders SET amount = 99.0 WHERE order_id = 'O-1'", stmt.value)
    }

    @Test
    fun `UpdateSqlStatement with leading comment`() {
        val sql = "-- fix amount\nUPDATE orders SET amount = 50.0 WHERE order_id = 'O-1'"
        val stmt = UpdateSqlStatement(sql)
        assertEquals(sql, stmt.value)
    }

    @Test
    fun `UpdateSqlStatement rejects invalid table reference`() {
        assertThrows<IllegalArgumentException> {
            UpdateSqlStatement("UPDATE no_such_table SET x = 1")
        }
    }

    @Test
    fun `UpdateSqlStatement rejects SELECT`() {
        assertThrows<IllegalArgumentException> {
            UpdateSqlStatement("SELECT * FROM orders")
        }
    }

    @Test
    fun `UpdateSqlStatement rejects empty string`() {
        assertThrows<IllegalArgumentException> {
            UpdateSqlStatement("")
        }
    }

    @Test
    fun `DeleteSqlStatement validates correct DELETE`() {
        val stmt = DeleteSqlStatement("DELETE FROM orders WHERE order_id = 'O-1'")
        assertEquals("DELETE FROM orders WHERE order_id = 'O-1'", stmt.value)
    }

    @Test
    fun `DeleteSqlStatement with leading comment`() {
        val sql = "-- remove order\nDELETE FROM orders WHERE amount < 10"
        val stmt = DeleteSqlStatement(sql)
        assertEquals(sql, stmt.value)
    }

    @Test
    fun `DeleteSqlStatement rejects invalid table reference`() {
        assertThrows<IllegalArgumentException> {
            DeleteSqlStatement("DELETE FROM no_such_table WHERE id = 1")
        }
    }

    @Test
    fun `DeleteSqlStatement rejects INSERT`() {
        assertThrows<IllegalArgumentException> {
            DeleteSqlStatement("INSERT INTO orders VALUES ('O-5', 10.0)")
        }
    }

    @Test
    fun `DeleteSqlStatement rejects empty string`() {
        assertThrows<IllegalArgumentException> {
            DeleteSqlStatement("")
        }
    }
}
