package io.github.mbbhalla.agentio.camel

import io.github.mbbhalla.agentio.core.lib.AgentOutput
import io.github.mbbhalla.agentio.core.lib.Instructible
import org.apache.camel.Exchange

/**
 * Maps between a Camel [Exchange] and the AgentIO boundary types at the two edges of an
 * `agentio:` endpoint.
 *
 * This is the one place a route's untyped/`Object` world meets AgentIO's typed
 * `f(Input) = Output` world. Binding is deliberately explicit and pluggable rather than
 * reflective: a custom binding is resolved from the Camel registry by name via the
 * `?binding=<beanName>` URI parameter, otherwise [Default] is used.
 *
 * Implementations MUST be thread-safe and stateless: a single binding instance is shared across
 * all concurrent exchanges flowing through an endpoint.
 */
interface ExchangeBinding {
    /**
     * Produce the typed agentic-function input from the incoming exchange.
     *
     * The [Default] binding expects the message body to already be a
     * [Instructible.WithInstruction] (the typed mapping having been done upstream in Kotlin,
     * where types are real). Override to adapt a raw body — e.g. deserialize a JSON string
     * pulled off a queue — but keep the method pure and side-effect free.
     *
     * @throws IllegalArgumentException if the exchange cannot be mapped to a valid input. The
     *   producer surfaces this synchronously as an exchange exception, so the route's error
     *   handler owns it (fail fast at the boundary).
     */
    fun toInput(exchange: Exchange): Instructible.WithInstruction

    /**
     * Write a successful agent output back onto the exchange.
     *
     * The [Default] binding sets the out message body to [AgentOutput.output] (the typed result
     * value) and records observability data — instruction id, per-conversation token usage, and
     * the full [io.github.mbbhalla.agentio.core.model.conversation.Conversation] — as exchange
     * properties so downstream steps and dead-letter channels retain the agent's transcript.
     */
    fun fromOutput(
        output: AgentOutput<*>,
        exchange: Exchange,
    )

    companion object {
        /** Exchange property keys written by [Default]. Namespaced to avoid collisions. */
        const val PROPERTY_INSTRUCTION_ID: String = "AgentIo.instructionId"
        const val PROPERTY_INPUT_TOKENS: String = "AgentIo.inputTokens"
        const val PROPERTY_OUTPUT_TOKENS: String = "AgentIo.outputTokens"
        const val PROPERTY_CONVERSATION: String = "AgentIo.conversation"

        /**
         * Identity-in, value-out binding. Body must already be a [Instructible.WithInstruction];
         * on success the body becomes the typed output value and token/transcript data is stashed
         * in exchange properties. This is the correct default because AgentIO's whole premise is
         * that inputs are typed — mapping raw payloads to typed inputs belongs upstream in a
         * `.process {}` step, not in a reflective seam here.
         */
        val Default: ExchangeBinding = DefaultExchangeBinding
    }
}

/** @see ExchangeBinding.Default */
private object DefaultExchangeBinding : ExchangeBinding {
    override fun toInput(exchange: Exchange): Instructible.WithInstruction {
        val body = exchange.message.getBody(Any::class.java)
        require(body is Instructible.WithInstruction) {
            "agentio endpoint requires the message body to be an Instructible.WithInstruction, " +
                "but was ${body?.javaClass?.name ?: "null"}. Map the body to a " +
                "typed agentic-function input upstream (e.g. in a .process {} step), or configure " +
                "a custom ExchangeBinding via ?binding=<beanName>."
        }
        return body
    }

    override fun fromOutput(
        output: AgentOutput<*>,
        exchange: Exchange,
    ) {
        val tokenUsage = output.conversation.tokenUsage
        exchange.message.body = output.output
        exchange.setProperty(ExchangeBinding.PROPERTY_INSTRUCTION_ID, output.instructionId)
        exchange.setProperty(ExchangeBinding.PROPERTY_INPUT_TOKENS, tokenUsage.totalInputTokens)
        exchange.setProperty(ExchangeBinding.PROPERTY_OUTPUT_TOKENS, tokenUsage.totalOutputTokens)
        exchange.setProperty(ExchangeBinding.PROPERTY_CONVERSATION, output.conversation)
    }
}
