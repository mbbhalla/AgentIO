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
     * Upper bound on concurrent in-flight agent *invocations* for this endpoint. Agent calls are
     * long-running and costly (Bedrock spend, provider throttling), so — unlike a cheap I/O
     * endpoint — they get an explicit ceiling on how many run at once. Configured via
     * `?maxConcurrency=<n>`; must be >= 1 (enforced at route start in [doInit]).
     *
     * Note this bounds *concurrency*, not queue depth or memory: [AgentIoProducer.process] returns
     * immediately for every exchange, so Camel applies no upstream backpressure and an inbound
     * backlog still parks unbounded exchanges on the permit. To bound memory/queue depth, throttle
     * upstream (a bounded SEDA/thread pool or the `throttle` EIP) rather than relying on this cap.
     */
    var maxConcurrency: Int = DEFAULT_MAX_CONCURRENCY

    override fun doInit() {
        super.doInit()
        // Fail fast at route start with a clear, endpoint-scoped message. Without this the invalid
        // value only surfaces later as a cryptic Semaphore constructor error at producer start.
        require(maxConcurrency >= MIN_MAX_CONCURRENCY) {
            "agentio:$beanName maxConcurrency must be >= $MIN_MAX_CONCURRENCY, but was $maxConcurrency"
        }
    }

    override fun createProducer(): Producer = AgentIoProducer(this)

    override fun createConsumer(processor: Processor): Consumer =
        throw UnsupportedOperationException(
            "agentio endpoints are producer-only (to(\"agentio:...\")). Agentic functions are " +
                "invoked, not polled, so consuming from an agentio endpoint is not supported.",
        )

    override fun isSingleton(): Boolean = true

    companion object {
        const val DEFAULT_MAX_CONCURRENCY: Int = 4

        /** Smallest legal [maxConcurrency]; a value below this cannot admit any invocation. */
        const val MIN_MAX_CONCURRENCY: Int = 1
    }
}
