package io.github.mbbhalla.agentio.camel

import io.github.mbbhalla.agentio.core.lib.AbstractAgenticFunction
import io.github.mbbhalla.agentio.core.lib.AgentOutput
import io.github.mbbhalla.agentio.core.lib.Instructible
import io.github.mbbhalla.agentio.core.model.AgentConfiguration
import io.github.mbbhalla.agentio.core.model.conversation.AgentTokenUsage
import io.github.mbbhalla.agentio.core.model.conversation.Conversation
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import org.apache.camel.CamelExecutionException
import org.apache.camel.Exchange
import org.apache.camel.builder.RouteBuilder
import org.apache.camel.impl.DefaultCamelContext
import org.apache.camel.support.SimpleRegistry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.seconds

/**
 * Exercises the agentio Camel component end to end through a real [DefaultCamelContext] and
 * [org.apache.camel.ProducerTemplate], plus targeted unit checks on the component/endpoint edges.
 * Fake [Instructible]s stand in for real agents so the tests are deterministic and offline.
 */
internal class AgentIoComponentTest {
    // ---- Fixtures -------------------------------------------------------------------------

    data class SampleInput(
        val text: String,
    ) : Instructible.WithInstruction {
        override fun instructionId(): String = "sample-instruction"

        override fun instruction(): String = "process: $text"

        override fun systemInstruction(): String? = null
    }

    data class SampleOutput(
        val value: String,
    )

    /** Another input type, used to prove the boundary type-check rejects a mismatch. */
    data class OtherInput(
        val n: Int,
    ) : Instructible.WithInstruction {
        override fun instructionId(): String = "other"

        override fun instruction(): String = "n=$n"

        override fun systemInstruction(): String? = null
    }

    private fun conversationWith(
        inputTokens: Int,
        outputTokens: Int,
    ): Conversation =
        Conversation.initialize(listOf("seed")).copy(
            tokenUsage =
                AgentTokenUsage(
                    totalInputTokens = inputTokens,
                    totalOutputTokens = outputTokens,
                    lastTurnInputTokens = inputTokens,
                    lastTurnOutputTokens = outputTokens,
                    lastTurnTotalTokens = inputTokens + outputTokens,
                ),
        )

    private fun successOutput(value: String): AgentOutput<Any> =
        AgentOutput(
            instructionId = "sample-instruction",
            conversation = conversationWith(inputTokens = 12, outputTokens = 7),
            output = SampleOutput(value),
        )

    /** A function that succeeds by transforming the input text. */
    private fun succeedingFunction(): Instructible<Instructible.WithInstruction, Result<AgentOutput<Any>>> =
        object : Instructible<Instructible.WithInstruction, Result<AgentOutput<Any>>> {
            override suspend fun invoke(input: Instructible.WithInstruction): Result<AgentOutput<Any>> {
                val text = (input as SampleInput).text
                return Result.success(successOutput("SQL(${text.uppercase()})"))
            }
        }

    /** A function that returns a failure Result (the core failure channel). */
    private fun failingFunction(error: Throwable): Instructible<Instructible.WithInstruction, Result<AgentOutput<Any>>> =
        object : Instructible<Instructible.WithInstruction, Result<AgentOutput<Any>>> {
            override suspend fun invoke(input: Instructible.WithInstruction): Result<AgentOutput<Any>> = Result.failure(error)
        }

    /**
     * A relaxed AgentConfiguration mock. The type-check test that uses it never reaches invoke
     * (binding validation fails first), so no behavior needs stubbing.
     */
    private fun mockAgentConfiguration(): AgentConfiguration = mockk(relaxed = true)

    private fun registryWith(vararg beans: Pair<String, Any>): SimpleRegistry =
        SimpleRegistry().apply { beans.forEach { (name, bean) -> bind(name, bean) } }

    /** Walks the cause chain of a thrown error looking for a given type. */
    private inline fun <reified T : Throwable> Throwable.hasCause(): Boolean = generateSequence(this) { it.cause }.any { it is T }

    private fun contextWith(registry: SimpleRegistry): DefaultCamelContext = DefaultCamelContext(registry)

    // ---- Success path ---------------------------------------------------------------------

