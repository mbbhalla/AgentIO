package io.github.mbbhalla.agentio.module.text2sql.authz

import io.github.mbbhalla.agentio.data.model.ColumnName
import io.github.mbbhalla.agentio.data.model.DataValue
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/** Keeps the README example honest. */
class ReadmeExampleTest {
    @Test
    fun `readme example output is exact`() {
        val authorizer = SqlAuthorizer()
        val context =
            AuthorizationContext(
                dialect = RedshiftSqlDialect,
                policies =
                    mapOf(
                        RedshiftSqlDialect.tableReference(name = "inventory", schema = "supply") to
                            (ColumnName("site") isIn listOf(DataValue.StringValue("TX"), DataValue.StringValue("CA"))),
                        RedshiftSqlDialect.tableReference(name = "site", schema = "supply") to Criteria.AllRows,
                    ),
                defaultSchema = "supply",
            )
        val sql = authorizer.authorize("SELECT * FROM Inventory", context).getOrThrow().sql
        assertEquals(
            "WITH authz_supply_inventory AS (SELECT * FROM Inventory WHERE (\"site\" IN ('TX', 'CA'))) " +
                "SELECT * FROM authz_supply_inventory AS Inventory",
            sql,
        )
    }
}
