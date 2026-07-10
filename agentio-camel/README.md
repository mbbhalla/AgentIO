# agentio-camel

Apache Camel component that lets an AgentIO agent participate in a Camel route as a node. An
agentic function is addressed by a URI — `to("agentio:<beanName>")` — so agents compose with
Camel's 300+ connectors and Enterprise Integration Patterns the same way any other processor does.

```kotlin
from("aws2-sqs://teamTickets")
    .to("agentio:ticketEvaluator")      // an LLM agent
    .to("agentio:fixGenerator")         // another agent
    .to("agentio:codeReviewGenerator")  // another agent
    .to("slack:#oncall")                // ping oncall
```

## Core Principle: Edge Adapter, Not a New Platform

This module is an **optional edge adapter**. The dependency arrow points inward only —
`agentio-camel → agentio-core` — and `agentio-core` depends on neither Camel nor this module.
The connector ecosystem's transitive/CVE surface stays quarantined here; the typed,
coroutine-native core is untouched. You bring agents to the integration fabric you already run,
rather than adopting a new agent platform.

## What Binds to a Route

The endpoint binds to `Instructible` — the core `f(Input) = Output` interface — **not** to a
concrete function type. Because a plain agent, an `AgenticFunctionEvaluator` (multi-trial
voting), and any composite all implement `Instructible`, they are interchangeable behind one URI:

| Registry bean | Behind `agentio:<name>` |
|---|---|
| `AbstractAgenticFunction` subclass | A single agent invocation |
| `AgenticFunctionEvaluator` | N trials + a selection strategy (majority vote, cheapest, metric) |
| Any `Instructible` composite | A debate / chain / fan-in assembly, as one node |

Producer-only by design: agents are **invoked, not polled**, so consuming from an `agentio:`
endpoint is unsupported.

## URI Format

```
agentio:<registryBeanName>[?maxConcurrency=<n>][&binding=<bindingBeanName>]
```

| Parameter | Default | Meaning |
|---|---|---|
| `<registryBeanName>` | *(required)* | Name of the `Instructible` bean in the Camel registry |
| `maxConcurrency` | `4` | Upper bound on concurrent in-flight agent invocations for this endpoint |
| `binding` | identity/value | Name of a registry `ExchangeBinding` bean; omitted → the default binding |

A missing or wrong-typed bean, a blank name, or an unknown binding **fails fast at route start**,
not at first message.

## How It Behaves at Runtime

The producer bridges Camel's async SPI to the core's `suspend fun invoke(...)`. Four properties
keep the Camel edge seamless with AgentIO's concurrency model:

- **Non-blocking.** `process` returns `false` and completes the `AsyncCallback` from a coroutine,
  so a minutes-long agent turn never pins a Camel route thread. A `runBlocking` producer would
  cap throughput at the route pool size; this does not.
- **Bounded concurrency.** A `Semaphore` (`maxConcurrency`) caps in-flight invocations. Freeing
  the route thread removes Camel's natural backpressure, so this ceiling prevents an inbound
  backlog from launching unbounded — and expensive — concurrent agent conversations.
- **Failure as a value.** A core `Result.failure` is set as the exchange exception, so Camel's
  redelivery / dead-letter / circuit-breaker machinery owns retry and fallback declaratively.
- **Cancellation-correct.** The producer's coroutine scope is tied to its lifecycle. On (forced)
  shutdown the scope is cancelled to abort in-flight agents, and `CancellationException`
  propagates rather than being swallowed into a failure `Result` — so shutdown never looks like a
  retryable error.

## The Exchange Boundary

`ExchangeBinding` is the single place a route's untyped `Object` world meets AgentIO's typed
`f(Input) = Output` world. Binding is explicit and pluggable, never reflective.

**Default binding** (identity-in, value-out):

- **Input:** the message body must already be an `Instructible.WithInstruction`. Map raw payloads
  (e.g. a JSON string off a queue) to a typed input **upstream**, in a `.process {}` step where
  types are real — not in a reflective seam here.
- **Output:** the body becomes `AgentOutput.output` (the typed result value), and observability
  data is written to exchange properties:

| Property key | Value |
|---|---|
| `AgentIo.instructionId` | The agent's instruction id |
| `AgentIo.inputTokens` | Total input tokens for the conversation |
| `AgentIo.outputTokens` | Total output tokens for the conversation |
| `AgentIo.conversation` | The full `Conversation` transcript (retained through dead-letter routing) |

**Custom binding.** Implement `ExchangeBinding` (thread-safe and stateless — one instance is
shared across all concurrent exchanges), register it in the Camel registry, and reference it via
`?binding=<beanName>` to adapt a raw body or shape a custom output.

**Boundary type-check.** When the resolved function is an `AbstractAgenticFunction`, the bound
input is checked against the function's declared input `KClass` before any coroutine launches, so
a wrong-typed body fails fast at the boundary rather than cryptically deep inside `invoke`.

## Usage

Register the agentic function under a name, then reference it from a route:

```kotlin
val registry = SimpleRegistry().apply {
    bind("ticketEvaluator", myAgenticFunction)  // any Instructible
}
val context = DefaultCamelContext(registry)

context.addRoutes(object : RouteBuilder() {
    override fun configure() {
        // Handle agent failures so one bad item does not abort the batch.
        onException(Throwable::class.java)
            .handled(true)
            .to("direct:deadLetter")

        from("direct:tickets")
            .process { it.message.body = TicketInput(it.message.getBody(String::class.java)) }
            .to("agentio:ticketEvaluator?maxConcurrency=2")
            .to("mock:done")
    }
})
context.start()
```

A complete, runnable example — a route that reads natural-language queries from a file and routes
each through the Text2SQL agent — lives in `agentio-examples`
(`io.github.mbbhalla.agentio.examples.camel.Runner`), runnable via
`./gradlew :agentio-examples:RunCamelText2SqlRoute`.

## Building Blocks

| Type | Responsibility |
|---|---|
| `AgentIoComponent` | Registers the `agentio:` scheme; resolves the `Instructible` and `ExchangeBinding` from the registry |
| `AgentIoEndpoint` | Holds the resolved function, binding, and `maxConcurrency`; producer-only |
| `AgentIoProducer` | `DefaultAsyncProducer` bridging Camel's async SPI to the coroutine `invoke`; concurrency gate + lifecycle-scoped cancellation |
| `ExchangeBinding` | Maps `Exchange` ⇄ typed `Input`/`AgentOutput`; pluggable, with an identity/value `Default` |

## Dependencies

- `agentio-core` — the `Instructible` / `AgentOutput` abstraction (API).
- `org.apache.camel:camel-support` — component/endpoint/producer SPI (production classpath).
- `kotlinx-coroutines-core` — the async bridge.
- `org.apache.camel:camel-core` — full routing engine, **test scope only** (never on the
  production classpath).

## Scheme Registration

The `agentio:` scheme is discovered via
`META-INF/services/org/apache/camel/component/agentio`, so `to("agentio:...")` resolves without
any programmatic component registration.
