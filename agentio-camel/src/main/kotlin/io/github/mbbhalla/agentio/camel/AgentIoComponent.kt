package io.github.mbbhalla.agentio.camel

import io.github.mbbhalla.agentio.core.lib.AgentOutput
import io.github.mbbhalla.agentio.core.lib.Instructible
import org.apache.camel.CamelContext
import org.apache.camel.Endpoint
import org.apache.camel.support.DefaultComponent

/**
 * Camel component for the `agentio:` scheme. Resolves an [Instructible] bean from the Camel
 * registry by name and exposes it as a producer endpoint, so an agentic function can be a node
 * in a route: `to("agentio:<beanName>")`.
 *
 * The endpoint binds to [Instructible] — not to a concrete function type — so a plain
 * agentic function, an `AgenticFunctionEvaluator` (multi-trial voting), and any composite are
 * all interchangeable behind the same URI. Producer-only by design: agents are invoked, not
 * polled.
 *
 * URI: `agentio:<registryBeanName>[?maxConcurrency=<n>][&binding=<bindingBeanName>]`
 */
class AgentIoComponent : DefaultComponent() {
    override fun createEndpoint(
        uri: String,
        remaining: String,
        parameters: MutableMap<String, Any>,
    ): Endpoint {
        require(remaining.isNotBlank()) {
            "agentio endpoint requires a registry bean name: agentio:<beanName>"
        }

        val function = resolveFunction(remaining)
        val binding = resolveBinding(parameters)

        val endpoint = AgentIoEndpoint(uri, this, remaining, function, binding)
        // Let Camel bind remaining URI query params (e.g. maxConcurrency) onto the endpoint.
        setProperties(endpoint, parameters)
        return endpoint
    }

    /**
     * Look up the agentic function in the registry. Both AbstractAgenticFunction and
     * AgenticFunctionEvaluator implement Instructible<*, Result<AgentOutput<*>>>; the unchecked
     * cast is sound because that return shape is fixed by the core API. A wrong-typed or missing
     * bean fails fast here, at route start, rather than at first message.
     */
    private fun resolveFunction(beanName: String): Instructible<Instructible.WithInstruction, Result<AgentOutput<Any>>> {
        // Camel always sets the context before createEndpoint is invoked; narrow the nullable
        // accessor to non-null and fail loudly if that invariant is ever violated.
        val context: CamelContext = requireNotNull(camelContext) { "CamelContext is not available on this component" }
        val bean =
            context.registry.lookupByNameAndType(beanName, Instructible::class.java)
                ?: throw IllegalArgumentException(
                    "No Instructible bean named '$beanName' found in the Camel registry. " +
                        "Register the agentic function (or evaluator) under this name.",
                )

        @Suppress("UNCHECKED_CAST")
        return bean as Instructible<Instructible.WithInstruction, Result<AgentOutput<Any>>>
    }

    /**
     * Resolve the ExchangeBinding. Consumes the `binding` query param if present (removing it so
     * Camel does not later reject it as an unknown endpoint property); otherwise uses the
     * identity/value default.
     */
    private fun resolveBinding(parameters: MutableMap<String, Any>): ExchangeBinding {
        val bindingBeanName = parameters.remove("binding") ?: return ExchangeBinding.Default
        val context: CamelContext = requireNotNull(camelContext) { "CamelContext is not available on this component" }
        return context.registry.lookupByNameAndType(bindingBeanName.toString(), ExchangeBinding::class.java)
            ?: throw IllegalArgumentException(
                "No ExchangeBinding bean named '$bindingBeanName' found in the Camel registry.",
            )
    }
}
