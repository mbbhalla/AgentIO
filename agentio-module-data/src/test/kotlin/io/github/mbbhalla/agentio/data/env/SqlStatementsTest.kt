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
import kotlin.test.assertSame

class SqlStatementsTest {
    companion object {
        private lateinit var env: DuckDbDatabaseEnvironment

        @BeforeAll
        @JvmStatic
        fun setup() {
            env =
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

    private fun select(sql: String) = env.selectStatement(sql).getOrThrow()

    private fun insert(sql: String) = env.insertStatement(sql).getOrThrow()

    private fun update(sql: String) = env.updateStatement(sql).getOrThrow()

    private fun delete(sql: String) = env.deleteStatement(sql).getOrThrow()

    @Test
    fun `SelectSqlStatement validates correct SQL`() {
        val stmt = select("SELECT * FROM orders")
        assertEquals("SELECT * FROM orders", stmt.value)
    }

    @Test
    fun `SelectSqlStatement records the environment that validated it`() {
        assertSame(env, select("SELECT * FROM orders").environment)
    }

    @Test
    fun `SelectSqlStatement with WHERE clause`() {
        val stmt = select("SELECT order_id FROM orders WHERE amount > 10")
        assertEquals("SELECT order_id FROM orders WHERE amount > 10", stmt.value)
    }

    @Test
    fun `SelectSqlStatement with leading comment`() {
        val sql = "-- Find all orders\nSELECT * FROM orders"
        val stmt = select(sql)
        assertEquals(sql, stmt.value)
    }

    @Test
    fun `SelectSqlStatement rejects invalid table reference`() {
        assertThrows<IllegalArgumentException> {
            select("SELECT * FROM no_such_table")
        }
    }

    @Test
    fun `SelectSqlStatement rejects invalid column reference`() {
        assertThrows<IllegalArgumentException> {
            select("SELECT no_such_column FROM orders")
        }
    }

    @Test
    fun `SelectSqlStatement rejects syntax errors`() {
        assertThrows<IllegalArgumentException> {
            select("SELCET * FORM orders")
        }
    }

    @Test
    fun `SelectSqlStatement rejects INSERT disguised as SELECT`() {
        val error =
            assertThrows<IllegalArgumentException> {
                select("INSERT INTO orders VALUES ('O-2', 100.0)")
            }
        assertEquals("Not a SELECT statement", error.message)
    }

    @Test
    fun `SelectSqlStatement rejects DDL`() {
        assertThrows<IllegalArgumentException> {
            select("DROP TABLE orders")
        }
    }

    @Test
    fun `SelectSqlStatement rejects COPY to file`() {
        assertThrows<IllegalArgumentException> {
            select("COPY orders TO 'orders.csv'")
        }
    }

    @Test
    fun `SelectSqlStatement rejects DELETE with RETURNING`() {
        assertThrows<IllegalArgumentException> {
            select("DELETE FROM orders RETURNING *")
        }
    }

    @Test
    fun `SelectSqlStatement rejects a SELECT that writes through a CTE`() {
        listOf(
            "WITH d AS (DELETE FROM orders RETURNING *) SELECT * FROM d",
            "WITH i AS (INSERT INTO orders VALUES ('O-9', 1.0) RETURNING *) SELECT * FROM i",
            "WITH u AS (UPDATE orders SET amount = 0 RETURNING *) SELECT * FROM u",
        ).forEach { sql -> assertThrows<IllegalArgumentException>(sql) { select(sql) } }
    }

    @Test
    fun `SelectSqlStatement rejects empty string`() {
        assertThrows<IllegalArgumentException> {
            select("")
        }
    }

    @Test
    fun `InsertSqlStatement validates correct INSERT`() {
        val stmt = insert("INSERT INTO orders VALUES ('O-2', 100.0)")
        assertEquals("INSERT INTO orders VALUES ('O-2', 100.0)", stmt.value)
    }

    @Test
    fun `InsertSqlStatement with leading comment`() {
        val sql = "-- Add new order\nINSERT INTO orders VALUES ('O-3', 55.0)"
        val stmt = insert(sql)
        assertEquals(sql, stmt.value)
    }

    @Test
    fun `InsertSqlStatement rejects invalid table reference`() {
        assertThrows<IllegalArgumentException> {
            insert("INSERT INTO no_such_table VALUES (1)")
        }
    }

    @Test
    fun `InsertSqlStatement rejects garbage string`() {
        assertThrows<IllegalArgumentException> {
            insert("INSERT!@#\$%^&")
        }
    }

    @Test
    fun `InsertSqlStatement rejects SELECT disguised as INSERT`() {
        val error =
            assertThrows<IllegalArgumentException> {
                insert("SELECT * FROM orders")
            }
        assertEquals("Not an INSERT statement", error.message)
    }

    @Test
    fun `InsertSqlStatement rejects empty string`() {
        assertThrows<IllegalArgumentException> {
            insert("")
        }
    }

    @Test
    fun `UpdateSqlStatement validates correct UPDATE`() {
        val stmt = update("UPDATE orders SET amount = 99.0 WHERE order_id = 'O-1'")
        assertEquals("UPDATE orders SET amount = 99.0 WHERE order_id = 'O-1'", stmt.value)
    }

    @Test
    fun `UpdateSqlStatement with leading comment`() {
        val sql = "-- fix amount\nUPDATE orders SET amount = 50.0 WHERE order_id = 'O-1'"
        val stmt = update(sql)
        assertEquals(sql, stmt.value)
    }

    @Test
    fun `UpdateSqlStatement rejects invalid table reference`() {
        assertThrows<IllegalArgumentException> {
            update("UPDATE no_such_table SET x = 1")
        }
    }

    @Test
    fun `UpdateSqlStatement rejects SELECT`() {
        assertThrows<IllegalArgumentException> {
            update("SELECT * FROM orders")
        }
    }

    @Test
    fun `UpdateSqlStatement rejects empty string`() {
        assertThrows<IllegalArgumentException> {
            update("")
        }
    }

    @Test
    fun `DeleteSqlStatement validates correct DELETE`() {
        val stmt = delete("DELETE FROM orders WHERE order_id = 'O-1'")
        assertEquals("DELETE FROM orders WHERE order_id = 'O-1'", stmt.value)
    }

    @Test
    fun `DeleteSqlStatement with leading comment`() {
        val sql = "-- remove order\nDELETE FROM orders WHERE amount < 10"
        val stmt = delete(sql)
        assertEquals(sql, stmt.value)
    }

    @Test
    fun `DeleteSqlStatement rejects invalid table reference`() {
        assertThrows<IllegalArgumentException> {
            delete("DELETE FROM no_such_table WHERE id = 1")
        }
    }

    @Test
    fun `DeleteSqlStatement rejects INSERT`() {
        assertThrows<IllegalArgumentException> {
            delete("INSERT INTO orders VALUES ('O-5', 10.0)")
        }
    }

    @Test
    fun `DeleteSqlStatement rejects empty string`() {
        assertThrows<IllegalArgumentException> {
            delete("")
        }
    }
}
