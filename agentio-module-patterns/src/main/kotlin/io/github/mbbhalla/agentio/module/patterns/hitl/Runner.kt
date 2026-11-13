package io.github.mbbhalla.agentio.module.patterns.hitl

import io.github.mbbhalla.agentio.module.patterns.common.PatternAgents
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory

/**
 * Human-in-the-loop pattern. Argument: `scripted` (default) or `interactive`. Scripted mode replays
 * a fixed reviewer (one revision request, then approval) so the run needs no terminal; interactive
 * mode reads the reviewer's decision from standard input.
 */
internal object Runner {
    private const val ADVISOR_AGENT_ID = "hitl-advisor-b8c9d0e1-0001-0001-0001-000000000001"
    private const val MAX_ROUNDS = 3
    private const val MODE_SCRIPTED = "scripted"
    private const val MODE_INTERACTIVE = "interactive"

    private val SCRIPTED_REVIEWER =
        ScriptedApprovalGate(
            listOf(
                ReviewDecision.Revise(
                    "Leave ci-runner unchanged: build load spikes at release time and the 30-day average hides it. " +
                        "Also no terminations this quarter, downsizing only.",
                ),
                ReviewDecision.Approve,
            ),
        )

    private val LOG = LoggerFactory.getLogger(Runner::class.java)

    fun run(args: Array<String>) =
        runBlocking {
            val mode = args.getOrElse(0) { MODE_SCRIPTED }
            val gate =
                when (mode) {
                    MODE_SCRIPTED -> SCRIPTED_REVIEWER
                    MODE_INTERACTIVE -> ConsoleApprovalGate()
                    else -> throw IllegalArgumentException("mode must be '$MODE_SCRIPTED' or '$MODE_INTERACTIVE'")
                }
            val fleet = Fleet.SAMPLE

            LOG.info("=== Human-in-the-Loop Pattern: Fleet Right-sizing ({} reviewer) ===", mode)
            LOG.info("Fleet before: {} resources, ${'$'}{}/month", fleet.resources.size, fleet.monthlyCostUsd)

            val loop =
                HumanInTheLoop(
                    advisor =
                        RightsizingAdvisorAgenticFunction(
                            agentId = ADVISOR_AGENT_ID,
                            toolsProvider = PatternAgents.inProcessTools("fleet", FleetMcpServer(fleet)),
                        ),
                    gate = gate,
                    maxRounds = MAX_ROUNDS,
                )

            val outcome = loop.run(fleet)

            LOG.info("\n=== {} review round(s) ===", outcome.rounds.size)
            when (outcome) {
                is HumanInTheLoop.Outcome.Applied -> {
                    LOG.info(
                        "Applied. Fleet after: {} resources, ${'$'}{}/month",
                        outcome.after.resources.size,
                        outcome.after.monthlyCostUsd,
                    )
                    LOG.info("Monthly saving: ${'$'}{}", outcome.before.monthlyCostUsd - outcome.after.monthlyCostUsd)
                    outcome.after.resources.forEach { LOG.info("  {} {} {}", it.id, it.size, it.workload) }
                }
                is HumanInTheLoop.Outcome.Rejected -> LOG.info("Rejected, fleet unchanged: {}", outcome.reason)
                is HumanInTheLoop.Outcome.Failed -> LOG.error("Failed, fleet unchanged: {}", outcome.reason)
            }
        }
}

fun main(args: Array<String>) {
    Runner.run(args)
}
