package io.github.mbbhalla.agentio.examples.etl

import io.github.mbbhalla.agentio.data.model.TableName
import org.junit.jupiter.api.assertThrows
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EtlWorkspaceTest {
    private val workspace = AdventureWorks.workspace()
    private val dir = workspace.sources.directory

    @Test
    fun `the target is the whole contract, empty, with its constraints`() {
        assertEquals(68, workspace.targetTables().size)
        val shift = workspace.describe("Shift")
        assertEquals(0, shift.rowCount)
        assertEquals(listOf("shiftid"), shift.columns.filter { it.primaryKey }.map { it.name })
        assertContains(shift.ddl, "starttime TIME")
        assertContains(shift.ddl, "CHECK((length(\"name\") <= 50))")
        assertThrows<IllegalArgumentException> { workspace.describe("employees") }
    }

    @Test
    fun `a profile shows the preamble, the sniffed dialect and the raw text of the first rows`() {
        val costCenters = workspace.profile("cost_centers.csv")
        assertEquals("Adventure Works Cycles - Cost Center Extract", costCenters.firstLines.first())
        assertEquals(";", costCenters.delimiter)
        assertEquals(2, costCenters.skipRows)
        assertEquals(listOf("cc_code", "cc_name", "cc_level", "parent_cc", "valid_from"), costCenters.columns.map { it.name })
        assertEquals(22, costCenters.rowCount)
        val roster = workspace.profile("hris_roster.csv")
        assertTrue(roster.sampleRows.any { "\"National ID\":\"014417807\"" in it }, "leading zeros survive as text: ${roster.sampleRows}")
        assertThrows<IllegalArgumentException> { workspace.profile("../secrets.csv") }
    }

    @Test
    fun `queries are guarded and return rows as JSON objects`() {
        val result = workspace.query("SELECT DISTINCT action FROM read_csv('$dir/org_assignment_events.csv') ORDER BY 1", maxRows = 2)
        assertEquals(4, result.rowCount)
        assertEquals(listOf("""{"action":"HIRE"}""", """{"action":"SHIFT_CHANGE"}"""), result.rows)
        assertThrows<IllegalArgumentException> { workspace.query("SELECT * FROM read_text('/etc/passwd')", maxRows = 1) }
    }

    @Test
    fun `a trial load reports what arrived and leaves the table empty`() {
        val trial = workspace.tryLoad("department", AdventureWorks.referenceSql.getValue("department"), emptyList())
        assertEquals(16, trial.insertedRows)
        assertEquals(mapOf(), trial.nullCounts)
        assertEquals(3, trial.sampleRows.size)
        assertEquals(0, workspace.rowCount(TableName("department")))
    }

    @Test
    fun `a trial load names the constraint DuckDB enforced`() {
        val naive = "SELECT Shift AS shift_name FROM read_csv('$dir/shift_patterns.csv', all_varchar = true)"
        assertContains(
            assertThrows<IllegalArgumentException> { workspace.tryLoad("shift", naive, emptyList()) }.message.orEmpty(),
            "does not have a column with name \"shift_name\"",
            message = "BY NAME matches target columns",
        )
        val wrongTime =
            "SELECT row_number() OVER () AS shiftid, Shift AS name, split_part(Hours, ' - ', 1) AS starttime " +
                "FROM read_csv('$dir/shift_patterns.csv', all_varchar = true)"
        assertContains(
            assertThrows<IllegalArgumentException> { workspace.tryLoad("shift", wrongTime, emptyList()) }.message.orEmpty(),
            "time field value out of range",
        )
        val duplicatePeriods =
            "SELECT 1 AS businessentityid, CAST(\"Period Start\" AS TIMESTAMP) AS ratechangedate " +
                "FROM read_csv('$dir/payroll_register.csv', all_varchar = true) WHERE SSN = '486228782'"
        assertContains(
            assertThrows<IllegalArgumentException> {
                workspace.tryLoad(
                    "employeepayhistory",
                    duplicatePeriods,
                    emptyList(),
                )
            }.message.orEmpty(),
            "PRIMARY KEY or UNIQUE constraint violation: duplicate key \"1, 2010-12-23 00:00:00\"",
            message = "the register repeats one pay period",
        )
        assertEquals(0, workspace.rowCount(TableName("shift")))
    }

    @Test
    fun `a trial load counts orphaned references, and a table is loaded once`() {
        val reference = Reference("employeepayhistory", "businessentityid", "employee", "businessentityid")
        val pay = "SELECT 1001 AS businessentityid, TIMESTAMP '2009-01-14' AS ratechangedate, 125.5 AS rate"
        assertEquals(
            listOf("employeepayhistory.businessentityid -> employee.businessentityid: 1 orphaned rows"),
            workspace.tryLoad("employeepayhistory", pay, listOf(reference)).orphanedReferences,
        )
        assertEquals(1, workspace.load("employeepayhistory", pay))
        assertContains(
            assertThrows<IllegalArgumentException> { workspace.load("employeepayhistory", pay) }.message.orEmpty(),
            "already holds 1 rows",
        )
    }

    @Test
    fun `agent SQL cannot reach other files through a load`() {
        assertThrows<IllegalArgumentException> {
            workspace.tryLoad("department", "SELECT 1 AS departmentid, content AS name FROM read_text('/etc/hosts')", emptyList())
        }
    }
}
