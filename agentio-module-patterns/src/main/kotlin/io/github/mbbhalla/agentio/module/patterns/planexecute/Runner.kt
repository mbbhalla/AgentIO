package io.github.mbbhalla.agentio.module.patterns.planexecute

import io.github.mbbhalla.agentio.module.patterns.common.PatternAgents
import io.github.mbbhalla.agentio.module.patterns.common.WorkspaceMcpServer
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import java.nio.file.Path

/**
 * Plan-and-Execute pattern. Arguments: repoPath, then an optional goal. The planner writes a short plan over
 * read-only workspace tools, the executor runs each step, and the synthesizer writes the brief.
 */
internal object Runner {
    private const val PLANNER_AGENT_ID = "pe-planner-d4e5f6a7-0001-0001-0001-000000000001"
    private const val EXECUTOR_AGENT_ID = "pe-executor-d4e5f6a7-0001-0001-0001-000000000002"
    private const val SYNTHESIZER_AGENT_ID = "pe-synth-d4e5f6a7-0001-0001-0001-000000000003"

    /** Small budgets keep the demo to a handful of LLM calls while still exercising replanning. */
    private const val MAX_STEPS = 3
    private const val MAX_REPLANS = 1

    private val TOOL_CATALOGUE =
        listOf(
            "list_directory(relativePath): sub-directories and files directly under a directory of the repository",
            "read_file_head(relativePath, maxLines): first lines of a text file",
            "count_files(relativePath, suffix): recursive count of files whose name ends with a suffix, with sample paths",
        )

    private val LOG = LoggerFactory.getLogger(Runner::class.java)

    fun run(args: Array<String>) =
        runBlocking {
            val repoPath = Path.of(args.getOrElse(0) { "." })
            val goal =
                args.getOrElse(1) {
                    """
                    Write an onboarding brief for a new contributor to this repository: which Gradle modules exist and
                    what each is for (from the top-level README), how many Kotlin source and test files there are, and
                    the exact command to build everything.
                    """.trimIndent()
                }

            LOG.info("=== Plan-and-Execute Pattern ===")
            LOG.info("Repository: {}", repoPath)
            LOG.info("Goal: {}", goal)

            val process =
                PlanAndExecute(
                    planner = PlannerAgenticFunction(PLANNER_AGENT_ID),
                    executor =
                        ExecutorAgenticFunction(
                            agentId = EXECUTOR_AGENT_ID,
                            toolsProvider = PatternAgents.inProcessTools("workspace", WorkspaceMcpServer(repoPath)),
                        ),
                    synthesizer = SynthesizerAgenticFunction(SYNTHESIZER_AGENT_ID),
                    toolCatalogue = TOOL_CATALOGUE,
                    maxSteps = MAX_STEPS,
                    maxReplans = MAX_REPLANS,
                )

            val outcome = process.run(goal)

            LOG.info("\n=== Plans issued: {} | Steps executed: {} ===", outcome.plans.size, outcome.results.size)
            outcome.results.forEach { LOG.info("  step {} [{}] {}", it.stepId, it.status, it.summary) }
            when (outcome) {
                is PlanAndExecute.Outcome.Completed -> {
                    LOG.info("\n=== Deliverable ===\n{}", outcome.answer)
                    if (outcome.gaps.isNotEmpty()) LOG.info("Gaps: {}", outcome.gaps)
                }
                is PlanAndExecute.Outcome.Abandoned -> LOG.error("\n=== Abandoned: {} ===", outcome.reason)
            }
        }
}

fun main(args: Array<String>) {
    Runner.run(args)
}
