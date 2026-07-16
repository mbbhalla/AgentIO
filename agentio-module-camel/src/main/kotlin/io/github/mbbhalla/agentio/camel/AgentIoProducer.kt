package io.github.mbbhalla.agentio.camel

import io.github.mbbhalla.agentio.core.lib.AbstractAgenticFunction
import io.github.mbbhalla.agentio.core.lib.Instructible
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.apache.camel.AsyncCallback
import org.apache.camel.Exchange
import org.apache.camel.support.DefaultAsyncProducer
import org.slf4j.LoggerFactory
import kotlin.coroutines.cancellation.CancellationException

/**
 * Bridges Camel's async producer SPI to an AgentIO [Instructible.invoke] (a `suspend` function).
 *
 * Design goals — keep the Camel edge seamless with AgentIO core's concurrency model:
 *
 * 1. Non-blocking: [process] returns `false` and completes the [AsyncCallback] from a coroutine,
 *    so a minutes-long agent turn never pins a Camel route thread. This preserves the coroutine
 *    model core is built on; a `runBlocking` producer would cap throughput at the route pool size.
 * 2. Bounded concurrency: a [Semaphore] caps how many agent invocations run at once, so a burst
 *    cannot fan out into unbounded concurrent (expensive) conversations. This bounds concurrency
 *    only, not queue depth: [process] returns immediately, so Camel applies no upstream
 *    backpressure and a backlog still parks exchanges on the permit — bound memory upstream (see
 *    [AgentIoEndpoint.maxConcurrency]).
 * 3. Failure as value: a core `Result.failure` becomes an exchange exception, so Camel's
 *    redelivery / dead-letter / circuit-breaker machinery owns retry and fallback declaratively.
 * 4. Cancellation-correct: the coroutine scope is tied to producer lifecycle; on stop it is
 *    cancelled to abort in-flight agents, and [CancellationException] is propagated (never
 *    swallowed into a failure Result) so shutdown does not look like a retryable error.
 * 5. Thread-safe: the [Instructible] is invoked as a stateless function; the shared [Semaphore]
 *    and per-exchange state are the only concurrency-sensitive pieces, and both are safe.
 */
class AgentIoProducer(
    private val endpoint: AgentIoEndpoint,
) : DefaultAsyncProducer(endpoint) {
    private val gate = Semaphore(endpoint.maxConcurrency)

    /**
     * Producer-scoped coroutine scope. SupervisorJob so one failing invocation does not cancel
     * siblings; Dispatchers.IO because agent invocations are I/O-bound (Bedrock, MCP tools).
     * Initialized in [doStart] and cancelled in [doStop] for graceful shutdown.
     */
    @Volatile
    private var scope: CoroutineScope? = null

    override fun doStart() {
        super.doStart()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }

    override fun doStop() {
        // Cancel in-flight invocations before the underlying producer tears down.
        scope?.cancel()
        scope = null
        super.doStop()
    }

    override fun process(
        exchange: Exchange,
        callback: AsyncCallback,
    ): Boolean {
        val activeScope = scope
        if (activeScope == null) {
            // Producer not started (or already stopped) — fail synchronously.
            exchange.setException(IllegalStateException("AgentIoProducer for '${endpoint.beanName}' is not started"))
            callback.done(true)
            return true
        }

        // Bind and validate the input synchronously so a mapping error fails fast, at the
        // boundary, before any coroutine is launched.
        val input: Instructible.WithInstruction
        try {
            input = endpoint.binding.toInput(exchange)
            validateInputType(input)
        } catch (e: IllegalArgumentException) {
            exchange.setException(e)
            callback.done(true)
            return true
        }

        activeScope.launch {
            try {
                gate.withPermit {
                    endpoint.function.invoke(input).fold(
                        onSuccess = { endpoint.binding.fromOutput(it, exchange) },
                        onFailure = { exchange.setException(it) },
                    )
                }
            } catch (ce: CancellationException) {
                // Producer shutting down (or scope cancelled): surface as exchange exception and
                // rethrow so structured concurrency observes the cancellation.
                exchange.setException(ce)
                callback.done(false)
                throw ce
            } catch (t: Throwable) {
                // Defensive: invoke is contractually total (returns Result), but a binding
                // fromOutput or an Error must not leave the exchange uncompleted.
                exchange.setException(t)
                callback.done(false)
                return@launch
            }
            callback.done(false)
        }

        // false => this exchange is being processed asynchronously; do not block the route thread.
        return false
    }

    /**
     * When the resolved function is an AbstractAgenticFunction, verify the bound input is the
     * concrete type it expects. coreLogic serializes the input by its declared KClass, so a
     * mismatched subtype would otherwise fail cryptically deep inside invoke; catch it at the
     * boundary instead. Evaluators/composites do not expose an input class, so they are skipped.
     */
    private fun validateInputType(input: Instructible.WithInstruction) {
        val function = endpoint.function
        if (function is AbstractAgenticFunction<*, *>) {
            val expected = function.getInputKClass()
            require(expected.isInstance(input)) {
                "Binding for agentio:'${endpoint.beanName}' produced ${input.javaClass.name}, " +
                    "but the function expects ${expected.qualifiedName}."
            }
        }
    }

    companion object {
        @Suppress("unused")
        private val LOG = LoggerFactory.getLogger(AgentIoProducer::class.java)
    }
}
