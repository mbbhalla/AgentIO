package io.github.mbbhalla.agentio.examples.etl

import io.github.mbbhalla.agentio.core.common.JsonSchemaUtil
import io.github.mbbhalla.agentio.data.model.TableName
import io.github.mbbhalla.agentio.module.patterns.hitl.Decision
import io.github.mbbhalla.agentio.module.patterns.hitl.Proposal
import io.github.mbbhalla.agentio.module.patterns.hitl.Proposer
import io.github.mbbhalla.agentio.module.patterns.hitl.Question
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

/** The agents of one round, bound to that round's workspace. */
internal class EtlAgents(
    val planner: Planner,
    val sqlWriterFor: suspend (references: List<Reference>) -> SqlWriter,
)

/**
 * Makes each round's proposal for [io.github.mbbhalla.agentio.module.patterns.hitl.HumanInTheLoop]: a fresh,
 * empty target, the pipeline run under the reviewer's decisions and feedback, and the round's `etl.sql` and
 * `load-plan.json` written for the reviewer to read. The agents' questions, and a table every answer failed
 * for, become the round's questions.
 */
internal class EtlProposer(
    private val newWorkspace: () -> EtlWorkspace,
    private val agentsFor: suspend (EtlWorkspace) -> EtlAgents,
    private val artifacts: EtlArtifacts,
) : Proposer<EtlProposal> {
    override suspend fun propose(
        round: Int,
        decisions: List<Decision>,
        feedback: List<String>,
    ): Result<Proposal<EtlProposal>> {
        val workspace = newWorkspace()
        val agents = agentsFor(workspace)
        val run =
            EtlPipeline(workspace, agents.planner, agents.sqlWriterFor)
                .run(decisions.map { DecidedQuestion(it.question.text, it.answerText) }, feedback)
                .getOrElse { return Result.failure(it) }
        val proposal = proposalOf(run)
        return runCatching {
            artifacts.write(workspace, proposal, status = "proposed in round $round, awaiting review", decisions)
            Proposal(proposal, questionsOf(run))
        }
    }

    companion object {
        /** Characters of each rejected attempt shown to the reviewer; DuckDB errors repeat the whole statement. */
        private const val ERROR_EXCERPT = 600

        fun proposalOf(run: EtlRun): EtlProposal =
            EtlProposal(
                plan = run.plan,
                loaded = run.loaded,
                stoppedAt =
                    run.blocked?.let { "$it, whose SQL needs the answers first" }
                        ?: run.failure?.let { "${it.table}, after every answer was rejected" },
                references = run.orphans.map { (reference, orphans) -> ReferenceCheck(reference, orphans) },
            )

        fun questionsOf(run: EtlRun): List<Question> {
            val raised =
                run.questions.map { (origin, q) ->
                    Pair("$origin: ${q.question}", Triple(q.evidence, q.options, q.assumedOption))
                }
            val failed =
                run.failure?.let { failure ->
                    "${failure.table}: the agents could not load this table. What should they do differently?" to
                        Triple(
                            failure.attemptErrors
                                .mapIndexed { i, e -> "Attempt ${i + 1}: ${e.take(ERROR_EXCERPT)}" }
                                .joinToString("\n"),
                            emptyList<String>(),
                            null,
                        )
                }
            return (raised + listOfNotNull(failed)).mapIndexed { i, (text, details) ->
                val (evidence, options, assumed) = details
                Question(id = "Q${i + 1}", text = text, evidence = evidence, options = options, assumedOption = assumed)
            }
        }

        /** The proposal as Markdown for the reviewer; headings are level 4 so they sit inside the review file. */
        fun describe(proposal: EtlProposal): String =
            buildString {
                appendLine("#### Tables")
                appendLine()
                appendLine("| # | Table | Sources | Rows | Attempts |")
                appendLine("|---|-------|---------|------|----------|")
                proposal.plan.steps.forEachIndexed { i, step ->
                    val loaded = proposal.loaded.firstOrNull { it.table == step.targetTable.trim().lowercase() }
                    appendLine(
                        "| ${i + 1} | ${step.targetTable} | ${step.sourceFiles.joinToString()} | " +
                            "${loaded?.rows ?: "not loaded"} | ${loaded?.attempts ?: ""} |",
                    )
                }
                proposal.stoppedAt?.let {
                    appendLine()
                    appendLine("**Stopped at $it.** The tables after it were not attempted.")
                }
                if (proposal.references.isNotEmpty()) {
                    appendLine()
                    appendLine("#### References")
                    appendLine()
                    appendLine("| Reference | Orphaned rows |")
                    appendLine("|-----------|---------------|")
                    proposal.references.forEach { appendLine("| ${it.reference} | ${it.orphanedRows} |") }
                }
                appendLine()
                appendLine("#### How each table is loaded")
                proposal.loaded.forEach {
                    appendLine()
                    appendLine("**${it.table}**: ${it.explanation.lines().joinToString(" ") { line -> line.trim() }.trim()}")
                }
                appendLine()
                appendLine(
                    "The SQL is in `${EtlArtifacts.SCRIPT_FILE}` beside this file, and each step's mapping in `${EtlArtifacts.PLAN_FILE}`.",
                )
            }
    }
}

/** Writes a proposal's `etl.sql` and `load-plan.json`, and proves the script loads the same rows on its own. */
internal class EtlArtifacts(
    private val outputDirectory: Path,
    private val contractName: String,
    private val sourceDirectory: Path,
    private val generator: String,
) {
    /** @throws IllegalStateException when replaying `etl.sql` in a fresh DuckDB loads other row counts */
    fun write(
        workspace: EtlWorkspace,
        proposal: EtlProposal,
        status: String,
        decisions: List<Decision>,
    ): Map<TableName, Long> {
        outputDirectory.createDirectories()
        outputDirectory.resolve(PLAN_FILE).writeText(JsonSchemaUtil.json.encodeToString(LoadPlan.serializer(), proposal.plan))
        val header =
            listOf(
                "Generated by the AgentIO ODCS ETL example with $generator; $status.",
                "Target contract: $contractName; sources: $sourceDirectory.",
                "Run against an empty database: duckdb target.duckdb < $SCRIPT_FILE",
            ) +
                if (decisions.isEmpty()) {
                    listOf("Reviewer decisions: none.")
                } else {
                    listOf("Reviewer decisions:") +
                        decisions.map { "  Round ${it.round} [${it.question.id}] ${it.question.text} -> ${it.answerText}" }
                }
        val script = EtlScript.of(workspace, proposal.loaded, header)
        outputDirectory.resolve(SCRIPT_FILE).writeText(script.render())
        val replayed = script.replay()
        val mismatched = proposal.loaded.filter { replayed[TableName(it.table)] != it.rows.toLong() }
        check(mismatched.isEmpty()) { "Replaying $SCRIPT_FILE loads different row counts for ${mismatched.map { it.table }}" }
        return replayed
    }

    companion object {
        const val PLAN_FILE = "load-plan.json"
        const val SCRIPT_FILE = "etl.sql"
    }
}
