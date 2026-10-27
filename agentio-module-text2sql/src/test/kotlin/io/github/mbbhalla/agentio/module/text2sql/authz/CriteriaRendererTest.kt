package io.github.mbbhalla.agentio.module.text2sql.authz

import io.github.mbbhalla.agentio.data.model.ColumnName
import io.github.mbbhalla.agentio.data.model.DataValue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CriteriaRendererTest {
    private val site = ColumnName("site")

    @Test
    fun `renders comparison with quoted identifier and escaped literal`() {
        val rendered = CriteriaRenderer.render(site eq DataValue.StringValue("O'Hare"), RedshiftSqlDialect)
        assertEquals("""("site" = 'O''Hare')""", rendered)
    }

    @Test
    fun `redshift doubles backslashes but duckdb does not`() {
        val criteria = site eq DataValue.StringValue("""a\b""")
        assertEquals("""("site" = 'a\\b')""", CriteriaRenderer.render(criteria, RedshiftSqlDialect))
        assertEquals("""("site" = 'a\b')""", CriteriaRenderer.render(criteria, DuckDbSqlDialect))
    }

    @Test
    fun `renders every leaf and combinator with explicit parentheses`() {
        val criteria =
            Criteria.and(
                Criteria.or(site eq DataValue.StringValue("TX"), site eq DataValue.StringValue("CA")),
                ColumnName("qty") gte DataValue.LongValue(10),
                ColumnName("ratio") lt DataValue.DoubleValue(0.5),
                ColumnName("active") eq DataValue.BooleanValue(true),
                ColumnName("ts") gt DataValue.TimestampValue("2024-01-15T00:00:00Z"),
                ColumnName("code") isIn listOf(DataValue.StringValue("A"), DataValue.LongValue(2)),
                ColumnName("code") notIn listOf(DataValue.StringValue("Z")),
                ColumnName("deleted_at").isNull(),
                ColumnName("owner").isNotNull(),
                Criteria.not(ColumnName("name") like DataValue.StringValue("tmp%")),
            )
        val expected =
            """((("site" = 'TX') OR ("site" = 'CA')) AND ("qty" >= 10) AND ("ratio" < 0.5) AND ("active" = TRUE) """ +
                """AND ("ts" > TIMESTAMP '2024-01-15 00:00:00.000000') AND ("code" IN ('A', 2)) """ +
                """AND ("code" NOT IN ('Z')) AND ("deleted_at" IS NULL) AND ("owner" IS NOT NULL) """ +
                """AND (NOT ("name" LIKE 'tmp%')))"""
        assertEquals(expected, CriteriaRenderer.render(criteria, RedshiftSqlDialect))
    }

    @Test
    fun `constant criteria render as tautology and contradiction`() {
        assertEquals("(1 = 1)", CriteriaRenderer.render(Criteria.AllRows, RedshiftSqlDialect))
        assertEquals("(1 = 0)", CriteriaRenderer.render(Criteria.NoRows, RedshiftSqlDialect))
    }

    @Test
    fun `identifier with embedded double quote is escaped by doubling`() {
        assertEquals(""""we""ird"""", RedshiftSqlDialect.quoteIdentifier("we\"ird"))
    }

    @Test
    fun `invalid criteria are rejected at construction`() {
        assertThrows<IllegalArgumentException> { site eq DataValue.NullValue }
        assertThrows<IllegalArgumentException> { site isIn emptyList() }
        assertThrows<IllegalArgumentException> { site isIn listOf(DataValue.NullValue) }
        assertThrows<IllegalArgumentException> { Criteria.And(emptyList()) }
        assertThrows<IllegalArgumentException> { Criteria.Or(emptyList()) }
        assertThrows<IllegalArgumentException> {
            CriteriaRenderer.render(site eq DataValue.DoubleValue(Double.NaN), RedshiftSqlDialect)
        }
        assertThrows<IllegalArgumentException> {
            CriteriaRenderer.render(site eq DataValue.TimestampValue("yesterday"), RedshiftSqlDialect)
        }
    }
}

class AuthorizationContextTest {
    @Test
    fun `redshift canonicalises quoted and unquoted identifiers to lower case`() {
        val quoted = RedshiftSqlDialect.tableReference(name = "\"Inventory\"", schema = "\"SUPPLY\"", database = "\"Db\"")
        val unquoted = RedshiftSqlDialect.tableReference(name = "INVENTORY", schema = "supply", database = "db")
        assertEquals(unquoted, quoted)
        assertEquals("db.supply.inventory", quoted.toString())
    }

    @Test
    fun `policy lookup falls back from fully qualified to schema-less to name-only`() {
        val exact = RedshiftSqlDialect.tableReference(name = "site", schema = "supply", database = "db")
        val schemaLevel = RedshiftSqlDialect.tableReference(name = "site", schema = "supply")
        val nameOnly = RedshiftSqlDialect.tableReference(name = "site")
        val c1 = Criteria.NoRows
        val c2 = Criteria.AllRows
        val c3 = ColumnName("id") eq DataValue.LongValue(1)

        val ctx = AuthorizationContext(RedshiftSqlDialect, mapOf(exact to c1, schemaLevel to c2, nameOnly to c3))
        assertEquals(c1, ctx.policyFor(exact))
        assertEquals(c2, ctx.policyFor(RedshiftSqlDialect.tableReference(name = "site", schema = "supply", database = "other")))
        assertEquals(c3, ctx.policyFor(RedshiftSqlDialect.tableReference(name = "site", schema = "analytics")))
        assertNull(ctx.policyFor(RedshiftSqlDialect.tableReference(name = "product", schema = "analytics")))
    }

    @Test
    fun `table reference invariants are enforced by the factory`() {
        assertThrows<IllegalArgumentException> { RedshiftSqlDialect.tableReference(name = " ") }
        assertThrows<IllegalArgumentException> { RedshiftSqlDialect.tableReference(name = "t", database = "db") }
    }

    @Test
    fun `default schema is canonicalised at construction`() {
        val ctx = AuthorizationContext(RedshiftSqlDialect, emptyMap(), defaultSchema = "SUPPLY")
        assertEquals("supply", ctx.defaultSchema)
    }

    @Test
    fun `policy keys that are not canonical for the context dialect are rejected`() {
        val caseSensitive =
            object : PostgresFamilyDialect() {
                override val name = "case-sensitive"

                override fun canonicalIdentifier(
                    part: String,
                    quoted: Boolean,
                ) = part

                override fun quoteString(raw: String) = "'" + raw.replace("'", "''") + "'"
            }
        val mixedCaseKey = caseSensitive.tableReference(name = "Inventory")
        assertThrows<IllegalArgumentException> {
            AuthorizationContext(RedshiftSqlDialect, mapOf(mixedCaseKey to Criteria.AllRows))
        }
    }
}

class AuthorizedSqlTest {
    private val none = emptySet<TableReference>()

    @Test
    fun `cannot hold unparseable sql`() {
        assertThrows<IllegalArgumentException> { AuthorizedSql("SELECT * FORM t", none, none) }
        assertThrows<IllegalArgumentException> { AuthorizedSql("  ", none, none) }
    }

    @Test
    fun `cannot hold a non-select statement`() {
        assertThrows<IllegalArgumentException> { AuthorizedSql("DELETE FROM t", none, none) }
    }

    @Test
    fun `a table cannot be both filtered and unfiltered`() {
        val t = RedshiftSqlDialect.tableReference(name = "t")
        assertThrows<IllegalArgumentException> { AuthorizedSql("SELECT * FROM t", setOf(t), setOf(t)) }
    }
}
