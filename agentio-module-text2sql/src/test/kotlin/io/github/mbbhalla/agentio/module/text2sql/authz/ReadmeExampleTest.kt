package io.github.mbbhalla.agentio.module.text2sql.authz

import io.github.mbbhalla.agentio.data.env.DuckDbDatabaseEnvironment
import io.github.mbbhalla.agentio.data.env.SelectSqlStatement
import io.github.mbbhalla.agentio.data.model.ColumnName
import io.github.mbbhalla.agentio.data.model.DataValue
import io.github.mbbhalla.agentio.data.model.TableName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/** Keeps the README example honest. */
class ReadmeExampleTest {
    @Test
    fun `readme example output is exact`() {
        DuckDbDatabaseEnvironment.fromStatements(
            ddl = listOf("CREATE TABLE inventory (site VARCHAR, qty BIGINT)", "CREATE TABLE site (id VARCHAR)"),
            dml = emptyList(),
            tableMetadata = emptySet(),
        )
        val context =
            AuthorizationContext(
                policies =
                    mapOf(
                        TableName("inventory") to
                            (ColumnName("site") isIn listOf(DataValue.StringValue("TX"), DataValue.StringValue("CA"))),
                        TableName("site") to Criteria.AllRows,
                    ),
                unlistedTablePolicy = UnlistedTablePolicy.DENY,
            )
        val result: Result<AuthorizedSql> = SqlAuthorizer.authorize(SelectSqlStatement("SELECT * FROM Inventory"), context)
        assertEquals(
            "WITH authz_inventory AS (SELECT * FROM inventory WHERE (site IN ('TX', 'CA'))) SELECT * FROM authz_inventory AS Inventory",
            result.getOrThrow().sql.value,
        )
    }
}
