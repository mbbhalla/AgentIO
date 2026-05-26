package io.github.mbbhalla.agentio.data.env

import io.github.mbbhalla.agentio.data.model.ColumnInfo
import io.github.mbbhalla.agentio.data.model.ColumnName
import io.github.mbbhalla.agentio.data.model.ColumnType
import io.github.mbbhalla.agentio.data.model.TableInfo
import io.github.mbbhalla.agentio.data.model.TableName
import org.junit.jupiter.api.BeforeAll
import kotlin.test.Test
import kotlin.test.assertEquals

class
StatementTypeTest {

    companion object {
        private lateinit var env: DuckDbDatabaseEnvironment

        @BeforeAll
        @JvmStatic
        fun setup() {
            env = DuckDbDatabaseEnvironment.fromStatements(
                ddl = listOf(
                    "CREATE TABLE items (id INTEGER PRIMARY KEY, name VARCHAR NOT NULL, price DOUBLE NOT NULL)",
                ),
                dml = listOf(
                    "INSERT INTO items VALUES (1, 'Widget', 9.99)",
                    "INSERT INTO items VALUES (2, 'Gadget', 19.99)",
                ),
                tableMetadata = setOf(
                    TableInfo(
                        name = TableName("items"),
                        description = "Items",
                        columns = listOf(
                            ColumnInfo(ColumnName("id"), ColumnType.INTEGER, false, true, null, "ID"),
                            ColumnInfo(ColumnName("name"), ColumnType.VARCHAR, false, false, null, "Name"),
                            ColumnInfo(ColumnName("price"), ColumnType.DOUBLE, false, false, null, "Price"),
                        ),
                    ),
                ),
            )
        }
    }

    @Test
    fun `SELECT is identified as SELECT`() {
        assertEquals(StatementType.SELECT, env.statementType("SELECT * FROM items"))
    }

    @Test
    fun `SELECT with WHERE is identified as SELECT`() {
        assertEquals(StatementType.SELECT, env.statementType("SELECT name FROM items WHERE price > 10"))
    }

    @Test
    fun `SELECT with JOIN is identified as SELECT`() {
        assertEquals(StatementType.SELECT, env.statementType("SELECT * FROM items i JOIN items i2 ON i.id = i2.id"))
    }

    @Test
    fun `SELECT with leading comment is identified as SELECT`() {
        assertEquals(StatementType.SELECT, env.statementType("-- query\nSELECT * FROM items"))
    }

    @Test
    fun `INSERT is identified as INSERT`() {
        assertEquals(StatementType.INSERT, env.statementType("INSERT INTO items VALUES (3, 'Thing', 5.0)"))
    }

    @Test
    fun `INSERT with leading comment is identified as INSERT`() {
        assertEquals(StatementType.INSERT, env.statementType("-- add item\nINSERT INTO items VALUES (4, 'Foo', 1.0)"))
    }

    @Test
    fun `INSERT with block comment is identified as INSERT`() {
        assertEquals(StatementType.INSERT, env.statementType("/* batch */ INSERT INTO items VALUES (5, 'Bar', 2.0)"))
    }

    @Test
    fun `UPDATE is identified as UPDATE`() {
        assertEquals(StatementType.UPDATE, env.statementType("UPDATE items SET price = 12.0 WHERE id = 1"))
    }

    @Test
    fun `UPDATE with leading comment is identified as UPDATE`() {
        assertEquals(StatementType.UPDATE, env.statementType("-- fix price\nUPDATE items SET price = 15.0 WHERE id = 2"))
    }

    @Test
    fun `DELETE is identified as DELETE`() {
        assertEquals(StatementType.DELETE, env.statementType("DELETE FROM items WHERE id = 1"))
    }

    @Test
    fun `DELETE with leading comment is identified as DELETE`() {
        assertEquals(StatementType.DELETE, env.statementType("-- cleanup\nDELETE FROM items WHERE price < 5"))
    }

    @Test
    fun `invalid SQL returns UNKNOWN`() {
        assertEquals(StatementType.UNKNOWN, env.statementType("NOT VALID SQL"))
    }

    @Test
    fun `empty string returns UNKNOWN`() {
        assertEquals(StatementType.UNKNOWN, env.statementType(""))
    }
}