    @Test
    fun `producer maps successful output to body and records observability properties`() {
        val ctx = contextWith(registryWith("fn" to succeedingFunction()))
        ctx.addRoutes(
            object : RouteBuilder() {
                override fun configure() {
                    from("direct:in").to("agentio:fn").to("mock:out")
                }
            },
        )
        ctx.start()
        try {
            val exchange =
                ctx.createProducerTemplate().request("direct:in") {
                    it.message.body = SampleInput("select something")
                }

            assertFalse(exchange.isFailed, "exchange should not be failed")
            assertEquals(SampleOutput("SQL(SELECT SOMETHING)"), exchange.message.body)
            assertEquals("sample-instruction", exchange.getProperty(ExchangeBinding.PROPERTY_INSTRUCTION_ID))
            assertEquals(12, exchange.getProperty(ExchangeBinding.PROPERTY_INPUT_TOKENS))
            assertEquals(7, exchange.getProperty(ExchangeBinding.PROPERTY_OUTPUT_TOKENS))
            assertInstanceOf(Conversation::class.java, exchange.getProperty(ExchangeBinding.PROPERTY_CONVERSATION))
        } finally {
            ctx.stop()
        }
    }

    // ---- Failure path ---------------------------------------------------------------------

    @Test
    fun `producer surfaces a Result failure as an exchange exception`() {
        val boom = IllegalStateException("agent failed")
        val ctx = contextWith(registryWith("fn" to failingFunction(boom)))
        ctx.addRoutes(
            object : RouteBuilder() {
                override fun configure() {
                    from("direct:in").to("agentio:fn")
                }
            },
        )
        ctx.start()
        try {
            val exchange =
                ctx.createProducerTemplate().request("direct:in") {
                    it.message.body = SampleInput("x")
                }

            assertTrue(exchange.isFailed)
            assertEquals(boom, exchange.exception)
        } finally {
            ctx.stop()
        }
    }

    @Test
    fun `route error handler can catch an agent failure for dead-letter or fallback`() {
        val ctx = contextWith(registryWith("fn" to failingFunction(RuntimeException("nope"))))
        ctx.addRoutes(
            object : RouteBuilder() {
                override fun configure() {
                    onException(RuntimeException::class.java)
                        .handled(true)
                        .setBody(constant("FALLBACK"))
                    from("direct:in").to("agentio:fn")
                }
            },
        )
        ctx.start()
        try {
            val body = ctx.createProducerTemplate().requestBody("direct:in", SampleInput("x"), String::class.java)
            assertEquals("FALLBACK", body)
        } finally {
            ctx.stop()
        }
    }

    // ---- Input binding / boundary validation ---------------------------------------------

    @Test
    fun `default binding rejects a non-Instructible body at the boundary`() {
        val ctx = contextWith(registryWith("fn" to succeedingFunction()))
        ctx.addRoutes(
            object : RouteBuilder() {
                override fun configure() {
                    from("direct:in").to("agentio:fn")
                }
            },
        )
        ctx.start()
        try {
            val exchange =
                ctx.createProducerTemplate().request("direct:in") {
                    it.message.body = "just a string, not a typed input"
                }
            assertTrue(exchange.isFailed)
            assertInstanceOf(IllegalArgumentException::class.java, exchange.exception)
        } finally {
            ctx.stop()
        }
    }

    @Test
    fun `default binding rejects a null body at the boundary`() {
        val ctx = contextWith(registryWith("fn" to succeedingFunction()))
        ctx.addRoutes(
            object : RouteBuilder() {
                override fun configure() {
                    from("direct:in").to("agentio:fn")
                }
            },
        )
        ctx.start()
        try {
            val exchange = ctx.createProducerTemplate().request("direct:in") { it.message.body = null }
            assertTrue(exchange.isFailed)
            assertInstanceOf(IllegalArgumentException::class.java, exchange.exception)
        } finally {
            ctx.stop()
        }
    }

    @Test
    fun `producer type-checks the input against an AbstractAgenticFunction input class`() {
        // A real AbstractAgenticFunction subtype (with a mocked config-free surface) declaring
        // SampleInput as its input class; feeding OtherInput must fail fast at the boundary.
        val typedFunction =
            object : AbstractAgenticFunction<SampleInput, SampleOutput>(mockAgentConfiguration()) {
                override fun getInputKClass() = SampleInput::class

                override fun getOutputKClass() = SampleOutput::class
            }
        val ctx = contextWith(registryWith("fn" to typedFunction))
        ctx.addRoutes(
            object : RouteBuilder() {
                override fun configure() {
                    from("direct:in").to("agentio:fn")
                }
            },
        )
        ctx.start()
        try {
            val exchange = ctx.createProducerTemplate().request("direct:in") { it.message.body = OtherInput(5) }
            assertTrue(exchange.isFailed)
            val ex = exchange.exception
            assertInstanceOf(IllegalArgumentException::class.java, ex)
            assertTrue(ex?.message?.contains("expects") == true)
        } finally {
            ctx.stop()
        }
    }

