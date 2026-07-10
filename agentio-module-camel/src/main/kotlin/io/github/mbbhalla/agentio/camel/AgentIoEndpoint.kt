package io.github.mbbhalla.agentio.camel

import io.github.mbbhalla.agentio.core.lib.AgentOutput
import io.github.mbbhalla.agentio.core.lib.Instructible
import org.apache.camel.Consumer
import org.apache.camel.Processor
import org.apache.camel.Producer
import org.apache.camel.support.DefaultEndpoint

/**
 * Endpoint for an `agentio:` URI. Holds the resolved [Instructible] plus the [ExchangeBinding],
 * and produces an [AgentIoProducer] on demand. Producer-only: [createConsumer] is unsupported
 * because agentic functions are invoked, not polled.
 */
class AgentIoEndpoint(
    uri: String,
    component: AgentIoComponent,
    /** Registry bean name of the agentic function — used in diagnostics and the concurrency check. */
    val beanName: String,
    val function: Instructible<Instructible.WithInstruction, Result<AgentOutput<Any>>>,
    val binding: ExchangeBinding,
) : DefaultEndpoint(uri, component) {
    /**
     * Upper bound on concurrent in-flight agent invocations for this endpoint. Agent calls are
     * long-running, memory-heavy and costly, so — unlike a cheap I/O endpoint — they need an
     * explicit ceiling to stop a queue backlog from launching unbounded concurrent conversations
     * (OOM / throttling / runaway cost). Must be >= 1. Configured via `?maxConcurrency=<n>`.
     */
    var maxConcurrency: Int = DEFAULT_MAX_CONCURRENCY

    override fun createProducer(): Producer = AgentIoProducer(this)

    override fun createConsumer(processor: Processor): Consumer =
        throw UnsupportedOperationException(
            "agentio endpoints are producer-only (to(\"agentio:...\")). Agentic functions are " +
                "invoked, not polled, so consuming from an agentio endpoint is not supported.",
        )

    override fun isSingleton(): Boolean = true

    companion object {
        const val DEFAULT_MAX_CONCURRENCY: Int = 4
    }
}
