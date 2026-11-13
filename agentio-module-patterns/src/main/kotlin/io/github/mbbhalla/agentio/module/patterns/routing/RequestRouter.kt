package io.github.mbbhalla.agentio.module.patterns.routing

import org.slf4j.LoggerFactory

/**
 * Routing pattern: a cheap classifier picks exactly one specialist, and only that specialist
 * runs. Unlike orchestration (fan out to every worker) a request costs one classification plus
 * one specialist call. A low-confidence classification or an [Route.UNSUPPORTED] verdict is
 * declined without spending a specialist call.
 */
internal class RequestRouter(
    private val router: RouterAgenticFunction,
    private val specialists: Map<Route, SpecialistAgenticFunction>,
    /** Classifications below this confidence are declined rather than dispatched. */
    private val minConfidence: Double,
) {
    companion object {
        private val LOG = LoggerFactory.getLogger(RequestRouter::class.java)
    }

    init {
        val missing = Route.entries.filter { it != Route.UNSUPPORTED && it !in specialists }
        require(missing.isEmpty()) { "No specialist registered for routes: $missing" }
    }

    sealed interface Outcome {
        val route: Route
        val confidence: Double

        data class Answered(
            override val route: Route,
            override val confidence: Double,
            val answer: String,
        ) : Outcome

        data class Declined(
            override val route: Route,
            override val confidence: Double,
            val reason: String,
        ) : Outcome
    }

    suspend fun handle(request: String): Result<Outcome> =
        router
            .invoke(RouterAgenticFunction.Input(request = request, routes = Route.entries.map { "${it.name}: ${it.description}" }))
            .map { it.output }
            .mapCatching { classification ->
                LOG.info(
                    "[ROUTING] '{}' -> {} ({}): {}",
                    request.take(60),
                    classification.route,
                    classification.confidence,
                    classification.rationale,
                )
                dispatch(request, classification)
            }

    private suspend fun dispatch(
        request: String,
        classification: RouterAgenticFunction.Output,
    ): Outcome {
        val route = classification.route
        val confidence = classification.confidence
        if (route == Route.UNSUPPORTED) return Outcome.Declined(route, confidence, classification.rationale)
        if (confidence < minConfidence) return Outcome.Declined(route, confidence, "Confidence $confidence below threshold $minConfidence")

        val specialist = specialists.getValue(route)
        return specialist
            .invoke(SpecialistAgenticFunction.Input(request = request))
            .map { Outcome.Answered(route, confidence, it.output.answer) as Outcome }
            .getOrElse { error -> Outcome.Declined(route, confidence, "Specialist failed: ${error.message}") }
    }
}
