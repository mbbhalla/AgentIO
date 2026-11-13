package io.github.mbbhalla.agentio.module.patterns.goap.release

import io.github.mbbhalla.agentio.module.patterns.goap.AgenticFunctionAction
import io.github.mbbhalla.agentio.module.patterns.goap.GoapAction
import io.github.mbbhalla.agentio.module.patterns.goap.goapAction
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.exists
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.streams.asSequence

/**
 * The action set for the release-readiness domain. Two actions produce [RiskAssessment]: a cheap
 * deterministic scan and an expensive LLM assessment. The planner picks the scan while it is
 * available and falls back to the LLM if the scan fails or is excluded, without any code here
 * saying so.
 */
internal object ReleaseActions {
    private const val RECENT_COMMIT_COUNT = 10
    private const val GIT_TIMEOUT_SECONDS = 30L

    /** Cost tiers: deterministic code is cheap, each LLM call is expensive. */
    private const val DETERMINISTIC_COST = 1
    private const val LLM_COST = 4

    /** Below this test-to-source ratio the heuristic scan flags elevated risk. */
    private const val LOW_TEST_RATIO = 0.15
    private const val MEDIUM_TEST_RATIO = 0.30

    private val RISKY_COMMIT_KEYWORDS = listOf("revert", "hotfix", "cve", "vulnerab", "breaking")

    fun all(
        agentIdPrefix: String,
        includeHeuristicScan: Boolean,
    ): List<GoapAction<*>> =
        buildList {
            add(collectRepoFacts)
            if (includeHeuristicScan) add(heuristicRiskScan)
            add(llmRiskAssessment("$agentIdPrefix-risk"))
            add(draftReleaseNotes("$agentIdPrefix-notes"))
            add(decideRelease("$agentIdPrefix-decision"))
        }

    val collectRepoFacts: GoapAction<RepoFacts> =
        goapAction(name = "collect_repo_facts", requires = setOf(RepoPath::class), cost = DETERMINISTIC_COST) { blackboard ->
            runCatching { collect(Path.of(blackboard.get<RepoPath>().path)) }
        }

    /** Deterministic risk scan. Declines (fails) when there is no commit history to judge, which hands the job to the LLM route. */
    val heuristicRiskScan: GoapAction<RiskAssessment> =
        goapAction(name = "heuristic_risk_scan", requires = setOf(RepoFacts::class), cost = DETERMINISTIC_COST) { blackboard ->
            val facts = blackboard.get<RepoFacts>()
            if (facts.recentCommits.isEmpty()) {
                Result.failure(IllegalStateException("No commit history available for a heuristic scan"))
            } else {
                Result.success(scan(facts))
            }
        }

    fun llmRiskAssessment(agentId: String): GoapAction<RiskAssessment> =
        AgenticFunctionAction(
            name = "llm_risk_assessment",
            requires = setOf(RepoFacts::class),
            produces = RiskAssessment::class,
            cost = LLM_COST,
            function = RiskAssessorAgenticFunction(agentId),
        ) { blackboard -> RiskAssessorAgenticFunction.Input(facts = blackboard.get<RepoFacts>()) }

    fun draftReleaseNotes(agentId: String): GoapAction<ReleaseNotes> =
        AgenticFunctionAction(
            name = "draft_release_notes",
            requires = setOf(RepoFacts::class),
            produces = ReleaseNotes::class,
            cost = LLM_COST,
            function = ReleaseNotesAgenticFunction(agentId),
        ) { blackboard -> ReleaseNotesAgenticFunction.Input(facts = blackboard.get<RepoFacts>()) }

    fun decideRelease(agentId: String): GoapAction<ReleaseDecision> =
        AgenticFunctionAction(
            name = "decide_release",
            requires = setOf(RiskAssessment::class, ReleaseNotes::class),
            produces = ReleaseDecision::class,
            cost = LLM_COST,
            function = ReleaseDecisionAgenticFunction(agentId),
        ) { blackboard ->
            ReleaseDecisionAgenticFunction.Input(
                risk = blackboard.get<RiskAssessment>(),
                notes = blackboard.get<ReleaseNotes>(),
            )
        }

    private fun collect(repo: Path): RepoFacts {
        require(Files.isDirectory(repo)) { "'$repo' is not a directory" }
        val kotlinFiles =
            Files.walk(repo).use { stream ->
                stream
                    .asSequence()
                    .filter { it.isRegularFile() && it.name.endsWith(".kt") }
                    .filter { path -> repo.relativize(path).none { segment -> segment.name == "build" || segment.name.startsWith(".") } }
                    .map { repo.relativize(it).toString() }
                    .toList()
            }
        return RepoFacts(
            name = repo.toAbsolutePath().normalize().name,
            modules = declaredModules(repo),
            kotlinFileCount = kotlinFiles.size,
            testFileCount = kotlinFiles.count { "/src/test/" in "/$it" },
            hasReadme = repo.resolve("README.md").exists(),
            recentCommits = recentCommits(repo),
        )
    }

    private fun declaredModules(repo: Path): List<String> {
        val settings = repo.resolve("settings.gradle.kts")
        if (!settings.exists()) return emptyList()
        return Regex("""include\("([^"]+)"\)""").findAll(settings.readText()).map { it.groupValues[1] }.toList()
    }

    private fun recentCommits(repo: Path): List<String> {
        val process =
            ProcessBuilder("git", "log", "--oneline", "-$RECENT_COMMIT_COUNT")
                .directory(repo.toFile())
                .redirectErrorStream(true)
                .start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        val finished = process.waitFor(GIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        if (!finished || process.exitValue() != 0) return emptyList()
        return output.lines().filter { it.isNotBlank() }.map { it.substringAfter(' ') }
    }

    private fun scan(facts: RepoFacts): RiskAssessment {
        val testRatio = if (facts.kotlinFileCount == 0) 0.0 else facts.testFileCount.toDouble() / facts.kotlinFileCount
        val riskyCommits =
            facts.recentCommits.filter { subject -> RISKY_COMMIT_KEYWORDS.any { keyword -> subject.lowercase().contains(keyword) } }
        val concerns =
            buildList {
                if (testRatio < LOW_TEST_RATIO) add("Test files are ${"%.0f".format(testRatio * 100)}% of Kotlin sources")
                if (!facts.hasReadme) add("No top-level README")
                riskyCommits.forEach { add("Recent commit signals risk: '$it'") }
            }
        val level =
            when {
                testRatio < LOW_TEST_RATIO || riskyCommits.size > 1 -> RiskLevel.HIGH
                testRatio < MEDIUM_TEST_RATIO || riskyCommits.isNotEmpty() || !facts.hasReadme -> RiskLevel.MEDIUM
                else -> RiskLevel.LOW
            }
        return RiskAssessment(level = level, concerns = concerns, assessedBy = "heuristic")
    }
}
