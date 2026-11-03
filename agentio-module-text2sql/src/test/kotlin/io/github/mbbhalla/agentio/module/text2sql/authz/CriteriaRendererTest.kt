package io.github.mbbhalla.agentio.module.text2sql.authz

import io.github.mbbhalla.agentio.data.model.ColumnName
import io.github.mbbhalla.agentio.data.model.DataValue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals

class CriteriaRendererTest {
    private val site = ColumnName("site")

    @Test
    fun `renders comparison with quoted identifier and escaped literal`() {
        assertEquals("""("site" = 'O''Hare')""", CriteriaRenderer.render(site eq DataValue.StringValue("O'Hare")))
    }

    @Test
    fun `backslash is an ordinary character in string literals`() {
        assertEquals("""("site" = 'a\b')""", CriteriaRenderer.render(site eq DataValue.StringValue("""a\b""")))
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
        assertEquals(expected, CriteriaRenderer.render(criteria))
    }

    @Test
    fun `constant criteria render as tautology and contradiction`() {
        assertEquals("(1 = 1)", CriteriaRenderer.render(Criteria.AllRows))
        assertEquals("(1 = 0)", CriteriaRenderer.render(Criteria.NoRows))
    }

    @Test
    fun `identifier with embedded double quote is escaped by doubling`() {
        assertEquals(""""we""ird"""", CriteriaRenderer.identifier("we\"ird"))
    }

    @Test
    fun `invalid criteria are rejected`() {
        assertThrows<IllegalArgumentException> { site eq DataValue.NullValue }
        assertThrows<IllegalArgumentException> { site isIn emptyList() }
        assertThrows<IllegalArgumentException> { site isIn listOf(DataValue.NullValue) }
        assertThrows<IllegalArgumentException> { Criteria.And(emptyList()) }
        assertThrows<IllegalArgumentException> { Criteria.Or(emptyList()) }
        assertThrows<IllegalArgumentException> { CriteriaRenderer.render(site eq DataValue.DoubleValue(Double.NaN)) }
        assertThrows<IllegalArgumentException> { CriteriaRenderer.render(site eq DataValue.TimestampValue("yesterday")) }
    }
}