    // ---- Custom binding -------------------------------------------------------------------

    @Test
    fun `custom binding resolved from registry adapts a raw body`() {
        val rawToTyped =
            object : ExchangeBinding {
                override fun toInput(exchange: Exchange): Instructible.WithInstruction =
                    SampleInput(exchange.message.getBody(String::class.java) ?: "")

                override fun fromOutput(
                    output: AgentOutput<*>,
                    exchange: Exchange,
                ) {
                    exchange.message.body = (output.output as SampleOutput).value
                }
            }
        val ctx = contextWith(registryWith("fn" to succeedingFunction(), "raw" to rawToTyped))
        ctx.addRoutes(
            object : RouteBuilder() {
                override fun configure() {
                    from("direct:in").to("agentio:fn?binding=raw")
                }
            },
        )
        ctx.start()
        try {
            val body = ctx.createProducerTemplate().requestBody("direct:in", "hello", String::class.java)
            assertEquals("SQL(HELLO)", body)
        } finally {
            ctx.stop()
        }
    }

    @Test
    fun `unknown binding bean name fails route start`() {
        val ctx = contextWith(registryWith("fn" to succeedingFunction()))
        ctx.addRoutes(
            object : RouteBuilder() {
                override fun configure() {
                    from("direct:in").to("agentio:fn?binding=missing")
                }
            },
        )
        val thrown = assertThrows(Exception::class.java) { ctx.start() }
        assertTrue(thrown.hasCause<IllegalArgumentException>())
        ctx.stop()
    }

    // ---- Endpoint resolution errors -------------------------------------------------------

    @Test
    fun `unknown function bean name fails route start`() {
        val ctx = contextWith(registryWith("other" to succeedingFunction()))
        ctx.addRoutes(
            object : RouteBuilder() {
                override fun configure() {
                    from("direct:in").to("agentio:doesNotExist")
                }
            },
        )
        val thrown = assertThrows(Exception::class.java) { ctx.start() }
        assertTrue(thrown.hasCause<IllegalArgumentException>())
        ctx.stop()
    }

    @Test
    fun `blank bean name is rejected`() {
        // A resolved-but-empty remaining (agentio:?foo=bar) reaches createEndpoint with a blank
        // bean name; the component rejects it. Route start surfaces it wrapped in the cause chain.
        val ctx = contextWith(registryWith("fn" to succeedingFunction()))
        ctx.addRoutes(
            object : RouteBuilder() {
                override fun configure() {
                    from("direct:in").to("agentio:?maxConcurrency=1")
                }
            },
        )
        val thrown = assertThrows(Exception::class.java) { ctx.start() }
        assertTrue(thrown.hasCause<IllegalArgumentException>())
        ctx.stop()
    }

    @Test
    fun `consumer creation is unsupported`() {
        val ctx = contextWith(registryWith("fn" to succeedingFunction()))
        ctx.start()
        try {
            val endpoint = ctx.getEndpoint("agentio:fn") as AgentIoEndpoint
            assertThrows(UnsupportedOperationException::class.java) {
                endpoint.createConsumer { }
            }
            assertTrue(endpoint.isSingleton)
            assertEquals(AgentIoEndpoint.DEFAULT_MAX_CONCURRENCY, endpoint.maxConcurrency)
        } finally {
            ctx.stop()
        }
    }

    @Test
    fun `maxConcurrency URI parameter is bound onto the endpoint`() {
        val ctx = contextWith(registryWith("fn" to succeedingFunction()))
        ctx.start()
        try {
            val endpoint = ctx.getEndpoint("agentio:fn?maxConcurrency=2") as AgentIoEndpoint
            assertEquals(2, endpoint.maxConcurrency)
        } finally {
            ctx.stop()
        }
    }

