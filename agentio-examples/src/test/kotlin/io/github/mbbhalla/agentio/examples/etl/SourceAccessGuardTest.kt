package io.github.mbbhalla.agentio.examples.etl

import org.junit.jupiter.api.Nested
import kotlin.test.Test
import kotlin.test.assertTrue

class SourceAccessGuardTest {
    private val workspace = AdventureWorks.workspace()
    private val guard = SourceAccessGuard(workspace.env, workspace.sources)
    private val roster = "${workspace.sources.directory}/hris_roster.csv"
    private val events = "${workspace.sources.directory}/org_assignment_events.csv"

    private fun allowed(sql: String) = assertTrue(guard.check(sql).isSuccess, "expected allowed: $sql, got ${guard.check(sql)}")

    private fun rejected(
        sql: String,
        because: String,
    ) {
        val message = guard.check(sql).exceptionOrNull()?.message
        assertTrue(message != null && because in message, "expected '$because' for: $sql, got $message")
    }

    @Nested
    inner class Allowed {
        @Test
        fun `read_csv of a source file, with options`() = allowed("SELECT * FROM read_csv('$roster', all_varchar = true, header = true)")

        @Test
        fun `sources and target tables in CTEs, joins, subqueries and unions`() =
            allowed(
                """
                WITH r AS (SELECT * FROM read_csv('$roster', all_varchar = true))
                SELECT e.login FROM (SELECT lower(login) AS login FROM read_csv_auto('$events')) AS e
                JOIN r ON lower(r.Login) = e.login
                JOIN employee AS x ON x.loginid = e.login
                UNION ALL SELECT 'it''s' FROM department
                """,
            )

        @Test
        fun `unnest, generate_series, VALUES and a recursive CTE read no files`() =
            allowed(
                """
                WITH RECURSIVE n AS (SELECT 1 AS i UNION ALL SELECT i + 1 FROM n WHERE i < 3)
                SELECT * FROM n, generate_series(1, 2) AS g(x), unnest(['a', 'b']) AS u(y), (VALUES (1)) AS v(z)
                """,
            )

        @Test
        fun `sniff_csv of a source file`() = allowed("SELECT * FROM sniff_csv('$roster')")
    }

    @Nested
    inner class Rejected {
        @Test
        fun `a file outside the sources`() = rejected("SELECT * FROM read_csv('/etc/passwd')", "is not one of the source files")

        @Test
        fun `a path that leaves the source directory`() =
            rejected(
                "SELECT * FROM read_csv('${workspace.sources.directory}/../../../build.gradle.kts')",
                "is not one of the source files",
            )

        @Test
        fun `a relative path`() = rejected("SELECT * FROM read_csv('hris_roster.csv')", "must be absolute")

        @Test
        fun `a glob`() = rejected("SELECT * FROM read_csv('${workspace.sources.directory}/*.csv')", "is not one of the source files")

        @Test
        fun `a computed or listed path`() {
            rejected("SELECT * FROM read_csv(concat('/etc/', 'passwd'))", "named by a string literal")
            rejected("SELECT * FROM read_csv(['$roster', '/etc/passwd'])", "named by a string literal")
        }

        @Test
        fun `a file read as a table, even a source file`() = rejected("SELECT * FROM '$roster'", "is read as a file")

        @Test
        fun `other table functions`() {
            rejected("SELECT * FROM read_text('/etc/passwd')", "table function read_text is not permitted")
            rejected("SELECT * FROM read_parquet('/tmp/x.parquet')", "table function read_parquet is not permitted")
            rejected("SELECT * FROM glob('/Users/*')", "table function glob is not permitted")
            rejected("SELECT * FROM query('SELECT 1')", "table function query is not permitted")
        }

        @Test
        fun `a disallowed source nested anywhere`() =
            rejected(
                "SELECT 1 FROM department WHERE departmentid IN (SELECT 1 FROM read_text('/etc/hosts'))",
                "read_text is not permitted",
            )

        @Test
        fun `DESCRIBE and SUMMARIZE`() {
            rejected("SELECT * FROM (DESCRIBE department)", "SHOW_REF is not permitted")
            rejected("SUMMARIZE department", "SHOW_REF is not permitted")
        }

        @Test
        fun `anything but one SELECT`() {
            rejected("DELETE FROM department", "Not a single SELECT")
            rejected("SELECT 1; SELECT 2", "must not contain ';'")
            rejected("SELECT * FROM read_csv('$roster', delim = ';')", "write chr(59) for a semicolon")
            allowed("SELECT * FROM read_csv('$roster', delim = chr(44));")
            rejected("COPY department TO '/tmp/x.csv'", "Not a single SELECT")
        }
    }
}
