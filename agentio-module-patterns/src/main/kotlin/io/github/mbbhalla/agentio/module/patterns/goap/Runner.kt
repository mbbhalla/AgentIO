package io.github.mbbhalla.agentio.module.patterns.goap

import io.github.mbbhalla.agentio.core.common.JsonSchemaUtil
import io.github.mbbhalla.agentio.module.patterns.goap.release.ReleaseActions
import io.github.mbbhalla.agentio.module.patterns.goap.release.ReleaseDecision
import io.github.mbbhalla.agentio.module.patterns.goap.release.RepoPath
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory

/**
 * GOAP pattern: the goal is a `ReleaseDecision` fact; the planner derives the action sequence from
 * typed preconditions and effects, executes one action at a time, and replans after each.
 *
 * Arguments: repoPath, then an optional riskMode of `heuristic` (default; a deterministic scan
 * is available and the planner prefers it) or `llm` (the scan is excluded, so the planner routes
 * through the LLM assessor instead). Same goal, same code, different plan.
 */
internal object Runner {
    private const val AGENT_ID_PREFIX = "goap-c3d4e5f6-0001-0001-0001-000000000001"
    private const val RISK_MODE_HEURISTIC = "heuristic"
    private const val RISK_MODE_LLM = "llm"

    private val LOG = LoggerFactory.getLogger(Runner::class.java)

    fun run(args: Array<String>) =
        runBlocking {
            val repoPath = args.getOrElse(0) { "." }
            val riskMode = args.getOrElse(1) { RISK_MODE_HEURISTIC }
            require(
                riskMode == RISK_MODE_HEURISTIC || riskMode == RISK_MODE_LLM,
            ) { "riskMode must be '$RISK_MODE_HEURISTIC' or '$RISK_MODE_LLM'" }

            LOG.info("=== GOAP Pattern: Release Readiness ===")
            LOG.info("Repository: {} | risk mode: {}", repoPath, riskMode)

            val actions = ReleaseActions.all(agentIdPrefix = AGENT_ID_PREFIX, includeHeuristicScan = riskMode == RISK_MODE_HEURISTIC)
            LOG.info("Available actions:")
            actions.forEach { action ->
                LOG.info(
                    "  {} (cost={}) requires {} produces {}",
                    action.name,
                    action.cost,
                    action.requires.map { it.simpleName },
                    action.produces.simpleName,
                )
            }

            val outcome = GoapAgentProcess(actions).run(initial = Blackboard.of(RepoPath(repoPath)), goal = ReleaseDecision::class)

            LOG.info("\n=== Execution history ===")
            outcome.history.forEachIndexed { index, step ->
                LOG.info("  {}. {} -> {}", index + 1, step.action.name, if (step.succeeded) "ok" else "failed: ${step.error?.message}")
            }

            when (outcome) {
                is GoapAgentProcess.Outcome.Achieved -> {
                    val decision = outcome.blackboard.get<ReleaseDecision>()
                    LOG.info("\n=== Goal achieved: {} ===", decision.decision)
                    LOG.info(JsonSchemaUtil.json.encodeToString(ReleaseDecision.serializer(), decision))
                }
                is GoapAgentProcess.Outcome.Stuck ->
                    LOG.error("\n=== Process stuck: {} ===", outcome.reason)
            }
        }
}

fun main(args: Array<String>) {
    Runner.run(args)
}