    @Test
    fun `maxConcurrency below the minimum fails route start with a clear message`() {
        val ctx = contextWith(registryWith("fn" to succeedingFunction()))
        ctx.addRoutes(
            object : RouteBuilder() {
                override fun configure() {
                    from("direct:in").to("agentio:fn?maxConcurrency=0")
                }
            },
        )
        val thrown = assertThrows(Exception::class.java) { ctx.start() }
        assertTrue(thrown.hasCause<IllegalArgumentException>())
        val message = generateSequence(thrown as Throwable) { it.cause }.mapNotNull { it.message }.joinToString(" | ")
        assertTrue(message.contains("maxConcurrency"), "message should name the offending parameter: $message")
        ctx.stop()
    }

    @Test
    fun `maxConcurrency at the minimum is accepted`() {
        val ctx = contextWith(registryWith("fn" to succeedingFunction()))
        ctx.start()
        try {
            val endpoint = ctx.getEndpoint("agentio:fn?maxConcurrency=1") as AgentIoEndpoint
            assertEquals(AgentIoEndpoint.MIN_MAX_CONCURRENCY, endpoint.maxConcurrency)
        } finally {
            ctx.stop()
        }
    }

    // ---- Concurrency cap ------------------------------------------------------------------

    @Test
    fun `producer caps concurrent in-flight invocations at maxConcurrency`() {
        val inFlight = AtomicInteger(0)
        val peak = AtomicInteger(0)
        val release = CompletableDeferred<Unit>()

        val gatedFunction =
            object : Instructible<Instructible.WithInstruction, Result<AgentOutput<Any>>> {
                override suspend fun invoke(input: Instructible.WithInstruction): Result<AgentOutput<Any>> {
                    val now = inFlight.incrementAndGet()
                    peak.updateAndGet { maxOf(it, now) }
                    release.await() // hold until the test releases, forcing overlap
                    inFlight.decrementAndGet()
                    return Result.success(successOutput("done"))
                }
            }

        val ctx = contextWith(registryWith("fn" to gatedFunction))
        ctx.addRoutes(
            object : RouteBuilder() {
                override fun configure() {
                    from("direct:in").to("agentio:fn?maxConcurrency=2")
                }
            },
        )
        ctx.start()
        try {
            val template = ctx.createProducerTemplate()
            // Fire 6 concurrent async requests; only 2 may run at once.
            val futures = (0 until 6).map { i -> template.asyncSendBody("direct:in", SampleInput("q$i")) }

            // Give the producer time to saturate the gate, then assert the peak never exceeded 2.
            Thread.sleep(500)
            assertEquals(2, peak.get(), "no more than maxConcurrency invocations should run at once")

            release.complete(Unit)
            futures.forEach { it.get(5, java.util.concurrent.TimeUnit.SECONDS) }
            assertEquals(0, inFlight.get())
        } finally {
            ctx.stop()
        }
    }

    // ---- Shutdown / cancellation ----------------------------------------------------------

    @Test
    fun `forced context shutdown cancels an in-flight invocation`() {
        // Camel's default ctx.stop() is graceful: it waits for in-flight exchanges to drain, so a
        // still-running agent completes naturally (the behaviour we want). Forced cancellation only
        // fires on shutdown timeout. Here we set an aggressive shutdown timeout so the strategy
        // gives up on the hung agent and forces shutdown, invoking the producer's doStop ->
        // scope.cancel() path, which must cancel the in-flight coroutine.
        val started = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Boolean>()

        val hangingFunction =
            object : Instructible<Instructible.WithInstruction, Result<AgentOutput<Any>>> {
                override suspend fun invoke(input: Instructible.WithInstruction): Result<AgentOutput<Any>> {
                    started.complete(Unit)
                    try {
                        delay(30.seconds) // hang until cancelled by forced shutdown
                        return Result.success(successOutput("never"))
                    } catch (ce: kotlin.coroutines.cancellation.CancellationException) {
                        cancelled.complete(true)
                        throw ce
                    }
                }
            }

        val ctx = contextWith(registryWith("fn" to hangingFunction))
        ctx.shutdownStrategy.timeout = 1
        ctx.shutdownStrategy.timeUnit = java.util.concurrent.TimeUnit.SECONDS
        ctx.shutdownStrategy.isShutdownNowOnTimeout = true
        ctx.addRoutes(
            object : RouteBuilder() {
                override fun configure() {
                    from("direct:in").to("agentio:fn")
                }
            },
        )
        ctx.start()
        val template = ctx.createProducerTemplate()
        template.asyncSendBody("direct:in", SampleInput("q"))

        // Wait until the agent is mid-flight, then stop the context; forced shutdown cancels it.
        kotlinx.coroutines.runBlocking {
            kotlinx.coroutines.withTimeout(5.seconds) { started.await() }
            ctx.stop()
            assertTrue(kotlinx.coroutines.withTimeout(10.seconds) { cancelled.await() })
        }
    }

