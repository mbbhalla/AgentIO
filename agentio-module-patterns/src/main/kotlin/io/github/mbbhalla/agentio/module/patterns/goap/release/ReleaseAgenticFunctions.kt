package io.github.mbbhalla.agentio.module.patterns.goap.release

import io.github.mbbhalla.agentio.core.common.Description
import io.github.mbbhalla.agentio.core.lib.AbstractAgenticFunction
import io.github.mbbhalla.agentio.core.lib.Instructible
import io.github.mbbhalla.agentio.module.patterns.common.PatternAgents
import kotlinx.serialization.Serializable

private const val EVENT_TAG = "GOAP"

/** LLM route to a [RiskAssessment]; the planner only picks it when the cheaper heuristic scan is unavailable. */
internal class RiskAssessorAgenticFunction(
    agentId: String,
) : AbstractAgenticFunction<RiskAssessorAgenticFunction.Input, RiskAssessment>(
        PatternAgents.configuration(
            agentId = agentId,
            problemDomain = "Software Release Risk",
            systemPrompt = "You are a release manager who judges shipping risk from repository signals. Be specific and concise.",
            eventListenerTag = EVENT_TAG,
        ),
    ) {
    @Serializable
    data class Input(
        @field:Description("Repository facts to assess")
        val facts: RepoFacts,
    ) : Instructible.WithInstruction {
        override fun instructionId() = "goap-risk-${facts.name}"

        override fun instruction() =
            """
            Assess the release risk of the repository described in the input. Weigh test coverage
            (test files versus total Kotlin files), documentation, and the nature of the recent commits.
            Set assessedBy to "llm".
            """.trimIndent()

        override fun systemInstruction(): String? = null
    }

    override fun getInputKClass() = Input::class

    override fun getOutputKClass() = RiskAssessment::class
}

internal class ReleaseNotesAgenticFunction(
    agentId: String,
) : AbstractAgenticFunction<ReleaseNotesAgenticFunction.Input, ReleaseNotes>(
        PatternAgents.configuration(
            agentId = agentId,
            problemDomain = "Release Communication",
            systemPrompt = "You write crisp, user-facing release notes from commit history. No marketing language.",
            eventListenerTag = EVENT_TAG,
        ),
    ) {
    @Serializable
    data class Input(
        @field:Description("Repository facts, including recent commit subjects")
        val facts: RepoFacts,
    ) : Instructible.WithInstruction {
        override fun instructionId() = "goap-notes-${facts.name}"

        override fun instruction() =
            """
            Draft release notes for '${facts.name}' from the recent commits in the input. Group related
            commits into highlights a user would care about; drop pure housekeeping.
            """.trimIndent()

        override fun systemInstruction(): String? = null
    }

    override fun getInputKClass() = Input::class

    override fun getOutputKClass() = ReleaseNotes::class
}

internal class ReleaseDecisionAgenticFunction(
    agentId: String,
) : AbstractAgenticFunction<ReleaseDecisionAgenticFunction.Input, ReleaseDecision>(
        PatternAgents.configuration(
            agentId = agentId,
            problemDomain = "Release Management",
            systemPrompt = "You make go/no-go release calls. Tie every decision to the evidence you were given.",
            eventListenerTag = EVENT_TAG,
        ),
    ) {
    @Serializable
    data class Input(
        @field:Description("Risk assessment for this release")
        val risk: RiskAssessment,
        @field:Description("Draft release notes for this release")
        val notes: ReleaseNotes,
    ) : Instructible.WithInstruction {
        override fun instructionId() = "goap-decision-${risk.level}"

        override fun instruction() =
            """
            Decide whether to ship. HIGH risk is NO_GO unless the concerns are trivially fixable, in which case
            GO_WITH_CONDITIONS with the fixes as conditions. MEDIUM risk is GO_WITH_CONDITIONS. LOW risk is GO.
            """.trimIndent()

        override fun systemInstruction(): String? = null
    }

    override fun getInputKClass() = Input::class

    override fun getOutputKClass() = ReleaseDecision::class
}
