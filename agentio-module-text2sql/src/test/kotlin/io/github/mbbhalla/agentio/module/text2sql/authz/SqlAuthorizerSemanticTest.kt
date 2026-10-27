package io.github.mbbhalla.agentio.module.text2sql.authz

import io.github.mbbhalla.agentio.data.model.ColumnName
import io.github.mbbhalla.agentio.data.model.DataValue
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.sql.Connection
import java.sql.DriverManager
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Semantic guarantee: for each query, rows(authorized) == rows(query with the policy manually
 * applied to the base tables), and rows(authorized) ⊆ rows(original). Executed on in-memory DuckDB.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SqlAuthorizerSemanticTest {
    private lateinit var connection: Connection
    private val authorizer = SqlAuthorizer()

    private val txOrCa =
        Criteria.or(ColumnName("site") eq DataValue.StringValue("TX"), ColumnName("site") eq DataValue.StringValue("CA"))
    private val groceryOnly = ColumnName("category") eq DataValue.StringValue("grocery")

    private val context =
        AuthorizationContext(
            DuckDbSqlDialect,
            mapOf(
                table("inventory") to txOrCa,
                table("product") to groceryOnly,
                table("site") to Criteria.AllRows,
            ),
            defaultSchema = "supply",
        )

    private fun table(name: String) = DuckDbSqlDialect.tableReference(name = name, schema = "supply")

    @BeforeAll
    fun setUp() {
        connection = DriverManager.getConnection("jdbc:duckdb:")
        connection.createStatement().use { st ->
            st.execute("CREATE SCHEMA supply")
            // Mirrors a Redshift search_path so unqualified names resolve the same way the context's defaultSchema does.
            st.execute("SET search_path = 'supply'")
            st.execute("CREATE TABLE supply.site (id VARCHAR, site VARCHAR, region VARCHAR)")
            st.execute("CREATE TABLE supply.product (id VARCHAR, category VARCHAR, name VARCHAR)")
            st.execute("CREATE TABLE supply.inventory (site VARCHAR, product_id VARCHAR, qty BIGINT)")
            st.execute(
                "INSERT INTO supply.site VALUES ('TX','TX','south'), ('CA','CA','west'), ('NY','NY','east'), ('WA','WA','west')",
            )
            st.execute(
                "INSERT INTO supply.product VALUES ('p1','grocery','apple'), ('p2','grocery','bread'), ('p3','toys','ball')",
            )
            st.execute(
                """
                INSERT INTO supply.inventory VALUES
                  ('TX','p1',10), ('TX','p3',5), ('CA','p2',7), ('CA','p3',1),
                  ('NY','p1',99), ('NY','p2',98), ('WA','p3',50), ('WA','p1',3)
                """.trimIndent(),
            )
        }
    }

    @AfterAll
    fun tearDown() {
        connection.close()
    }

    private fun rows(sql: String): List<List<Any?>> =
        connection.createStatement().use { st ->
            st.executeQuery(sql).use { rs ->
                val n = rs.metaData.columnCount
                generateSequence { if (rs.next()) (1..n).map { rs.getObject(it) } else null }.toList()
            }
        }

    /**
     * Runs [sql] through the authorizer, then asserts its rows equal those of [expectedSql] (the
     * same query with filters applied by hand) and are a sub-multiset of the original's rows.
     */
    private fun assertAuthorizedMatches(
        sql: String,
        expectedSql: String,
        subsetOfOriginal: Boolean = true,
    ) {
        val authorized = authorizer.authorize(sql, context).getOrThrow()
        val actual = rows(authorized.sql)
        assertEquals(rows(expectedSql).sortedBy { it.toString() }, actual.sortedBy { it.toString() }, authorized.sql)

        if (subsetOfOriginal) {
            val original = rows(sql).groupingBy { it }.eachCount()
            val filtered = actual.groupingBy { it }.eachCount()
            assertTrue(filtered.all { (row, count) -> count <= (original[row] ?: 0) }, "authorized rows must be a subset")
        }
    }

    @Test
    fun `simple select is row filtered`() {
        assertAuthorizedMatches(
            sql = "SELECT * FROM Inventory",
            expectedSql = "SELECT * FROM supply.inventory WHERE site IN ('TX','CA')",
        )
    }

    @Test
    fun `join filters each operand before joining`() {
        assertAuthorizedMatches(
            sql =
                """
                SELECT i.site, p.name, i.qty
                FROM supply.inventory i JOIN supply.product p ON p.id = i.product_id JOIN supply.site s ON s.id = i.site
                """.trimIndent(),
            expectedSql =
                """
                SELECT i.site, p.name, i.qty
                FROM (SELECT * FROM supply.inventory WHERE site IN ('TX','CA')) i
                JOIN (SELECT * FROM supply.product WHERE category = 'grocery') p ON p.id = i.product_id
                JOIN supply.site s ON s.id = i.site
                """.trimIndent(),
        )
    }

    @Test
    fun `aggregation sees only authorized rows`() {
        assertAuthorizedMatches(
            sql = "SELECT site, SUM(qty) total FROM inventory GROUP BY site ORDER BY site",
            expectedSql = "SELECT site, SUM(qty) total FROM supply.inventory WHERE site IN ('TX','CA') GROUP BY site ORDER BY site",
        )
    }

    @Test
    fun `nested ctes derived tables and unions are filtered everywhere`() {
        val sql =
            """
            WITH tx AS (
                WITH raw AS (SELECT * FROM supply.inventory) SELECT site, qty FROM raw WHERE site = 'TX'
            ),
            everything AS (SELECT site, qty FROM supply.inventory)
            SELECT site, qty FROM tx
            UNION ALL
            SELECT site, qty FROM (SELECT * FROM everything WHERE qty > 2) big
            UNION ALL
            SELECT site, qty FROM supply.inventory WHERE product_id IN (SELECT id FROM supply.product WHERE name <> 'none')
            """.trimIndent()
        val expected =
            sql
                .replace("FROM supply.inventory", "FROM (SELECT * FROM supply.inventory WHERE site IN ('TX','CA')) inventory")
                .replace("FROM supply.product", "FROM (SELECT * FROM supply.product WHERE category = 'grocery') product")
        assertAuthorizedMatches(sql, expected)
    }

    @Test
    fun `left join preserves outer rows but hides unauthorized matches`() {
        assertAuthorizedMatches(
            sql =
                """
                SELECT s.id, i.qty FROM supply.site s LEFT JOIN supply.inventory i ON i.site = s.id ORDER BY s.id, i.qty
                """.trimIndent(),
            expectedSql =
                """
                SELECT s.id, i.qty FROM supply.site s
                LEFT JOIN (SELECT * FROM supply.inventory WHERE site IN ('TX','CA')) i ON i.site = s.id ORDER BY s.id, i.qty
                """.trimIndent(),
            // Outer joins legitimately emit (site, NULL) for sites whose inventory is hidden — not a subset.
            subsetOfOriginal = false,
        )
    }

    @Test
    fun `no rows policy yields an empty result without failing`() {
        val denyAll = AuthorizationContext(DuckDbSqlDialect, mapOf(table("inventory") to Criteria.NoRows))
        val authorized = authorizer.authorize("SELECT COUNT(*) FROM supply.inventory", denyAll).getOrThrow()
        assertEquals(listOf(listOf<Any?>(0L)), rows(authorized.sql))
    }

    @Test
    fun `string literals with quotes and backslashes survive the round trip`() {
        connection.createStatement().use {
            it.execute("CREATE TABLE supply.notes (site VARCHAR, body VARCHAR)")
            it.execute("""INSERT INTO supply.notes VALUES ('TX', 'O''Hare \ path'), ('TX', 'other')""")
        }
        val ctx =
            AuthorizationContext(
                DuckDbSqlDialect,
                mapOf(table("notes") to (ColumnName("body") eq DataValue.StringValue("O'Hare \\ path"))),
            )
        val authorized = authorizer.authorize("SELECT body FROM supply.notes", ctx).getOrThrow()
        assertEquals(listOf(listOf<Any?>("O'Hare \\ path")), rows(authorized.sql))
    }
}