    @Test
    fun `wrapping a CamelExecutionException still exposes the agent failure`() {
        // requestBody (as opposed to request) throws CamelExecutionException on failure; confirm
        // the underlying agent cause is preserved for callers using the throwing API.
        val boom = IllegalStateException("agent boom")
        val ctx = contextWith(registryWith("fn" to failingFunction(boom)))
        ctx.addRoutes(
            object : RouteBuilder() {
                override fun configure() {
                    from("direct:in").to("agentio:fn")
                }
            },
        )
        ctx.start()
        try {
            val thrown =
                assertThrows(CamelExecutionException::class.java) {
                    ctx.createProducerTemplate().requestBody("direct:in", SampleInput("x"), SampleOutput::class.java)
                }
            assertEquals(boom, thrown.cause)
        } finally {
            ctx.stop()
        }
    }

    @Test
    fun `producer fails synchronously when it is not started`() {
        // Directly drive process() on a producer that was never started: the scope is null, so it
        // must fail the exchange synchronously (return true) rather than launch a coroutine.
        val ctx = contextWith(registryWith("fn" to succeedingFunction()))
        ctx.start()
        try {
            val endpoint = ctx.getEndpoint("agentio:fn") as AgentIoEndpoint
            val producer = AgentIoProducer(endpoint) // deliberately not started
            val exchange = endpoint.createExchange().also { it.message.body = SampleInput("x") }
            var callbackDoneSync = false
            val sync = producer.process(exchange) { callbackDoneSync = true }
            assertTrue(sync, "unstarted producer must complete synchronously")
            assertTrue(callbackDoneSync)
            assertTrue(exchange.isFailed)
            assertInstanceOf(IllegalStateException::class.java, exchange.exception)
        } finally {
            ctx.stop()
        }
    }

    @Test
    fun `producer completes the exchange when the binding throws while writing output`() {
        // A binding that succeeds on input but throws (a non-IllegalArgument error) while writing
        // the output exercises the defensive catch in the async body: the exchange must still be
        // failed and the callback completed, never left hanging.
        val throwingOnOutput =
            object : ExchangeBinding {
                override fun toInput(exchange: Exchange): Instructible.WithInstruction = SampleInput("x")

                override fun fromOutput(
                    output: AgentOutput<*>,
                    exchange: Exchange,
                ): Unit = throw IllegalStateException("cannot write output")
            }
        val ctx = contextWith(registryWith("fn" to succeedingFunction(), "bad" to throwingOnOutput))
        ctx.addRoutes(
            object : RouteBuilder() {
                override fun configure() {
                    from("direct:in").to("agentio:fn?binding=bad")
                }
            },
        )
        ctx.start()
        try {
            val exchange = ctx.createProducerTemplate().request("direct:in") { it.message.body = SampleInput("x") }
            assertTrue(exchange.isFailed)
            assertInstanceOf(IllegalStateException::class.java, exchange.exception)
        } finally {
            ctx.stop()
        }
    }

    @Test
    fun `default binding reports the runtime type of a non-Instructible body`() {
        // An anonymous, non-Instructible body drives the diagnostic message branch that reads the
        // body's runtime class name.
        val ctx = contextWith(registryWith("fn" to succeedingFunction()))
        ctx.addRoutes(
            object : RouteBuilder() {
                override fun configure() {
                    from("direct:in").to("agentio:fn")
                }
            },
        )
        ctx.start()
        try {
            val exchange = ctx.createProducerTemplate().request("direct:in") { it.message.body = 42 }
            assertTrue(exchange.isFailed)
            assertInstanceOf(IllegalArgumentException::class.java, exchange.exception)
        } finally {
            ctx.stop()
        }
    }

    @Test
    fun `component discovers via agentio scheme without programmatic registration`() {
        // getEndpoint resolving proves the META-INF/services/.../agentio file wires the scheme.
        val ctx = contextWith(registryWith("fn" to succeedingFunction()))
        ctx.start()
        try {
            val endpoint = ctx.getEndpoint("agentio:fn")
            assertNotNull(endpoint)
            assertInstanceOf(AgentIoEndpoint::class.java, endpoint)
        } finally {
            ctx.stop()
        }
    }
}
