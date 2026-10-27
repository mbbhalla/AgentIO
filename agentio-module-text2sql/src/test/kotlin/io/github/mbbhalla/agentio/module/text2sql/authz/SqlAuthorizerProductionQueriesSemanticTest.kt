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
 * Executes the two production-shaped Redshift queries (ported from the original ANTLR suite) on an
 * in-memory DuckDB populated with a small supply-chain dataset, and checks that the authorized SQL
 * returns exactly the rows of the same query with the policy applied by hand to each base table.
 *
 * DuckDB lacks Redshift's `DATEADD`; a macro shim with the same signature is defined for the test.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SqlAuthorizerProductionQueriesSemanticTest {
    private lateinit var connection: Connection
    private val authorizer = SqlAuthorizer()

    private val eastOnly = ColumnName("geo_id") eq DataValue.StringValue("GEO-1")
    private val noGreenOlives = ColumnName("id") notEq DataValue.StringValue("OLIVE-GR")
    private val onlyOliveProducts = ColumnName("product_id") like DataValue.StringValue("OLIVE-%")

    private val context =
        AuthorizationContext(
            DuckDbSqlDialect,
            mapOf(
                table("supply", "geography") to Criteria.AllRows,
                table("supply", "site") to eastOnly,
                table("supply", "producthierarchy") to Criteria.AllRows,
                table("supply", "product") to noGreenOlives,
                table("analytics", "projectedinventory") to
                    Criteria.and(onlyOliveProducts, ColumnName("site_id") notEq DataValue.StringValue("CH-001")),
            ),
        )

    private fun table(
        schema: String,
        name: String,
    ) = DuckDbSqlDialect.tableReference(name = name, schema = schema)

    /** Hand-applied equivalents of [context] — the oracle the authorizer is checked against. */
    private val manualFilters =
        mapOf(
            "supply.Site" to "(SELECT * FROM supply.Site WHERE geo_id = 'GEO-1')",
            "supply.Product" to "(SELECT * FROM supply.Product WHERE id <> 'OLIVE-GR')",
            "analytics.ProjectedInventory" to
                "(SELECT * FROM analytics.ProjectedInventory WHERE product_id LIKE 'OLIVE-%' AND site_id <> 'CH-001')",
        )

    @BeforeAll
    fun setUp() {
        connection = DriverManager.getConnection("jdbc:duckdb:")
        connection.createStatement().use { st ->
            checkNotNull(javaClass.getResource("/authz/supply_chain_fixture.sql"))
                .readText()
                .lineSequence()
                .filterNot { it.trimStart().startsWith("--") }
                .joinToString("\n")
                .split(";")
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .forEach { st.execute(it) }
        }
    }

    @AfterAll
    fun tearDown() {
        connection.close()
    }

    private fun resource(name: String): String = checkNotNull(javaClass.getResource("/authz/$name")).readText()

    private fun rows(sql: String): List<List<Any?>> =
        connection.createStatement().use { st ->
            st.executeQuery(sql).use { rs ->
                val n = rs.metaData.columnCount
                generateSequence { if (rs.next()) (1..n).map { rs.getObject(it) } else null }.toList()
            }
        }

    /**
     * Builds the oracle query by substituting every base-table reference (any quoting / casing) with
     * its manually filtered derived table, keeping the original alias or installing the table name.
     */
    private fun manuallyFiltered(sql: String): String {
        var out = sql
        for ((table, replacement) in manualFilters) {
            val (schema, name) = table.split(".")
            val pattern = Regex("""(?<!\w)"?$schema"?\s*\.\s*"?$name"?(?!\w)(\s+AS\s+\w+)?""", RegexOption.IGNORE_CASE)
            out = out.replace(pattern) { m -> replacement + (m.groups[1]?.value ?: " AS $name") }
        }
        return out
    }

    private fun assertAuthorizedMatchesOracle(resourceName: String) {
        val sql = resource(resourceName)
        val authorized = authorizer.authorize(sql, context).getOrThrow()

        val expected = rows(manuallyFiltered(sql))
        val actual = rows(authorized.sql)
        val original = rows(sql)

        assertTrue(original.size > actual.size, "fixture must make the policy observable")
        assertTrue(actual.isNotEmpty(), "fixture must leave authorized rows")
        assertEquals(expected.sortedBy { it.toString() }, actual.sortedBy { it.toString() }, authorized.sql)
    }

    @Test
    fun `site summary aggregates query returns exactly the policy-filtered rows`() {
        assertAuthorizedMatchesOracle("site_summary_aggregates.sql")
    }

    @Test
    fun `projected inventory for sites and products query returns exactly the policy-filtered rows`() {
        assertAuthorizedMatchesOracle("projected_inventory_for_sites_and_products.sql")
    }

    @Test
    fun `no-rows policy on a leaf table empties the whole production query`() {
        val denyInventory =
            AuthorizationContext(
                DuckDbSqlDialect,
                context.policies + (table("analytics", "projectedinventory") to Criteria.NoRows),
            )
        val authorized = authorizer.authorize(resource("site_summary_aggregates.sql"), denyInventory).getOrThrow()
        assertEquals(emptyList(), rows(authorized.sql))
    }
}
