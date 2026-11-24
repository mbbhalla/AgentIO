package io.github.mbbhalla.agentio.examples.etl

import io.github.mbbhalla.agentio.core.model.LLM
import io.github.mbbhalla.agentio.data.model.DomainModel
import io.github.mbbhalla.agentio.module.patterns.hitl.ConsoleReviewGate
import io.github.mbbhalla.agentio.module.patterns.hitl.FileReviewGate
import io.github.mbbhalla.agentio.module.patterns.hitl.HumanInTheLoop
import io.github.mbbhalla.agentio.module.patterns.hitl.ReviewGate
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import java.nio.file.Path
import kotlin.io.path.name

/**
 * ETL from CSV files into a DuckDB database defined by an Open Data Contract, with a human in the loop:
 *
 * 1. Target: [DomainModel.fromOdcs] reads the engine-neutral contract; each round builds it as an empty
 *    DuckDB database that enforces its types, keys and checks.
 * 2. Middle layer: [EtlPipeline] has a planner agent map the sources onto target tables, then a SQL-writer
 *    agent write and prove each table's load with tools that run against DuckDB. Choices no constraint can
 *    settle, and failures the agents cannot resolve, become questions.
 * 3. Review: [HumanInTheLoop] puts the questions to a reviewer, reruns the agents with the answers, and asks
 *    for sign-off once nothing is open. With the default file reviewer each round ends the process: answer
 *    `SYSTEM_INPUT.md` in the output directory and run again.
 * 4. Output: `etl.sql`, replayed into a fresh DuckDB to prove it works on its own, approved by the reviewer.
 */
internal object Runner {
    private val LOG = LoggerFactory.getLogger(Runner::class.java)
    private val LLM_USED = LLM.ANTHROPIC_CLAUDE_OPUS_5_5_CROSS_REGION_INFERENCE
    private const val MAX_ROUNDS = 5
    private const val RERUN = "./gradlew :agentio-examples:RunEtlAgenticFunction-AdventureWorks"
    const val REVIEW_FILE = "file"
    const val REVIEW_CONSOLE = "console"

    fun run(
        contract: Path,
        sourceDirectory: Path,
        outputDirectory: Path,
        review: String,
    ): HumanInTheLoop.Outcome<EtlProposal> =
        runBlocking {
            val model = DomainModel.fromOdcs(listOf(contract))
            val sources = SourceFiles.of(sourceDirectory)
            LOG.info("Target: {} tables from {}; sources: {}", model.tables.size, contract.name, sources.names)
            val artifacts = EtlArtifacts(outputDirectory, contract.name, sources.directory, LLM_USED.id)
            val fileGate =
                FileReviewGate(
                    directory = outputDirectory,
                    proposalSerializer = EtlProposal.serializer(),
                    describe = EtlProposer::describe,
                    title = "AdventureWorks ETL review",
                    howToContinue = "run `$RERUN` again with the same output directory",
                )
            val gate: ReviewGate<EtlProposal> =
                when (review) {
                    REVIEW_FILE -> fileGate
                    REVIEW_CONSOLE -> ConsoleReviewGate(EtlProposer::describe)
                    else -> throw IllegalArgumentException("review must be '$REVIEW_FILE' or '$REVIEW_CONSOLE', found '$review'")
                }
            check(gate !== fileGate || !fileGate.finished()) {
                "${fileGate.file} records a review that has ended; move it away or choose another output directory to start again"
            }
            val resume = if (gate === fileGate) fileGate.pending() else null
            resume?.let { LOG.info("Resuming round {} from {}", it.round, fileGate.file) }

            val proposer =
                EtlProposer(
                    newWorkspace = { EtlWorkspace(model, sources) },
                    agentsFor = { workspace ->
                        val planner = EtlPlannerAgenticFunction.create(workspace, LLM_USED)
                        EtlAgents(
                            planner = { input -> planner.invoke(input).map { it.output } },
                            sqlWriterFor = { references ->
                                val writer = EtlSqlWriterAgenticFunction.create(workspace, references, LLM_USED)
                                SqlWriter { input -> writer.invoke(input).map { it.output } }
                            },
                        )
                    },
                    artifacts = artifacts,
                )
            val outcome = HumanInTheLoop(proposer, gate, MAX_ROUNDS).run(resume)

            if (outcome is HumanInTheLoop.Outcome.Approved) {
                // The approved SQL, replayed into a fresh target; the agents do not run again.
                artifacts.write(EtlWorkspace(model, sources), outcome.proposal, status = "approved by the reviewer", outcome.decisions)
            }
            if (gate === fileGate && outcome !is HumanInTheLoop.Outcome.AwaitingReview) fileGate.record(outcome)
            report(outcome, outputDirectory, fileGate.file)
            outcome
        }

    private fun report(
        outcome: HumanInTheLoop.Outcome<EtlProposal>,
        outputDirectory: Path,
        reviewFile: Path,
    ) {
        LOG.info("=== Result")
        when (outcome) {
            is HumanInTheLoop.Outcome.AwaitingReview -> {
                val request = outcome.request
                val what = if (request.isSignOff) "sign-off of the mapping" else "${request.questions.size} open question(s)"
                LOG.info("Round {} awaits the reviewer: {}.", request.round, what)
                request.questions.forEach { LOG.info("  [{}] {}", it.id, it.text) }
                LOG.info("Answer {} and run the task again.", reviewFile)
            }
            is HumanInTheLoop.Outcome.Approved -> {
                outcome.proposal.loaded.forEach { LOG.info("  {} rows into {} (attempt {})", it.rows, it.table, it.attempts) }
                outcome.proposal.references.forEach { LOG.info("  {}: {} orphaned rows", it.reference, it.orphanedRows) }
                outcome.decisions.forEach { LOG.info("  Decision: {} -> {}", it.question.text, it.answerText) }
                LOG.info("Approved. ETL script: {}", outputDirectory.resolve(EtlArtifacts.SCRIPT_FILE))
            }
            is HumanInTheLoop.Outcome.Rejected -> LOG.info("Rejected: {}", outcome.reason)
            is HumanInTheLoop.Outcome.Failed -> LOG.error("Failed: {}", outcome.reason)
        }
    }
}

fun main(args: Array<String>) {
    require(args.size == 4) { "Usage: <contract .odcs.yaml> <source CSV directory> <output directory> <file|console>" }
    val outcome = Runner.run(Path.of(args[0]), Path.of(args[1]), Path.of(args[2]), args[3])
    check(outcome !is HumanInTheLoop.Outcome.Failed) { "The ETL failed: ${(outcome as HumanInTheLoop.Outcome.Failed).reason}" }
}
