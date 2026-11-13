package io.github.mbbhalla.agentio.module.patterns.routing

import io.github.mbbhalla.agentio.core.common.Description
import io.github.mbbhalla.agentio.core.lib.AbstractAgenticFunction
import io.github.mbbhalla.agentio.core.lib.Instructible
import io.github.mbbhalla.agentio.core.lib.tool.EmptyToolsProvider
import io.github.mbbhalla.agentio.core.lib.tool.ToolsProvider
import io.github.mbbhalla.agentio.module.patterns.common.PatternAgents
import kotlinx.serialization.Serializable

private const val EVENT_TAG = "ROUTING"

/** The closed set of destinations. The router must pick one; the schema enforces it. */
enum class Route(
    val description: String,
) {
    REPOSITORY("Questions about this code repository: its modules, files, structure, build, or contents"),
    DATES("Calendar arithmetic: days between dates, weekdays, adding or subtracting days"),
    WRITING("Drafting, rewriting, or editing prose such as emails, summaries, or announcements"),
    UNSUPPORTED("Anything outside the other routes, or requests that are unsafe or too vague to act on"),
}

/** Classifies a request. Cheap by design: no tools, low temperature, one short call. */
internal class RouterAgenticFunction(
    agentId: String,
) : AbstractAgenticFunction<RouterAgenticFunction.Input, RouterAgenticFunction.Output>(
        PatternAgents.configuration(
            agentId = agentId,
            problemDomain = "Request Classification",
            systemPrompt = "You are a dispatcher. Classify requests into exactly one route; when unsure, prefer UNSUPPORTED and say why.",
            languageModelParameters = PatternAgents.languageModelParameters(temperature = 0.0f),
            eventListenerTag = EVENT_TAG,
        ),
    ) {
    @Serializable
    data class Input(
        @field:Description("The user's request to classify")
        val request: String,
        @field:Description("Available routes with their descriptions")
        val routes: List<String>,
    ) : Instructible.WithInstruction {
        override fun instructionId() = "route-${request.hashCode()}"

        override fun instruction() = "Classify the request into one of the routes listed in the input and explain the choice briefly."

        override fun systemInstruction(): String? = null
    }

    @Serializable
    data class Output(
        @field:Description("The chosen route")
        val route: Route,
        @field:Description("One sentence on why this route fits")
        val rationale: String,
        @field:Description("Confidence in the classification from 0.0 to 1.0")
        val confidence: Double,
    )

    override fun getInputKClass() = Input::class

    override fun getOutputKClass() = Output::class
}

/** One specialist per route: same input and output shape, different expertise and tools. */
internal class SpecialistAgenticFunction(
    agentId: String,
    problemDomain: String,
    systemPrompt: String,
    toolsProvider: ToolsProvider = EmptyToolsProvider,
) : AbstractAgenticFunction<SpecialistAgenticFunction.Input, SpecialistAgenticFunction.Output>(
        PatternAgents.configuration(
            agentId = agentId,
            problemDomain = problemDomain,
            systemPrompt = systemPrompt,
            toolsProvider = toolsProvider,
            eventListenerTag = EVENT_TAG,
        ),
    ) {
    @Serializable
    data class Input(
        @field:Description("The user's request")
        val request: String,
    ) : Instructible.WithInstruction {
        override fun instructionId() = "specialist-${request.hashCode()}"

        override fun instruction() = "Answer the request: '$request'"

        override fun systemInstruction(): String? = null
    }

    @Serializable
    data class Output(
        @field:Description("The answer to the request")
        val answer: String,
    )

    override fun getInputKClass() = Input::class

    override fun getOutputKClass() = Output::class
}
