package io.github.mbbhalla.agentio.module.patterns.routing

import io.github.mbbhalla.agentio.module.patterns.common.PatternAgents
import io.github.mbbhalla.agentio.module.patterns.common.WorkspaceMcpServer
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import java.nio.file.Path

/**
 * Routing pattern. Arguments: repoPath, then zero or more requests. Each request is classified once and
 * handled by exactly one specialist; without requests a fixed set covering every route is used.
 */
internal object Runner {
    private const val ROUTER_AGENT_ID = "route-router-e5f6a7b8-0001-0001-0001-000000000001"
    private const val REPO_AGENT_ID = "route-repo-e5f6a7b8-0001-0001-0001-000000000002"
    private const val DATES_AGENT_ID = "route-dates-e5f6a7b8-0001-0001-0001-000000000003"
    private const val WRITING_AGENT_ID = "route-writing-e5f6a7b8-0001-0001-0001-000000000004"

    private const val MIN_CONFIDENCE = 0.6

    private val DEFAULT_REQUESTS =
        listOf(
            "How many Kotlin test files does this repository have, and which module has the most?",
            "How many days are there between 2025-11-12 and 2026-03-01, and what weekday is the latter?",
            "Rewrite this for a status email in two sentences: we moved the adversarial and orchestration patterns into their own module so examples stay about domains.",
            "Transfer 500 dollars from my savings account to checking.",
        )

    private val LOG = LoggerFactory.getLogger(Runner::class.java)

    fun run(args: Array<String>) =
        runBlocking {
            val repoPath = Path.of(args.getOrElse(0) { "." })
            val requests = args.drop(1).ifEmpty { DEFAULT_REQUESTS }

            LOG.info("=== Routing Pattern ===")

            val router =
                RequestRouter(
                    router = RouterAgenticFunction(ROUTER_AGENT_ID),
                    specialists =
                        mapOf(
                            Route.REPOSITORY to
                                SpecialistAgenticFunction(
                                    agentId = REPO_AGENT_ID,
                                    problemDomain = "Repository Analysis",
                                    systemPrompt =
                                        "You answer questions about a code repository using the workspace tools. Cite the paths you looked at.",
                                    toolsProvider = PatternAgents.inProcessTools("workspace", WorkspaceMcpServer(repoPath)),
                                ),
                            Route.DATES to
                                SpecialistAgenticFunction(
                                    agentId = DATES_AGENT_ID,
                                    problemDomain = "Calendar Arithmetic",
                                    systemPrompt =
                                        "You answer date questions. Always compute with the date tools; never do calendar math in your head.",
                                    toolsProvider = PatternAgents.inProcessTools("dates", DateToolsMcpServer()),
                                ),
                            Route.WRITING to
                                SpecialistAgenticFunction(
                                    agentId = WRITING_AGENT_ID,
                                    problemDomain = "Business Writing",
                                    systemPrompt = "You write clear, plain workplace prose. Match the requested length exactly.",
                                ),
                        ),
                    minConfidence = MIN_CONFIDENCE,
                )

            requests.forEachIndexed { index, request ->
                LOG.info("\n--- Request {} of {} ---\n{}", index + 1, requests.size, request)
                router
                    .handle(request)
                    .onSuccess { outcome ->
                        when (outcome) {
                            is RequestRouter.Outcome.Answered ->
                                LOG.info("[{} @ {}] {}", outcome.route, outcome.confidence, outcome.answer)
                            is RequestRouter.Outcome.Declined ->
                                LOG.info("[{} @ {}] Declined: {}", outcome.route, outcome.confidence, outcome.reason)
                        }
                    }.onFailure { error -> LOG.error("Routing failed: {}", error.message) }
            }
        }
}

fun main(args: Array<String>) {
    Runner.run(args)
}
