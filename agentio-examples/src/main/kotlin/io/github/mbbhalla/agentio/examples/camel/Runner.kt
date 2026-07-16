package io.github.mbbhalla.agentio.examples.camel

import io.github.mbbhalla.agentio.data.env.DatabaseEnvironment
import io.github.mbbhalla.agentio.examples.text2sql.data.RetailDatabase
import io.github.mbbhalla.agentio.module.text2sql.Text2SqlAgenticFunction
import kotlinx.coroutines.runBlocking
import org.apache.camel.Exchange
import org.apache.camel.builder.RouteBuilder
import org.apache.camel.impl.DefaultCamelContext
import org.apache.camel.support.SimpleRegistry
import org.slf4j.LoggerFactory
import java.io.File

/**
 * End-to-end example: a running [DefaultCamelContext] whose route reads a file of
 * natural-language queries (one per line), routes each through the Text2Sql agentic function
 * exposed as an `agentio:` endpoint, and prints the generated SQL.
 *
 * Route topology:
 * ```
 *   direct:queries
 *     -> split(body)          one exchange per query line
 *     -> process              wrap the line as a typed Text2SqlAgenticFunction.Input
 *     -> agentio:text2sql     invoke the agentic function (async, bounded concurrency)
 *     -> process              read AgentOutput.output.sql off the body and print it
 * ```
 *
 * This is deliberately small: it shows a real route with the agent as one node alongside a
 * standard EIP (Splitter). More elaborate examples (MCP tools, multi-agent collaboration,
 * SNS/SQS ingress, dead-letter routing) build on the same shape.
 *
 * NOTE: invoking the agent calls AWS Bedrock, so this `main` requires AWS credentials for
 * us-west-2. The wiring, route lifecycle, and mapping are exercised without credentials by the
 * unit tests in agentio-camel; this Runner demonstrates the real end-to-end flow.
 */
internal object Runner {
    private val LOG = LoggerFactory.getLogger(Runner::class.java)

    private const val AGENT_ID = "camel-text2sql-example"

    /** CamelContext management name — how this context is labelled in the Hawtio tree. */
    private const val MANAGEMENT_NAME = "agentio-text2sql"

    /** Cap on how many query lines convert concurrently — agent calls are expensive. */
    private const val MAX_CONCURRENCY = 2

    fun run(
        env: DatabaseEnvironment,
        queriesFile: File,
    ) {
        val queries = readQueries(queriesFile)
        require(queries.isNotEmpty()) { "No queries found in ${queriesFile.absolutePath}" }
        LOG.info("Loaded {} queries from {}", queries.size, queriesFile.name)

        // Start the embedded Hawtio console (if its WAR was supplied by the Gradle task) so the
        // route can be watched live in a browser. null => not started; fall back to batch-and-exit.
        val consoleUrl = HawtioConsole.start()

        // Build the agentic function once and register it under the name the route resolves.
        val text2Sql = runBlocking { Text2SqlAgenticFunction.create(agentId = AGENT_ID, env = env) }
        val registry = SimpleRegistry().apply { bind("text2sql", text2Sql) }

        val context = DefaultCamelContext(registry)
        // Name the context so it reads clearly in the Hawtio tree, and enable backlog tracing so
        // the console's Trace tab shows exchanges flowing through each node during the demo.
        context.managementName = MANAGEMENT_NAME
        context.isBacklogTracing = true
        context.addRoutes(buildRoutes())
        context.start()

        // When the console is up, keep the JVM (and the running context + its accumulated JMX
        // statistics) alive so the route stays visible after the batch completes; the shutdown hook
        // stops the context on Ctrl-C. Otherwise fall back to the original batch-and-exit behaviour.
        if (consoleUrl != null) {
            Runtime.getRuntime().addShutdownHook(Thread { context.stop() })
        }
        try {
            val template = context.createProducerTemplate()
            template.sendBody("direct:queries", queries)
        } finally {
            if (consoleUrl == null) {
                context.stop()
            }
        }
        if (consoleUrl != null) {
            LOG.info("Batch complete. Route visible at {} (Camel tab). Press Ctrl-C to stop.", consoleUrl)
            Thread.currentThread().join()
        }
    }

    private fun buildRoutes(): RouteBuilder =
        object : RouteBuilder() {
            override fun configure() {
                // Any agent failure is logged and handled so one bad query does not abort the batch.
                onException(Throwable::class.java)
                    .handled(true)
                    .process { exchange ->
                        val query = exchange.getProperty(PROPERTY_QUERY, String::class.java)
                        val cause = exchange.getProperty(Exchange.EXCEPTION_CAUGHT, Throwable::class.java)
                        LOG.error("Failed to convert query [{}]: {}", query, cause?.message)
                    }

                from("direct:queries")
                    .routeId("text2sql-route")
                    .split(body())
                    .id("split-queries")
                    .process { exchange ->
                        val query: String = requireNotNull(exchange.message.getBody(String::class.java)) { "empty query line" }
                        // Stash the original query so the print/error steps can reference it.
                        exchange.setProperty(PROPERTY_QUERY, query)
                        exchange.message.body = Text2SqlAgenticFunction.Input(text = query)
                    }.id("wrap-as-agent-input")
                    .to("agentio:text2sql?maxConcurrency=$MAX_CONCURRENCY")
                    .id("invoke-text2sql-agent")
                    .process { exchange ->
                        val query = exchange.getProperty(PROPERTY_QUERY, String::class.java)
                        val output: Text2SqlAgenticFunction.Output =
                            requireNotNull(exchange.message.getBody(Text2SqlAgenticFunction.Output::class.java)) {
                                "agentio endpoint produced no output body"
                            }
                        LOG.info("Q: {}", query)
                        LOG.info("SQL: {}", output.sql)
                    }.id("print-generated-sql")
            }
        }

    /** Reads non-blank, non-comment lines from the queries file. */
    private fun readQueries(file: File): List<String> =
        file
            .readLines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }

    private const val PROPERTY_QUERY = "example.query"
}

fun main(args: Array<String>) {
    val queriesPath =
        args.firstOrNull()
            ?: error(
                "Usage: provide the path to a queries file (one natural-language query per line). " +
                    "A sample lives at agentio-examples/src/main/resources/camel/text2sql-queries.txt",
            )
    Runner.run(env = RetailDatabase.environment, queriesFile = File(queriesPath))
}
