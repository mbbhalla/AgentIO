package io.github.mbbhalla.agentio.data.model

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MVELExpressionTest {
    private fun dataset(vararg rows: Map<String, DataValue>) =
        Dataset(
            columns =
                if (rows.isEmpty()) {
                    emptyList()
                } else {
                    rows.first().map { Dataset.ColumnMeta(ColumnName(it.key), ColumnType.VARCHAR) }
                },
            records = rows.map { Dataset.Record(it.mapKeys { e -> e.key }) },
        )

    private fun numericDataset(
        columnName: String,
        vararg values: Long,
    ) = Dataset(
        columns = listOf(Dataset.ColumnMeta(ColumnName(columnName), ColumnType.BIGINT)),
        records = values.map { Dataset.Record(mapOf(columnName to DataValue.LongValue(it))) },
    )

    // --- MVELExpression construction ---

    @Test
    fun `valid expression compiles`() {
        MVELExpression("!this.records.empty")
    }

    @Test
    fun `invalid expression fails construction`() {
        assertFailsWith<IllegalArgumentException> {
            MVELExpression("this.records.???invalid")
        }
    }

    // --- Dataset evaluate with non-empty check ---

    @Test
    fun `non-empty dataset evaluates true for records not empty`() {
        val ds = dataset(mapOf("id" to DataValue.StringValue("a")))
        val expr = MVELExpression("!this.records.empty")
        assertTrue(ds.evaluate(expr))
    }

    @Test
    fun `empty dataset evaluates false for records not empty`() {
        val ds = dataset()
        val expr = MVELExpression("!this.records.empty")
        assertFalse(ds.evaluate(expr))
    }

    // --- Dataset evaluate with empty check (inverse) ---

    @Test
    fun `empty dataset evaluates true for records empty`() {
        val ds = dataset()
        val expr = MVELExpression("this.records.empty")
        assertTrue(ds.evaluate(expr))
    }

    @Test
    fun `non-empty dataset evaluates false for records empty`() {
        val ds = dataset(mapOf("id" to DataValue.StringValue("a")))
        val expr = MVELExpression("this.records.empty")
        assertFalse(ds.evaluate(expr))
    }

    // --- Dataset evaluate with size check ---

    @Test
    fun `size greater than threshold evaluates true`() {
        val ds = numericDataset("qty", 1, 2, 3, 4, 5)
        val expr = MVELExpression("this.records.size() > 3")
        assertTrue(ds.evaluate(expr))
    }

    @Test
    fun `size not greater than threshold evaluates false`() {
        val ds = numericDataset("qty", 1, 2)
        val expr = MVELExpression("this.records.size() > 3")
        assertFalse(ds.evaluate(expr))
    }

    @Test
    fun `exact size equals check`() {
        val ds = numericDataset("qty", 10, 20, 30)
        val expr = MVELExpression("this.records.size() == 3")
        assertTrue(ds.evaluate(expr))
    }

    // --- Dataset evaluate accessing record values ---

    @Test
    fun `access first record value for scalar comparison`() {
        val ds =
            Dataset(
                columns = listOf(Dataset.ColumnMeta(ColumnName("quantity"), ColumnType.BIGINT)),
                records = listOf(Dataset.Record(mapOf("quantity" to DataValue.LongValue(150L)))),
            )
        val expr =
            MVELExpression(
                $$"((io.github.mbbhalla.agentio.data.model.DataValue$LongValue) this.records[0].values[\"quantity\"]).value < 500",
            )
        assertTrue(ds.evaluate(expr))
    }

    @Test
    fun `scalar comparison false when value exceeds threshold`() {
        val ds =
            Dataset(
                columns = listOf(Dataset.ColumnMeta(ColumnName("quantity"), ColumnType.BIGINT)),
                records = listOf(Dataset.Record(mapOf("quantity" to DataValue.LongValue(600L)))),
            )
        val expr =
            MVELExpression(
                $$"((io.github.mbbhalla.agentio.data.model.DataValue$LongValue) this.records[0].values[\"quantity\"]).value < 500",
            )
        assertFalse(ds.evaluate(expr))
    }

    // --- Compound expressions ---

    @Test
    fun `compound AND expression`() {
        val ds = numericDataset("v", 1, 2, 3)
        val expr = MVELExpression("!this.records.empty && this.records.size() < 5")
        assertTrue(ds.evaluate(expr))
    }

    @Test
    fun `compound OR expression`() {
        val ds = dataset()
        val expr = MVELExpression("this.records.empty || this.records.size() > 10")
        assertTrue(ds.evaluate(expr))
    }

    // --- Edge cases ---

    @Test
    fun `expression returning non-zero integer coerces to true`() {
        val ds = dataset(mapOf("x" to DataValue.StringValue("hello")))
        val expr = MVELExpression("this.records.size()")
        assertTrue(ds.evaluate(expr))
    }

    @Test
    fun `expression returning zero coerces to false`() {
        val ds = dataset()
        val expr = MVELExpression("this.records.size()")
        assertFalse(ds.evaluate(expr))
    }

    @Test
    fun `multiple columns dataset`() {
        val ds =
            Dataset(
                columns =
                    listOf(
                        Dataset.ColumnMeta(ColumnName("site"), ColumnType.VARCHAR),
                        Dataset.ColumnMeta(ColumnName("qty"), ColumnType.BIGINT),
                    ),
                records =
                    listOf(
                        Dataset.Record(mapOf("site" to DataValue.StringValue("WH-01"), "qty" to DataValue.LongValue(100L))),
                        Dataset.Record(mapOf("site" to DataValue.StringValue("WH-02"), "qty" to DataValue.LongValue(200L))),
                    ),
            )
        val expr = MVELExpression("this.records.size() == 2")
        assertTrue(ds.evaluate(expr))
    }

    @Test
    fun `default expression constant`() {
        val expr = MVELExpression(MVELExpression.DEFAULT)
        val nonEmpty = dataset(mapOf("a" to DataValue.LongValue(1L)))
        val empty = dataset()
        assertTrue(nonEmpty.evaluate(expr))
        assertFalse(empty.evaluate(expr))
    }
}
