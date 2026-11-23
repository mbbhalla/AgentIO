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

/** A step that could not be loaded, with the error of each attempt. */
data class StepFailure(
    val table: String,
    val attemptErrors: List<String>,
)

/** What a run did: the plan, the tables it loaded in order, where it stopped if it did, and the final reference check. */
data class EtlRun(
    val plan: LoadPlan,
    val loaded: List<LoadedTable>,
    val failure: StepFailure?,
    val orphans: Map<Reference, Long>,
) {
    val succeeded: Boolean get() = failure == null && orphans.values.all { it == 0L }
}

/**
 * The middle layer of the ETL: a planner agent decides what the sources become, then one SQL-writer
 * invocation per planned table writes a SELECT and proves it against the target's constraints. Each
 * accepted SELECT is loaded for real before the next step, so later steps can join the tables already
 * loaded. A rejected answer is retried with its error; a step that still fails stops the run, because
 * later steps may depend on it.
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

    /** Fails only when no valid plan was produced; a step that cannot be loaded is reported in [EtlRun.failure]. */
    suspend fun run(): Result<EtlRun> =
        plan().map { plan ->
            LOG.info("Plan: {}", plan.steps.joinToString(" -> ") { it.targetTable })
            val writer = sqlWriterFor(plan.references)
            val loaded = mutableListOf<LoadedTable>()
            var failure: StepFailure? = null
            for (step in plan.steps) {
                LOG.info("=== Loading {} from {}", step.targetTable, step.sourceFiles)
                when (val outcome = loadStep(step, plan, loaded.map { it.table }, writer)) {
                    is StepOutcome.Loaded -> loaded += outcome.table
                    is StepOutcome.Failed -> {
                        failure = outcome.failure
                        break
                    }
                }
            }
            EtlRun(plan, loaded.toList(), failure, plan.references.associateWith { workspace.orphans(it) })
        }

    private suspend fun plan(): Result<LoadPlan> {
        val errors = mutableListOf<String>()
        repeat(maxPlanAttempts) { attempt ->
            LOG.info("=== Planning, attempt {}/{}", attempt + 1, maxPlanAttempts)
            val input =
                EtlPlannerAgenticFunction.Input(
                    sourceFiles = workspace.sources.names,
                    targetTableCount = workspace.model.tables.size,
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
        ) : StepOutcome

        data class Failed(
            val failure: StepFailure,
        ) : StepOutcome
    }

    private suspend fun loadStep(
        step: LoadStep,
        plan: LoadPlan,
        loadedTables: List<String>,
        writer: SqlWriter,
    ): StepOutcome {
        val table = workspace.targetTable(step.targetTable).value
        val references = plan.references.filter { workspace.targetTable(it.fromTable).value == table }
        val errors = mutableListOf<String>()
        for (attempt in 1..maxWriteAttempts) {
            val input = EtlSqlWriterAgenticFunction.Input(step, plan.steps, loadedTables, references, errors.lastOrNull())
            val loaded =
                writer
                    .write(input)
                    .mapCatching { output ->
                        require(workspace.targetTable(output.targetTable).value == table) {
                            "The answer is for '${output.targetTable}', not '$table'"
                        }
                        LoadedTable(
                            table,
                            EtlWorkspace.body(output.selectSql),
                            output.explanation,
                            workspace.load(table, output.selectSql),
                            attempt,
                        )
                    }
            loaded.onSuccess { return StepOutcome.Loaded(it) }
            val error = loaded.exceptionOrNull()?.message ?: "unknown error"
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
