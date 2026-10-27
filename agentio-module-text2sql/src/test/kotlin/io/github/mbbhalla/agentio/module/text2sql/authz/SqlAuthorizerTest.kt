package io.github.mbbhalla.agentio.module.text2sql.authz

import io.github.mbbhalla.agentio.data.model.ColumnName
import io.github.mbbhalla.agentio.data.model.DataValue
import net.sf.jsqlparser.parser.CCJSqlParserUtil
import net.sf.jsqlparser.statement.Statement
import net.sf.jsqlparser.statement.select.Select
import net.sf.jsqlparser.util.TablesNamesFinder
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SqlAuthorizerTest {
    private val authorizer = SqlAuthorizer()

    private val siteFilter =
        Criteria.or(ColumnName("site") eq DataValue.StringValue("TX"), ColumnName("site") eq DataValue.StringValue("CA"))

    private fun ref(
        name: String,
        schema: String? = null,
    ) = RedshiftSqlDialect.tableReference(name = name, schema = schema)

    private fun authorize(
        sql: String,
        context: AuthorizationContext,
    ): AuthorizedSql {
        val result = authorizer.authorize(sql, context)
        val authorized = result.getOrElse { throw AssertionError("Expected success, got: ${it.message}", it) }
        CCJSqlParserUtil.parse(authorized.sql) // syntactic guarantee
        return authorized
    }

    private fun failure(
        sql: String,
        context: AuthorizationContext,
    ): SqlAuthorizationException {
        val result = authorizer.authorize(sql, context)
        assertTrue(result.isFailure, "Expected failure but got: ${result.getOrNull()?.sql}")
        return assertIs<SqlAuthorizationException>(result.exceptionOrNull())
    }

    private fun withItemNames(sql: String): List<String> = (CCJSqlParserUtil.parse(sql) as Select).withItemsList.map { it.aliasName }

    /** Base tables that remain *outside* any authz CTE in the output. */
    private fun baseTablesOutsideCtes(sql: String): Set<String> {
        val select = CCJSqlParserUtil.parse(sql) as Select
        val cteBodies = select.withItemsList.orEmpty().filter { it.aliasName.startsWith("authz_") }
        val tablesInsideCtes =
            cteBodies.flatMap { TablesNamesFinder<Void?>().getTables(it.select as Statement) }.toSet()
        val all = TablesNamesFinder<Void?>().getTables(select as Statement)
        return all - tablesInsideCtes
    }

    @Test
    fun `wraps a simple select in a filtering cte`() {
        val ctx = AuthorizationContext(RedshiftSqlDialect, mapOf(ref("inventory") to siteFilter))
        val out = authorize("SELECT * FROM Inventory", ctx)

        assertEquals(
            "WITH authz_inventory AS (SELECT * FROM Inventory WHERE ((\"site\" = 'TX') OR (\"site\" = 'CA'))) " +
                "SELECT * FROM authz_inventory AS Inventory",
            out.sql,
        )
        assertEquals(setOf(ref("inventory")), out.filteredTables)
        assertTrue(baseTablesOutsideCtes(out.sql).isEmpty())
    }

    @Test
    fun `references to the same table share one cte`() {
        val ctx = AuthorizationContext(RedshiftSqlDialect, mapOf(ref("t") to siteFilter))
        val out = authorize("SELECT a.x FROM t a JOIN t b ON a.id = b.parent_id UNION SELECT x FROM t", ctx)

        assertEquals(1, withItemNames(out.sql).size)
        assertEquals(3, Regex("authz_t").findAll(out.sql).count() - 1) // 3 uses + 1 declaration
        assertTrue(baseTablesOutsideCtes(out.sql).isEmpty())
    }

    @Test
    fun `existing with clause is kept after the authz ctes and its names stay resolvable`() {
        val ctx = AuthorizationContext(RedshiftSqlDialect, mapOf(ref("site", "supply") to siteFilter))
        val sql = "WITH X AS (SELECT * FROM supply.Site) SELECT * FROM X JOIN supply.Site s ON s.id = X.id"
        val out = authorize(sql, ctx)

        assertEquals(listOf("authz_supply_site", "X"), withItemNames(out.sql))
        assertTrue(baseTablesOutsideCtes(out.sql).isEmpty())
    }

    @Test
    fun `inner cte cannot shadow a base table in an outer scope`() {
        // `site` as a CTE is only visible inside the derived table; the outer FROM site is a base table.
        val ctx = AuthorizationContext(RedshiftSqlDialect, mapOf(ref("site") to siteFilter))
        val sql = "SELECT * FROM (WITH site AS (SELECT 1 AS id) SELECT * FROM site) d JOIN site ON site.id = d.id"
        val out = authorize(sql, ctx)

        assertContains(out.sql, "JOIN authz_site AS site ON")
        assertContains(out.sql, "WITH site AS (SELECT 1 AS id) SELECT * FROM site)") // inner untouched
        assertTrue(baseTablesOutsideCtes(out.sql).isEmpty())
    }

    @Test
    fun `cte is not visible to earlier siblings so a same-named base table there is filtered`() {
        val ctx = AuthorizationContext(RedshiftSqlDialect, mapOf(ref("site") to siteFilter))
        val sql = "WITH a AS (SELECT * FROM site), site AS (SELECT 1 AS id) SELECT * FROM a"
        val out = authorize(sql, ctx)

        assertContains(out.sql, "a AS (SELECT * FROM authz_site AS site)")
        assertTrue(baseTablesOutsideCtes(out.sql).isEmpty())
    }

    @Test
    fun `recursive cte keeps RECURSIVE on the first with item and self reference unfiltered`() {
        val ctx = AuthorizationContext(RedshiftSqlDialect, mapOf(ref("emp") to siteFilter))
        val sql =
            "WITH RECURSIVE tree(id, mgr) AS (SELECT id, mgr FROM emp WHERE mgr IS NULL " +
                "UNION ALL SELECT e.id, e.mgr FROM emp e JOIN tree ON tree.id = e.mgr) SELECT * FROM tree"
        val out = authorize(sql, ctx)

        assertTrue(out.sql.startsWith("WITH RECURSIVE authz_emp AS ("), out.sql)
        assertEquals(1, Regex("RECURSIVE").findAll(out.sql).count())
        assertContains(out.sql, "JOIN tree ON")
        assertTrue(baseTablesOutsideCtes(out.sql).isEmpty())
    }

    @Test
    fun `subqueries in where and select list are rewritten`() {
        val ctx =
            AuthorizationContext(RedshiftSqlDialect, mapOf(ref("a") to siteFilter, ref("b") to Criteria.NoRows, ref("c") to siteFilter))
        val sql = "SELECT (SELECT MAX(x) FROM c) m FROM a WHERE id IN (SELECT id FROM b) AND EXISTS (SELECT 1 FROM b)"
        val out = authorize(sql, ctx)

        assertEquals(3, out.filteredTables.size)
        assertContains(out.sql, "authz_b AS (SELECT * FROM b WHERE (1 = 0))")
        assertTrue(baseTablesOutsideCtes(out.sql).isEmpty())
    }

    @Test
    fun `default schema resolves unqualified references to schema-level policies`() {
        val ctx = AuthorizationContext(RedshiftSqlDialect, mapOf(ref("site", "supply") to siteFilter), defaultSchema = "SUPPLY")
        val out = authorize("SELECT * FROM site", ctx)
        assertEquals(setOf(ref("site", "supply")), out.filteredTables)
    }

    @Test
    fun `cte names never collide with identifiers already present`() {
        val ctx = AuthorizationContext(RedshiftSqlDialect, mapOf(ref("t") to siteFilter))
        val out = authorize("WITH authz_t AS (SELECT 1 AS id) SELECT * FROM authz_t JOIN t ON t.id = authz_t.id", ctx)

        assertContains(out.sql, "WITH authz_t_2 AS (SELECT * FROM t WHERE")
        assertContains(out.sql, "JOIN authz_t_2 AS t ON")
        assertTrue(baseTablesOutsideCtes(out.sql).isEmpty())
    }

    @Test
    fun `allow-all-rows policy leaves unlisted tables untouched but reports them`() {
        val ctx =
            AuthorizationContext(
                RedshiftSqlDialect,
                mapOf(ref("a") to siteFilter),
                unlistedTablePolicy = UnlistedTablePolicy.ALLOW_ALL_ROWS,
            )
        val out = authorize("SELECT * FROM a JOIN b ON a.id = b.id", ctx)

        assertEquals(setOf(ref("a")), out.filteredTables)
        assertEquals(setOf(ref("b")), out.unfilteredTables)
        assertEquals(setOf("b"), baseTablesOutsideCtes(out.sql))
    }

    /**
     * Ports of the "Test Redshift table replace - 1..5" cases from the original ANTLR-based
     * implementation. Policies mirror the original geography / product-hierarchy IN / NOT IN
     * criteria, expressed per table.
     */
    @Nested
    inner class TableReplaceCases {
        private val geographyIn =
            ColumnName("geo_id") isIn
                listOf(
                    DataValue.StringValue("GEOGRAPHY-1"),
                    DataValue.StringValue("GEOGRAPHY-2"),
                    DataValue.StringValue("GEOGRAPHY-3"),
                )
        private val productHierarchyIn =
            ColumnName("product_group_id") isIn
                listOf(DataValue.StringValue("PRODUCT_HIERARCHY-1"), DataValue.StringValue("PRODUCT_HIERARCHY-2"))
        private val productHierarchyNotIn =
            ColumnName("product_group_id") notIn
                listOf(DataValue.StringValue("PRODUCT_HIERARCHY-1"), DataValue.StringValue("PRODUCT_HIERARCHY-2"))

        private val supplyChainContext =
            AuthorizationContext(
                RedshiftSqlDialect,
                mapOf(
                    ref("inventorylevel", "supply") to Criteria.and(geographyIn, productHierarchyIn),
                    ref("site", "supply") to geographyIn,
                    ref("product", "supply") to productHierarchyNotIn,
                    ref("geography", "supply") to (ColumnName("id") isIn listOf(DataValue.StringValue("GEOGRAPHY-1"))),
                    ref("producthierarchy", "supply") to (ColumnName("id") notIn listOf(DataValue.StringValue("PRODUCT_HIERARCHY-1"))),
                    ref("projectedinventory", "analytics") to Criteria.and(geographyIn, productHierarchyNotIn),
                ),
            )

        private fun resource(name: String): String = checkNotNull(javaClass.getResource("/authz/$name")).readText()

        @Test
        fun `case 1 - quoted identifiers split across whitespace and newlines`() {
            val sql =
                """
                SELECT
                    InventoryLevel.site_id,
                    InventoryLevel.product_id,
                    Site.geo_id AS region_id,
                    Product.product_group_id AS product_category_id,
                    'Site-123' AS some_column
                FROM
                "supply"

                .

                "InventoryLevel" AS InventoryLevel
                JOIN "supply"."Site" AS Site
                    ON Site.id = InventoryLevel.site_id
                JOIN "supply"."Product" AS Product
                    ON Product.id = InventoryLevel.product_id
                WHERE InventoryLevel.site_id IN ('Site-123')
                """.trimIndent()
            val out = authorize(sql, supplyChainContext)

            assertEquals(
                listOf("authz_supply_inventorylevel", "authz_supply_site", "authz_supply_product"),
                withItemNames(out.sql),
            )
            assertContains(out.sql, "FROM authz_supply_inventorylevel AS InventoryLevel JOIN authz_supply_site AS Site ON")
            assertContains(out.sql, "JOIN authz_supply_product AS Product ON")
            assertContains(out.sql, "authz_supply_inventorylevel AS (SELECT * FROM \"supply\".\"InventoryLevel\" WHERE")
            assertContains(out.sql, "\"product_group_id\" IN ('PRODUCT_HIERARCHY-1', 'PRODUCT_HIERARCHY-2')")
            assertContains(out.sql, "\"geo_id\" IN ('GEOGRAPHY-1', 'GEOGRAPHY-2', 'GEOGRAPHY-3')")
            assertEquals(3, out.filteredTables.size)
            assertTrue(baseTablesOutsideCtes(out.sql).isEmpty())
        }

        @Test
        fun `case 2 - unquoted mixed-case identifiers with odd whitespace resolve to the same policies`() {
            val sql =
                """
                SELECT
                    InventoryLevel.site_id,
                    InventoryLevel.product_id,
                    Site.geo_id AS region_id,
                    Product.product_group_id AS product_category_id,
                    'Site-123' AS some_column
                FROM
                SUPPLY   .

                InventoryLevel

                AS InventoryLevel
                JOIN Supply.Site AS Site
                    ON Site.id = InventoryLevel.site_id
                JOIN supply.Product AS Product
                    ON Product.id = InventoryLevel.product_id
                WHERE InventoryLevel.site_id IN ('Site-123')
                """.trimIndent()
            val out = authorize(sql, supplyChainContext)

            assertEquals(
                setOf(ref("inventorylevel", "supply"), ref("site", "supply"), ref("product", "supply")),
                out.filteredTables,
            )
            assertContains(out.sql, "authz_supply_product AS (SELECT * FROM supply.Product WHERE (\"product_group_id\" NOT IN")
            assertTrue(baseTablesOutsideCtes(out.sql).isEmpty())
        }

        @Test
        fun `case 3 - production query with comments, quoted names, bare date units and left-joined ctes`() {
            val out = authorize(resource("projected_inventory_for_sites_and_products.sql"), supplyChainContext)

            assertEquals(
                listOf(
                    "authz_supply_geography",
                    "authz_supply_site",
                    "authz_supply_producthierarchy",
                    "authz_supply_product",
                    "authz_analytics_projectedinventory",
                    "REGIONS_TO_FILTER",
                    "SITES_TO_FILTER",
                    "CATEGORIES_TO_FILTER",
                    "PRODUCTS_TO_FILTER",
                    "BASELINE_RECORD",
                    "WINDOW_RECORD",
                ),
                withItemNames(out.sql),
            )
            // Both ProjectedInventory occurrences share one CTE and keep their PI alias.
            assertEquals(2, Regex("FROM authz_analytics_projectedinventory AS PI WHERE").findAll(out.sql).count())
            assertContains(out.sql, "FROM authz_supply_geography AS REGION)")
            assertContains(out.sql, "FROM authz_supply_producthierarchy AS CATEGORY)")
            // Redshift-isms survive the round trip.
            assertContains(out.sql, "DATEADD(day, 153 + 1, '2024-01-15 00:00:00')")
            assertContains(out.sql, "cast(MIN_SAFETY_STOCK AS float)") // keyword casing preserved
            assertTrue(baseTablesOutsideCtes(out.sql).isEmpty())
        }

        @Test
        fun `case 4 - with clauses nested inside ctes and inside derived tables`() {
            val sql =
                """
                WITH X AS (
                	WITH Y AS (
                		SELECT * FROM supply.Site
                	)
                	SELECT col1, col2 FROM Y
                )

                SELECT * FROM (
                	WITH R AS (SELECT * FROM X)
                	SELECT * FROM R
                )
                """.trimIndent()
            val out = authorize(sql, supplyChainContext)

            assertEquals(listOf("authz_supply_site", "X"), withItemNames(out.sql))
            assertContains(out.sql, "X AS (WITH Y AS (SELECT * FROM authz_supply_site AS Site) SELECT col1, col2 FROM Y)")
            assertContains(out.sql, "SELECT * FROM (WITH R AS (SELECT * FROM X) SELECT * FROM R)")
            assertEquals(setOf(ref("site", "supply")), out.filteredTables)
            assertTrue(baseTablesOutsideCtes(out.sql).isEmpty())
        }

        @Test
        fun `case 5 - production query with unions, nested derived tables, paginated cte and alias-less table`() {
            val out = authorize(resource("site_summary_aggregates.sql"), supplyChainContext)

            assertEquals(
                listOf("authz_supply_geography", "authz_supply_site", "authz_supply_product", "authz_analytics_projectedinventory"),
                withItemNames(out.sql).take(4),
            )
            assertEquals(4, out.filteredTables.size)
            // Two distinct occurrences of analytics.ProjectedInventory: one aliased, one not.
            assertContains(out.sql, "FROM authz_analytics_projectedinventory AS PI)")
            assertContains(
                out.sql,
                "FROM authz_analytics_projectedinventory AS ProjectedInventory WHERE record_type = 'WINDOW'",
            )
            assertEquals(2, Regex("UNION").findAll(out.sql).count())
            assertContains(out.sql, "LIMIT 25 OFFSET 0")
            assertTrue(baseTablesOutsideCtes(out.sql).isEmpty())
        }

        @Test
        fun `case 5 with a partial policy set fails closed on the unlisted table`() {
            val partial =
                AuthorizationContext(
                    RedshiftSqlDialect,
                    mapOf(
                        ref("site", "supply") to geographyIn,
                        ref("geography", "supply") to Criteria.AllRows,
                    ),
                )
            val e = failure(resource("site_summary_aggregates.sql"), partial)
            assertTrue(
                "supply.product" in e.message.orEmpty() || "analytics.projectedinventory" in e.message.orEmpty(),
                e.message,
            )
        }
    }

    // ------------------------------------------------------------------ error paths

    @Test
    fun `unlisted table is denied by default`() {
        val e =
            failure(
                "SELECT * FROM a JOIN secret ON a.id = secret.id",
                AuthorizationContext(
                    RedshiftSqlDialect,
                    mapOf(
                        ref("a") to siteFilter,
                    ),
                ),
            )
        assertContains(e.message.orEmpty(), "secret")
    }

    @Test
    fun `non select statements are rejected`() {
        val ctx = AuthorizationContext(RedshiftSqlDialect, mapOf(ref("t") to siteFilter))
        assertContains(failure("DELETE FROM t", ctx).message.orEmpty(), "Only SELECT")
        assertContains(failure("UPDATE t SET x = 1", ctx).message.orEmpty(), "Only SELECT")
        assertContains(failure("INSERT INTO t SELECT * FROM t", ctx).message.orEmpty(), "Only SELECT")
    }

    @Test
    fun `multiple statements are rejected`() {
        val ctx = AuthorizationContext(RedshiftSqlDialect, mapOf(ref("t") to siteFilter))
        assertContains(failure("SELECT * FROM t; DROP TABLE t", ctx).message.orEmpty(), "Exactly one statement")
    }

    @Test
    fun `select into and data-modifying ctes are rejected`() {
        val ctx =
            AuthorizationContext(
                RedshiftSqlDialect,
                mapOf(ref("t") to siteFilter),
                unlistedTablePolicy = UnlistedTablePolicy.ALLOW_ALL_ROWS,
            )
        assertContains(failure("SELECT * INTO copy FROM t", ctx).message.orEmpty(), "INTO")
        assertContains(
            failure("WITH d AS (DELETE FROM t RETURNING *) SELECT * FROM d", ctx).message.orEmpty(),
            "Data-modifying",
        )
    }

    @Test
    fun `malformed sql and blank sql fail with a parse error`() {
        val ctx = AuthorizationContext(RedshiftSqlDialect, mapOf(ref("t") to siteFilter))
        assertContains(failure("SELECT * FORM t", ctx).message.orEmpty(), "could not be parsed")
        assertContains(failure("   ", ctx).message.orEmpty(), "blank")
    }

    @Test
    fun `validator rejection is surfaced as an authorization failure`() {
        val rejecting = SqlAuthorizer { Result.failure(IllegalStateException("boom")) }
        val result = rejecting.authorize("SELECT * FROM t", AuthorizationContext(RedshiftSqlDialect, mapOf(ref("t") to siteFilter)))
        assertFalse(result.isSuccess)
        assertContains(result.exceptionOrNull()?.message.orEmpty(), "boom")
    }
}
