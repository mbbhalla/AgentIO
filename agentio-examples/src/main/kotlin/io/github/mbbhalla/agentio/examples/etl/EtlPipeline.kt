package io.github.mbbhalla.agentio.examples.etl

import org.slf4j.LoggerFactory

/** Produces a load plan; [EtlPlannerAgenticFunction] in a run, a fixed plan in tests. */
internal fun interface Planner {
    suspend fun plan(input: EtlPlannerAgenticFunction.Input): Result<LoadPlan>
}

/** Writes one step's SELECT; [EtlSqlWriterAgenticFunction] in a run, reference SQL in tests. */
internal fun interface SqlWriter {
    suspend fun write(input: EtlSqlWriterAgenticFunction.Input): Result<EtlSqlWriterAgenticFunction.Output>
}

/** A step whose answers were all rejected, with the error of each attempt. */
data class StepFailure(
    val table: String,
    val attemptErrors: List<String>,
)

/** A question an agent raised, and what it is about: [PLAN] or a target table. */
data class AgentQuestion(
    val origin: String,
    val question: RaisedQuestion,
) {
    companion object {
        const val PLAN = "plan"
    }
}

/**
 * What one run did: the plan, the tables it loaded in order, the questions the agents raised, and where it
 * stopped if it did, because a writer needed answers first ([blocked]) or every answer was rejected ([failure]).
 */
data class EtlRun(
    val plan: LoadPlan,
    val loaded: List<LoadedTable>,
    val questions: List<AgentQuestion>,
    val blocked: String?,
    val failure: StepFailure?,
    val orphans: Map<Reference, Long>,
) {
    /** Every planned table loaded and no reference has an orphan; questions may still be open. */
    val complete: Boolean get() = blocked == null && failure == null && orphans.values.all { it == 0L }
}

/**
 * The middle layer of the ETL: a planner agent decides what the sources become, then one SQL-writer
 * invocation per planned table writes a SELECT and proves it against the target's constraints. Each
 * accepted SELECT is loaded for real before the next step, so later steps can join the tables already
 * loaded. A rejected answer is retried with its error. A step that still fails, or whose writer cannot go
 * on without the reviewer's answers, stops the run, because later steps may depend on it.
 *
 * The reviewer's [DecidedQuestion]s and feedback go to every agent; the agents' questions come back in
 * [EtlRun.questions] for the next review.
 */
