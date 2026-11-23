package io.github.mbbhalla.agentio.examples.etl

import io.github.mbbhalla.agentio.data.model.TableName
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The pipeline with the agents replaced by the reference plan and SQL, so everything but the LLM runs. */
class EtlPipelineTest {
    private val workspace = AdventureWorks.workspace()

    private fun referenceAnswer(input: EtlSqlWriterAgenticFunction.Input) =
        EtlSqlWriterAgenticFunction.Output(
            targetTable = input.step.targetTable,
            selectSql = AdventureWorks.referenceSql.getValue(input.step.targetTable),
            explanation = "reference load",
        )

    private fun pipeline(
        planner: Planner = Planner { Result.success(AdventureWorks.referencePlan) },
        writer: SqlWriter = SqlWriter { Result.success(referenceAnswer(it)) },
    ) = EtlPipeline(workspace, planner, { writer })

    @Test
    fun `the reference load fills every planned table with no orphans, and its script replays alone`() {
        val run = runBlocking { pipeline().run() }.getOrThrow()
        assertTrue(run.succeeded)
        assertEquals(AdventureWorks.expectedRows, run.loaded.associate { it.table to it.rows })
        assertEquals(AdventureWorks.references.associateWith { 0L }, run.orphans)
        val script = EtlScript.of(workspace, run, header = listOf("test"))
        assertEquals(68, script.createTables.size)
        assertEquals(AdventureWorks.expectedRows.mapKeys { TableName(it.key) }.mapValues { it.value.toLong() }, script.replay())
        assertContains(script.render(), "INSERT INTO \"employeepayhistory\" BY NAME SELECT * FROM (")
    }

    @Test
    fun `a rejected answer is retried with its error, and each step sees the tables loaded before it`() {
        val seen = mutableListOf<EtlSqlWriterAgenticFunction.Input>()
        val writer =
            SqlWriter { input ->
                seen += input
                if (input.step.targetTable == "shift" && input.previousAttemptError == null) {
                    Result.success(referenceAnswer(input).copy(selectSql = "SELECT 'Day' AS name"))
                } else {
                    Result.success(referenceAnswer(input))
                }
            }
        val run = runBlocking { pipeline(writer = writer).run() }.getOrThrow()
        assertTrue(run.succeeded)
        assertEquals(2, run.loaded.first { it.table == "shift" }.attempts)
        assertContains(seen.last { it.step.targetTable == "shift" }.previousAttemptError.orEmpty(), "NOT NULL constraint failed")
        assertEquals(listOf("department", "shift"), seen.first { it.step.targetTable == "phonenumbertype" }.loadedTables)
        assertEquals(
            listOf(Reference("employeepayhistory", "businessentityid", "employee", "businessentityid")),
            seen.first { it.step.targetTable == "employeepayhistory" }.references,
        )
    }

    @Test
    fun `a step that keeps failing stops the run and reports every attempt`() {
        val writer =
            SqlWriter { input ->
                if (input.step.targetTable ==
                    "person"
                ) {
                    Result.failure(IllegalStateException("tool call limit"))
                } else {
                    Result.success(referenceAnswer(input))
                }
            }
        val run = runBlocking { pipeline(writer = writer).run() }.getOrThrow()
        assertEquals(StepFailure("person", List(3) { "tool call limit" }), run.failure)
        assertEquals(listOf("department", "shift", "phonenumbertype", "businessentity"), run.loaded.map { it.table })
        assertTrue(!run.succeeded)
    }

    @Test
    fun `an answer for another table is rejected`() {
        val writer = SqlWriter { input -> Result.success(referenceAnswer(input).copy(targetTable = "shift")) }
        val run = runBlocking { pipeline(writer = writer).run() }.getOrThrow()
        assertEquals("department", run.failure?.table)
        assertContains(
            run.failure
                ?.attemptErrors
                ?.first()
                .orEmpty(),
            "The answer is for 'shift', not 'department'",
        )
    }

    @Test
    fun `planning is retried with the error, and fails the run when no plan is valid`() {
        val inputs = mutableListOf<EtlPlannerAgenticFunction.Input>()
        val planner =
            Planner { input ->
                inputs += input
                Result.failure(IllegalArgumentException("The load plan is invalid: attempt ${inputs.size}"))
            }
        val result = runBlocking { pipeline(planner = planner).run() }
        assertNull(inputs.first().previousAttemptError)
        assertEquals("The load plan is invalid: attempt 1", inputs[1].previousAttemptError)
        assertContains(result.exceptionOrNull()?.message.orEmpty(), "No valid load plan after 2 attempts")
        assertEquals(68, inputs.first().targetTableCount)
        assertEquals(workspace.sources.names, inputs.first().sourceFiles)
    }
}
