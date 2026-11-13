package io.github.mbbhalla.agentio.module.patterns.goap.release

import io.github.mbbhalla.agentio.core.common.Description
import kotlinx.serialization.Serializable

/*
    Facts for the release-readiness domain. Each type is both a blackboard entry and, for the
    LLM-backed steps, the agentic function's typed output. The planner reasons only about which
    of these types exist; the values are consumed by actions.
 */

/** Initial fact: where the repository lives. */
@Serializable
data class RepoPath(
    val path: String,
)

/** Deterministically gathered repository metrics. */
@Serializable
data class RepoFacts(
    @field:Description("Repository directory name")
    val name: String,
    @field:Description("Gradle modules declared in settings.gradle.kts")
    val modules: List<String>,
    @field:Description("Number of Kotlin source files, tests included")
    val kotlinFileCount: Int,
    @field:Description("Number of Kotlin files under a src/test tree")
    val testFileCount: Int,
    @field:Description("Whether a top-level README exists")
    val hasReadme: Boolean,
    @field:Description("Most recent commit subjects, newest first")
    val recentCommits: List<String>,
)

enum class RiskLevel { LOW, MEDIUM, HIGH }

@Serializable
data class RiskAssessment(
    @field:Description("Overall release risk")
    val level: RiskLevel,
    @field:Description("Specific concerns backing the risk level")
    val concerns: List<String>,
    @field:Description("Which method produced this assessment")
    val assessedBy: String,
)

@Serializable
data class ReleaseNotes(
    @field:Description("One-line release headline")
    val headline: String,
    @field:Description("Three to six user-facing highlights derived from the recent commits")
    val highlights: List<String>,
)

enum class Decision { GO, GO_WITH_CONDITIONS, NO_GO }

/** Goal fact: the process ends once this exists on the blackboard. */
@Serializable
data class ReleaseDecision(
    @field:Description("The release decision")
    val decision: Decision,
    @field:Description("Why this decision follows from the risk assessment and release notes")
    val rationale: String,
    @field:Description("Conditions that must be met before shipping; empty for an unconditional GO or a NO_GO")
    val conditions: List<String>,
)
