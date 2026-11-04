package io.github.mbbhalla.agentio.module.text2sql.authz

import io.github.mbbhalla.agentio.data.env.DuckDbDatabaseEnvironment
import io.github.mbbhalla.agentio.data.env.SelectSqlStatement
import io.github.mbbhalla.agentio.data.model.ColumnName
import io.github.mbbhalla.agentio.data.model.DataValue
import io.github.mbbhalla.agentio.data.model.TableName
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Executes two production-shaped queries (ported from the original ANTLR suite; the fixture defines a
 * `dateadd` macro for them) on an in-memory DuckDB populated with a small supply-chain dataset, and
 * checks that the authorized SQL returns exactly the rows of the same query with the policy applied
 * by hand to each base table.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SqlAuthorizerProductionQueriesSemanticTest {
    private lateinit var env: DuckDbDatabaseEnvironment

    private val eastOnly = ColumnName("geo_id") eq DataValue.StringValue("GEO-1")
    private val noGreenOlives = ColumnName("id") notEq DataValue.StringValue("OLIVE-GR")
    private val onlyOliveProducts = ColumnName("product_id") like DataValue.StringValue("OLIVE-%")

    private val context =
        AuthorizationContext(
            mapOf(
                TableName("geography") to Criteria.AllRows,
                TableName("site") to eastOnly,
                TableName("producthierarchy") to Criteria.AllRows,
                TableName("product") to noGreenOlives,
                TableName("projectedinventory") to
                    Criteria.and(onlyOliveProducts, ColumnName("site_id") notEq DataValue.StringValue("CH-001")),
            ),
        )

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
        val fixture =
            resource("supply_chain_fixture.sql")
                .lineSequence()
                .filterNot { it.trimStart().startsWith("--") }
                .joinToString("\n")
                .split(";")
                .map { it.trim() }
                .filter { it.isNotEmpty() }
        env = DuckDbDatabaseEnvironment.fromStatements(ddl = fixture, dml = emptyList(), tableMetadata = emptySet())
    }

    private fun resource(name: String): String = checkNotNull(javaClass.getResource("/authz/$name")).readText()

    private fun statement(sql: String): SelectSqlStatement = env.selectStatement(sql).getOrThrow()

    private fun rows(sql: String): List<String> =
        env
            .executeQuery(statement(sql))
            .records
            .map { it.toString() }
            .sorted()

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
        val authorized = SqlAuthorizer.authorize(statement(sql), context).getOrThrow()

        val expected = rows(manuallyFiltered(sql))
        val actual = rows(authorized.sql.value)
        val original = rows(sql)

        assertTrue(original.size > actual.size, "fixture must make the policy observable")
        assertTrue(actual.isNotEmpty(), "fixture must leave authorized rows")
        assertEquals(expected, actual, authorized.sql.value)
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
        val denyInventory = context.copy(policies = context.policies + (TableName("projectedinventory") to Criteria.NoRows))
        val authorized = SqlAuthorizer.authorize(statement(resource("site_summary_aggregates.sql")), denyInventory).getOrThrow()
        assertEquals(emptyList(), env.executeQuery(authorized.sql).records)
    }

    @Test
    fun `a partial policy set fails closed on an unlisted table`() {
        val partial = AuthorizationContext(mapOf(TableName("site") to eastOnly, TableName("geography") to Criteria.AllRows))
        val result = SqlAuthorizer.authorize(statement(resource("site_summary_aggregates.sql")), partial)
        val message = result.exceptionOrNull()?.message.orEmpty()
        assertTrue("'product' is not authorized" in message || "'projectedinventory' is not authorized" in message, message)
    }
}