internal class EtlPipeline(
    private val workspace: EtlWorkspace,
    private val planner: Planner,
    private val sqlWriterFor: suspend (references: List<Reference>) -> SqlWriter,
    private val maxPlanAttempts: Int = DEFAULT_PLAN_ATTEMPTS,
    private val maxWriteAttempts: Int = DEFAULT_WRITE_ATTEMPTS,
) {
    init {
        require(maxPlanAttempts >= 1 && maxWriteAttempts >= 1) { "Attempts must be at least 1" }
    }

    /** Fails only when no valid plan was produced; a step that cannot be loaded is reported in the [EtlRun]. */
    suspend fun run(
        decisions: List<DecidedQuestion> = emptyList(),
        feedback: List<String> = emptyList(),
    ): Result<EtlRun> =
        plan(decisions, feedback).map { plan ->
            LOG.info("Plan: {}", plan.steps.joinToString(" -> ") { it.targetTable })
            val writer = sqlWriterFor(plan.references)
            val loaded = mutableListOf<LoadedTable>()
            val questions = plan.questions.map { AgentQuestion(AgentQuestion.PLAN, it) }.toMutableList()
            var blocked: String? = null
            var failure: StepFailure? = null
            for (step in plan.steps) {
                LOG.info("=== Loading {} from {}", step.targetTable, step.sourceFiles)
                val context = StepContext(plan, loaded.map { it.table }, decisions, feedback, questions.map { it.question })
                when (val outcome = loadStep(step, context, writer)) {
                    is StepOutcome.Loaded -> {
                        loaded += outcome.table
                        questions += outcome.questions.map { AgentQuestion(outcome.table.table, it) }
                    }
                    is StepOutcome.Blocked -> {
                        questions += outcome.questions.map { AgentQuestion(outcome.table, it) }
                        blocked = outcome.table
                        break
                    }
                    is StepOutcome.Failed -> {
                        failure = outcome.failure
                        break
                    }
                }
            }
            EtlRun(plan, loaded.toList(), questions.toList(), blocked, failure, plan.references.associateWith { workspace.orphans(it) })
        }

    private suspend fun plan(
        decisions: List<DecidedQuestion>,
        feedback: List<String>,
    ): Result<LoadPlan> {
        val errors = mutableListOf<String>()
        repeat(maxPlanAttempts) { attempt ->
            LOG.info("=== Planning, attempt {}/{}", attempt + 1, maxPlanAttempts)
            val input =
                EtlPlannerAgenticFunction.Input(
                    sourceFiles = workspace.sources.names,
                    targetTableCount = workspace.model.tables.size,
                    decisions = decisions,
                    reviewerFeedback = feedback,
                    previousAttemptError = errors.lastOrNull(),
                )
            planner
                .plan(input)
                .onSuccess { return Result.success(it) }
                .onFailure { errors += it.message ?: it.javaClass.simpleName }
        }
        return Result.failure(IllegalStateException("No valid load plan after $maxPlanAttempts attempts:\n${errors.joinToString("\n")}"))
    }

    private sealed interface StepOutcome {
        data class Loaded(
            val table: LoadedTable,
            val questions: List<RaisedQuestion>,
        ) : StepOutcome

        data class Blocked(
            val table: String,
            val questions: List<RaisedQuestion>,
        ) : StepOutcome

        data class Failed(
            val failure: StepFailure,
        ) : StepOutcome
    }

    /** What a step's writer is told besides the step: the plan, the tables before it, and the review so far. */
    private data class StepContext(
        val plan: LoadPlan,
        val loadedTables: List<String>,
        val decisions: List<DecidedQuestion>,
        val feedback: List<String>,
        val openQuestions: List<RaisedQuestion>,
    )

    private suspend fun loadStep(
        step: LoadStep,
        context: StepContext,
        writer: SqlWriter,
    ): StepOutcome {
        val table = workspace.targetTable(step.targetTable).value
        val references = context.plan.references.filter { workspace.targetTable(it.fromTable).value == table }
        val errors = mutableListOf<String>()
        for (attempt in 1..maxWriteAttempts) {
            val input =
                EtlSqlWriterAgenticFunction.Input(
                    step = step,
                    plan = context.plan.steps,
                    loadedTables = context.loadedTables,
                    references = references,
                    decisions = context.decisions,
                    reviewerFeedback = context.feedback,
                    openQuestions = context.openQuestions,
                    previousAttemptError = errors.lastOrNull(),
                )
            val outcome =
                writer
                    .write(input)
                    .mapCatching { output ->
                        require(workspace.targetTable(output.targetTable).value == table) {
                            "The answer is for '${output.targetTable}', not '$table'"
                        }
                        val sql = output.selectSql ?: return@mapCatching StepOutcome.Blocked(table, output.questions)
                        val rows = workspace.load(table, sql)
                        StepOutcome.Loaded(LoadedTable(table, EtlWorkspace.body(sql), output.explanation, rows, attempt), output.questions)
                    }
            outcome.onSuccess { return it }
            val error = outcome.exceptionOrNull()?.message ?: "unknown error"
            LOG.warn("{} attempt {}/{} rejected: {}", table, attempt, maxWriteAttempts, error)
            errors += error
        }
        return StepOutcome.Failed(StepFailure(table, errors.toList()))
    }

    companion object {
        private val LOG = LoggerFactory.getLogger(EtlPipeline::class.java)
        private const val DEFAULT_PLAN_ATTEMPTS = 2
        private const val DEFAULT_WRITE_ATTEMPTS = 3
    }
}
