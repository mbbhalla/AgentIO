package io.github.mbbhalla.agentio.data.model

import org.junit.jupiter.api.assertThrows
import kotlin.test.Test
import kotlin.test.assertEquals

class TableNameTest {
    @Test
    fun `valid table names construct successfully`() {
        assertEquals("site", TableName("site").value)
        assertEquals("purchase_order", TableName("purchase_order").value)
        assertEquals("t1", TableName("t1").value)
        assertEquals("a", TableName("a").value)
    }

    @Test
    fun `empty string is rejected`() {
        assertThrows<IllegalArgumentException> { TableName("") }
    }

    @Test
    fun `uppercase is rejected`() {
        assertThrows<IllegalArgumentException> { TableName("Site") }
    }

    @Test
    fun `starts with digit is rejected`() {
        assertThrows<IllegalArgumentException> { TableName("1table") }
    }

    @Test
    fun `starts with underscore is rejected`() {
        assertThrows<IllegalArgumentException> { TableName("_table") }
    }

    @Test
    fun `special characters are rejected`() {
        assertThrows<IllegalArgumentException> { TableName("table-name") }
        assertThrows<IllegalArgumentException> { TableName("table.name") }
        assertThrows<IllegalArgumentException> { TableName("table name") }
    }
}
