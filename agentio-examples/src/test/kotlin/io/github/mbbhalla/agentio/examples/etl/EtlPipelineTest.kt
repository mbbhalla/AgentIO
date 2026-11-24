package io.github.mbbhalla.agentio.examples.etl

import io.github.mbbhalla.agentio.data.model.TableName
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The pipeline with the agents replaced by the reference plan and SQL, so everything but the LLM runs. */
class EtlPipelineTest {
    private val workspace = AdventureWorks.workspace()

    private fun pipeline(
        planner: Planner = AdventureWorks.referencePlanner,
        writer: SqlWriter = AdventureWorks.referenceWriter,
    ) = EtlPipeline(workspace, planner, { writer })

    @Test
    fun `the reference load fills every planned table with no orphans, and its script replays alone`() {
        val run = runBlocking { pipeline().run() }.getOrThrow()
        assertTrue(run.complete)
        assertEquals(emptyList(), run.questions)
        assertEquals(AdventureWorks.expectedRows, run.loaded.associate { it.table to it.rows })
        assertEquals(AdventureWorks.references.associateWith { 0L }, run.orphans)
        val script = EtlScript.of(workspace, run.loaded, header = listOf("test"))
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
                    AdventureWorks.referenceWriter.write(input).map { it.copy(selectSql = "SELECT 'Day' AS name") }
                } else {
                    AdventureWorks.referenceWriter.write(input)
                }
            }
        val run = runBlocking { pipeline(writer = writer).run() }.getOrThrow()
        assertTrue(run.complete)
        assertEquals(2, run.loaded.first { it.table == "shift" }.attempts)
        assertContains(seen.last { it.step.targetTable == "shift" }.previousAttemptError.orEmpty(), "NOT NULL constraint failed")
        assertEquals(listOf("department", "shift"), seen.first { it.step.targetTable == "phonenumbertype" }.loadedTables)
        assertEquals(
            listOf(Reference("employeepayhistory", "businessentityid", "employee", "businessentityid")),
            seen.first { it.step.targetTable == "employeepayhistory" }.references,
        )
    }

    @Test
    fun `decisions and feedback reach the planner and every writer, and questions come back with their origin`() {
        val decisions = listOf(DecidedQuestion("employee: How do Divorced and Widowed map?", "S"))
        val plannerInputs = mutableListOf<EtlPlannerAgenticFunction.Input>()
        val writerInputs = mutableListOf<EtlSqlWriterAgenticFunction.Input>()
        val planQuestion = AdventureWorks.question("Load jobcandidate?")
        val employeeQuestion = AdventureWorks.question("Is On Leave active?")
        val planner =
            Planner { input ->
                plannerInputs += input
                AdventureWorks.referencePlanner.plan(input).map { it.copy(questions = listOf(planQuestion)) }
            }
        val writer =
            SqlWriter { input ->
                writerInputs += input
                val isEmployee = input.step.targetTable == "employee"
                AdventureWorks.referenceWriter.write(input).map { if (isEmployee) it.copy(questions = listOf(employeeQuestion)) else it }
            }
        val run = runBlocking { pipeline(planner, writer).run(decisions, feedback = listOf("keep ids")) }.getOrThrow()
        assertEquals(decisions, plannerInputs.single().decisions)
        assertTrue(writerInputs.all { it.decisions == decisions && it.reviewerFeedback == listOf("keep ids") })
        val openQuestions = writerInputs.associate { it.step.targetTable to it.openQuestions }
        assertEquals(listOf(planQuestion), openQuestions.getValue("employee"), "writers are told what is already asked")
        assertEquals(listOf(planQuestion, employeeQuestion), openQuestions.getValue("emailaddress"))
        assertEquals(listOf(AgentQuestion("plan", planQuestion), AgentQuestion("employee", employeeQuestion)), run.questions)
        assertTrue(run.complete, "questions with an assumption do not stop the load")
    }

    @Test
    fun `a writer that needs answers first stops the run with its questions`() {
        val writer =
            SqlWriter { input ->
                if (input.step.targetTable == "employeedepartmenthistory") {
                    Result.success(
                        EtlSqlWriterAgenticFunction.Output(
                            input.step.targetTable,
                            selectSql = null,
                            explanation = "",
                            questions = listOf(AdventureWorks.question("paula0 is not in the roster", assumed = null)),
                        ),
                    )
                } else {
                    AdventureWorks.referenceWriter.write(input)
                }
            }
        val run = runBlocking { pipeline(writer = writer).run() }.getOrThrow()
        assertEquals("employeedepartmenthistory", run.blocked)
        assertFalse(run.complete)
        assertEquals(8, run.loaded.size)
        assertEquals(listOf("employeedepartmenthistory"), run.questions.map { it.origin })
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
                    AdventureWorks.referenceWriter.write(input)
                }
            }
        val run = runBlocking { pipeline(writer = writer).run() }.getOrThrow()
        assertEquals(StepFailure("person", List(3) { "tool call limit" }), run.failure)
        assertEquals(listOf("department", "shift", "phonenumbertype", "businessentity"), run.loaded.map { it.table })
        assertFalse(run.complete)
    }

    @Test
    fun `an answer for another table is rejected`() {
        val writer = SqlWriter { input -> AdventureWorks.referenceWriter.write(input).map { it.copy(targetTable = "shift") } }
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
