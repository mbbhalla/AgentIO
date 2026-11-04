package io.github.mbbhalla.agentio.module.text2sql.authz

import io.github.mbbhalla.agentio.data.env.DuckDbDatabaseEnvironment
import io.github.mbbhalla.agentio.data.env.SelectSqlStatement
import io.github.mbbhalla.agentio.data.model.ColumnName
import io.github.mbbhalla.agentio.data.model.DataValue
import io.github.mbbhalla.agentio.data.model.TableName
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import kotlin.io.path.createTempFile
import kotlin.io.path.deleteIfExists
import kotlin.io.path.writeText
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Executes original and authorized SQL on an in-memory DuckDB. For each query, rows(authorized) must
 * equal rows(the same query with the policy applied by hand to its base tables) and be a subset of
 * rows(original).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SqlAuthorizerTest {
    private lateinit var env: DuckDbDatabaseEnvironment

    private val txOrCa =
        Criteria.or(ColumnName("site") eq DataValue.StringValue("TX"), ColumnName("site") eq DataValue.StringValue("CA"))
    private val context =
        AuthorizationContext(
            mapOf(
                TableName("inventory") to txOrCa,
                TableName("product") to (ColumnName("category") eq DataValue.StringValue("grocery")),
                TableName("site") to Criteria.AllRows,
                TableName("emp") to (ColumnName("site") eq DataValue.StringValue("TX")),
            ),
        )

    /** `inventory` with [txOrCa] applied by hand. */
    private val filteredInventory = "(SELECT * FROM inventory WHERE site IN ('TX', 'CA'))"

    @BeforeAll
    fun setUp() {
        env =
            DuckDbDatabaseEnvironment.fromStatements(
                ddl =
                    listOf(
                        "CREATE TABLE site (id VARCHAR, site VARCHAR, region VARCHAR)",
                        "CREATE TABLE product (id VARCHAR, category VARCHAR, name VARCHAR)",
                        "CREATE TABLE inventory (site VARCHAR, product_id VARCHAR, qty BIGINT)",
                        "CREATE TABLE emp (id INTEGER, mgr INTEGER, site VARCHAR)",
                        "CREATE TABLE notes (site VARCHAR, body VARCHAR)",
                        "CREATE TABLE secret (id VARCHAR, site VARCHAR)",
                    ),
                dml =
                    listOf(
                        "INSERT INTO site VALUES ('TX', 'TX', 'south'), ('CA', 'CA', 'west'), ('NY', 'NY', 'east'), ('WA', 'WA', 'west')",
                        "INSERT INTO product VALUES ('p1', 'grocery', 'apple'), ('p2', 'grocery', 'bread'), ('p3', 'toys', 'ball')",
                        "INSERT INTO inventory VALUES ('TX', 'p1', 10), ('TX', 'p3', 5), ('CA', 'p2', 7), ('CA', 'p3', 1), " +
                            "('NY', 'p1', 99), ('NY', 'p2', 98), ('WA', 'p3', 50), ('WA', 'p1', 3)",
                        "INSERT INTO emp VALUES (1, NULL, 'TX'), (2, 1, 'TX'), (3, 1, 'NY'), (4, 2, 'TX'), (5, 3, 'TX')",
                        "INSERT INTO notes VALUES ('TX', 'O''Hare \\ path'), ('TX', 'other')",
                        "INSERT INTO secret VALUES ('s1', 'TX')",
                    ),
                tableMetadata = emptySet(),
            )
    }

    private fun authorize(
        sql: String,
        context: AuthorizationContext = this.context,
    ): AuthorizedSql =
        SqlAuthorizer
            .authorize(statement(sql), context)
            .getOrElse { throw AssertionError("Expected success, got: ${it.message}", it) }

    private fun failure(
        sql: String,
        context: AuthorizationContext = this.context,
    ): String {
        val result = SqlAuthorizer.authorize(statement(sql), context)
        assertTrue(result.isFailure, "Expected failure but got: ${result.getOrNull()?.sql?.value}")
        return assertIs<IllegalArgumentException>(result.exceptionOrNull()).message.orEmpty()
    }

    private fun statement(sql: String): SelectSqlStatement = env.selectStatement(sql).getOrThrow()

    private fun rows(sql: String): List<String> =
        env
            .executeQuery(statement(sql))
            .records
            .map { it.toString() }
            .sorted()

    private fun assertAuthorizedMatches(
        sql: String,
        expectedSql: String,
        context: AuthorizationContext = this.context,
        subsetOfOriginal: Boolean = true,
    ) {
        val authorized = authorize(sql, context).sql.value
        val actual = rows(authorized)
        assertEquals(rows(expectedSql), actual, authorized)
        if (subsetOfOriginal) {
            val original = rows(sql).groupingBy { it }.eachCount()
            val filtered = actual.groupingBy { it }.eachCount()
            assertTrue(filtered.all { (row, count) -> count <= (original[row] ?: 0) }, "authorized rows must be a subset")
        }
    }

    @Test
    fun `simple select is row filtered`() {
        assertAuthorizedMatches("SELECT * FROM Inventory", "SELECT * FROM $filteredInventory")
        assertEquals(setOf(TableName("inventory")), authorize("SELECT * FROM Inventory").filteredTables)
    }

    @Test
    fun `join filters each operand before joining`() {
        assertAuthorizedMatches(
            sql = "SELECT i.site, p.name, i.qty FROM inventory i JOIN product p ON p.id = i.product_id JOIN site s ON s.id = i.site",
            expectedSql =
                "SELECT i.site, p.name, i.qty FROM $filteredInventory i " +
                    "JOIN (SELECT * FROM product WHERE category = 'grocery') p ON p.id = i.product_id JOIN site s ON s.id = i.site",
        )
    }

    @Test
    fun `aggregation sees only authorized rows`() {
        assertAuthorizedMatches(
            sql = "SELECT site, SUM(qty) AS total FROM inventory GROUP BY site",
            expectedSql = "SELECT site, SUM(qty) AS total FROM $filteredInventory GROUP BY site",
        )
    }

    @Test
    fun `nested ctes derived tables and unions are filtered everywhere`() {
        val sql =
            """
            WITH tx AS (
                WITH raw AS (SELECT * FROM inventory) SELECT site, qty FROM raw WHERE site = 'TX'
            ),
            everything AS (SELECT site, qty FROM inventory)
            SELECT site, qty FROM tx
            UNION ALL
            SELECT site, qty FROM (SELECT * FROM everything WHERE qty > 2) big
            UNION ALL
            SELECT site, qty FROM inventory WHERE product_id IN (SELECT id FROM product WHERE name <> 'none')
            """.trimIndent()
        val expected =
            sql
                .replace("FROM inventory", "FROM $filteredInventory inventory")
                .replace("FROM product", "FROM (SELECT * FROM product WHERE category = 'grocery') product")
        assertAuthorizedMatches(sql, expected)
    }

    @Test
    fun `left join preserves outer rows but hides unauthorized matches`() {
        assertAuthorizedMatches(
            sql = "SELECT s.id, i.qty FROM site s LEFT JOIN inventory i ON i.site = s.id",
            expectedSql = "SELECT s.id, i.qty FROM site s LEFT JOIN $filteredInventory i ON i.site = s.id",
            // Outer joins legitimately emit (site, NULL) for sites whose inventory is hidden — not a subset.
            subsetOfOriginal = false,
        )
    }

    @Test
    fun `no rows policy yields an empty result without failing`() {
        val denyAll = AuthorizationContext(mapOf(TableName("inventory") to Criteria.NoRows))
        val records = env.executeQuery(authorize("SELECT COUNT(*) AS n FROM inventory", denyAll).sql).records
        assertEquals(listOf<DataValue?>(DataValue.LongValue(0)), records.map { it[ColumnName("n")] })
    }

    @Test
    fun `string literals with quotes and backslashes survive the round trip`() {
        val body = DataValue.StringValue("O'Hare \\ path")
        val ctx = AuthorizationContext(mapOf(TableName("notes") to (ColumnName("body") eq body)))
        val records = env.executeQuery(authorize("SELECT body FROM notes", ctx).sql).records
        assertEquals(listOf<DataValue?>(body), records.map { it[ColumnName("body")] })
    }

    @Test
    fun `references to the same table share one cte`() {
        val sql = "SELECT a.site FROM inventory a JOIN inventory b ON a.site = b.site UNION SELECT site FROM inventory"
        val authorized = authorize(sql).sql.value
        assertEquals(1, Regex("authz_inventory AS \\(").findAll(authorized).count(), authorized)
        assertAuthorizedMatches(
            sql,
            "SELECT a.site FROM $filteredInventory a JOIN $filteredInventory b ON a.site = b.site UNION SELECT site FROM $filteredInventory",
        )
    }

    @Test
    fun `existing with clause keeps resolving after the authz ctes are added`() {
        assertAuthorizedMatches(
            sql = "WITH x AS (SELECT * FROM inventory) SELECT x.site, x.qty, s.region FROM x JOIN site s ON s.id = x.site",
            expectedSql = "WITH x AS (SELECT * FROM $filteredInventory) SELECT x.site, x.qty, s.region FROM x JOIN site s ON s.id = x.site",
        )
    }

    @Test
    fun `inner cte cannot shadow a base table in an outer scope`() {
        // `inventory` as a CTE is only visible inside the derived table; the outer reference is the base table.
        val inner = "(WITH inventory AS (SELECT 'XX' AS site) SELECT * FROM inventory) d"
        assertAuthorizedMatches(
            sql = "SELECT i.site, i.qty FROM $inner JOIN inventory i ON true",
            expectedSql = "SELECT i.site, i.qty FROM $inner JOIN $filteredInventory i ON true",
        )
    }

    @Test
    fun `cte is not visible to earlier siblings so a same-named base table there is filtered`() {
        assertAuthorizedMatches(
            sql = "WITH a AS (SELECT * FROM inventory), inventory AS (SELECT 'XX' AS site) SELECT * FROM a",
            expectedSql = "SELECT * FROM $filteredInventory",
        )
    }

    @Test
    fun `recursive cte sees itself only in its recursive term`() {
        val filteredEmp = "(SELECT * FROM emp WHERE site = 'TX')"
        assertAuthorizedMatches(
            sql =
                "WITH RECURSIVE tree AS (SELECT id, mgr FROM emp WHERE mgr IS NULL " +
                    "UNION ALL SELECT e.id, e.mgr FROM emp e JOIN tree ON tree.id = e.mgr) SELECT * FROM tree",
            expectedSql =
                "WITH RECURSIVE tree AS (SELECT id, mgr FROM $filteredEmp emp WHERE mgr IS NULL " +
                    "UNION ALL SELECT e.id, e.mgr FROM $filteredEmp e JOIN tree ON tree.id = e.mgr) SELECT * FROM tree",
        )
        // In the anchor DuckDB resolves the CTE's own name to the base table, so it must be filtered.
        assertAuthorizedMatches(
            sql =
                "WITH RECURSIVE inventory AS (SELECT * FROM inventory UNION ALL SELECT * FROM inventory WHERE false) " +
                    "SELECT * FROM inventory",
            expectedSql = "SELECT * FROM $filteredInventory",
        )
    }

    @Test
    fun `subqueries in the select list and where clause are rewritten`() {
        val sql =
            "SELECT (SELECT MAX(qty) FROM inventory) AS m, id FROM site " +
                "WHERE id IN (SELECT site FROM inventory) AND EXISTS (SELECT 1 FROM product WHERE name = 'apple')"
        assertEquals(setOf(TableName("inventory"), TableName("site"), TableName("product")), authorize(sql).filteredTables)
        assertAuthorizedMatches(
            sql,
            "SELECT (SELECT MAX(qty) FROM $filteredInventory) AS m, id FROM site WHERE id IN (SELECT site FROM $filteredInventory) " +
                "AND EXISTS (SELECT 1 FROM (SELECT * FROM product WHERE category = 'grocery') product WHERE name = 'apple')",
            // MAX(qty) over fewer rows is a different value, so these rows need not appear in the original.
            subsetOfOriginal = false,
        )
    }

    @Test
    fun `cte names never collide with names already in the query`() {
        val sql = "WITH authz_inventory AS (SELECT 'XX' AS site) SELECT i.site, i.qty FROM authz_inventory a JOIN inventory i ON true"
        assertContains(authorize(sql).sql.value, "authz_inventory_2")
        assertAuthorizedMatches(sql, "SELECT site, qty FROM $filteredInventory")
    }

    @Test
    fun `quoted mixed-case and whitespace-split identifiers resolve to the same policy`() {
        listOf(
            "SELECT i.site, i.qty FROM\n\"main\"\n\n.\n\n\"Inventory\" AS i",
            "SELECT Inventory.site, Inventory.qty FROM MAIN   .\n\nInventory",
            "SELECT site, qty FROM memory.main.INVENTORY",
        ).forEach { sql ->
            assertEquals(setOf(TableName("inventory")), authorize(sql).filteredTables, sql)
            assertAuthorizedMatches(sql, "SELECT site, qty FROM $filteredInventory")
        }
    }

    @Test
    fun `allow-all-rows policy leaves unlisted tables untouched but reports them`() {
        val ctx = AuthorizationContext(mapOf(TableName("inventory") to txOrCa), UnlistedTablePolicy.ALLOW_ALL_ROWS)
        val sql = "SELECT i.site, s.region FROM inventory i JOIN site s ON s.id = i.site"
        val authorized = authorize(sql, ctx)
        assertEquals(setOf(TableName("inventory")), authorized.filteredTables)
        assertEquals(setOf(TableName("site")), authorized.unfilteredTables)
        assertAuthorizedMatches(sql, "SELECT i.site, s.region FROM $filteredInventory i JOIN site s ON s.id = i.site", ctx)
    }

    // ------------------------------------------------------------------ error paths

    @Test
    fun `unlisted table is denied by default`() {
        assertContains(failure("SELECT * FROM inventory JOIN secret ON inventory.site = secret.site"), "'secret' is not authorized")
    }

    @Test
    fun `table functions and SUMMARIZE are rejected because they read data without naming a table`() {
        listOf(
            "SELECT * FROM query_table('inventory')",
            "SELECT * FROM query('SELECT * FROM inventory')",
            "SELECT * FROM range(3)",
            "SELECT * FROM (SUMMARIZE inventory)",
        ).forEach { sql -> assertContains(failure(sql), "is not permitted", message = sql) }
    }

    @Test
    fun `files are rejected even when unlisted tables are allowed`() {
        val csv = createTempFile(suffix = ".csv")
        try {
            csv.writeText("site,qty\nTX,1\n")
            val allowAll = AuthorizationContext(emptyMap(), UnlistedTablePolicy.ALLOW_ALL_ROWS)
            assertContains(failure("SELECT * FROM '$csv'", allowAll), "Invalid table name")
            assertContains(failure("SELECT * FROM read_csv('$csv')", allowAll), "is not permitted")
        } finally {
            csv.deleteIfExists()
        }
    }

    @Test
    fun `a policy on a column the table lacks fails authorization`() {
        val ctx = AuthorizationContext(mapOf(TableName("inventory") to (ColumnName("no_such_column") eq DataValue.LongValue(1))))
        assertContains(failure("SELECT * FROM inventory", ctx), "no_such_column")
    }

    @Test
    fun `authorization runs on the environment that validated the statement, not the newest one`() {
        val statement = statement("SELECT * FROM Inventory")
        DuckDbDatabaseEnvironment.fromStatements(
            ddl = listOf("CREATE TABLE unrelated (id INTEGER)"),
            dml = emptyList(),
            tableMetadata = emptySet(),
        )

        val authorized = SqlAuthorizer.authorize(statement, context).getOrThrow()

        assertSame(env, authorized.sql.environment)
        assertEquals(rows("SELECT * FROM $filteredInventory"), rows(authorized.sql.value))
    }
}
