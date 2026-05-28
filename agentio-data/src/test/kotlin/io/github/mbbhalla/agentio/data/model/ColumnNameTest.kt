package io.github.mbbhalla.agentio.data.model

import org.junit.jupiter.api.assertThrows
import kotlin.test.Test
import kotlin.test.assertEquals

class ColumnNameTest {
    @Test
    fun `valid column names construct successfully`() {
        assertEquals("site_id", ColumnName("site_id").value)
        assertEquals("quantity_on_hand", ColumnName("quantity_on_hand").value)
        assertEquals("x", ColumnName("x").value)
    }

    @Test
    fun `empty string is rejected`() {
        assertThrows<IllegalArgumentException> { ColumnName("") }
    }

    @Test
    fun `uppercase is rejected`() {
        assertThrows<IllegalArgumentException> { ColumnName("SiteId") }
    }

    @Test
    fun `special characters are rejected`() {
        assertThrows<IllegalArgumentException> { ColumnName("col-name") }
        assertThrows<IllegalArgumentException> { ColumnName("col.name") }
    }
}
