package io.github.mbbhalla.agentio.examples.camel

import io.github.mbbhalla.agentio.core.common.JsonSchemaUtil
import io.github.mbbhalla.agentio.data.model.DataValue
import io.github.mbbhalla.agentio.examples.compass.ALL_SUPPLY_CHAIN_VARIABLES
import io.github.mbbhalla.agentio.examples.compass.SupplyChainDatabase
import io.github.mbbhalla.agentio.module.compass.function.AnalyzerAgenticFunction
import io.github.mbbhalla.agentio.module.compass.function.ConstraintGeneratorAgenticFunction
import io.github.mbbhalla.agentio.module.compass.model.AnalysisResult
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import org.apache.camel.Exchange
import org.apache.camel.builder.AggregationStrategies
import org.apache.camel.builder.RouteBuilder
import org.apache.camel.impl.DefaultCamelContext
import org.apache.camel.support.SimpleRegistry
import org.slf4j.LoggerFactory
import java.io.File

/**
 * "Self-healing supply chain" demo: a running [DefaultCamelContext] that watches a folder for
 * carrier tracking-event files and, for each shipment, predicts an ETA slip and then proves a
 * feasible reroute — all as `agentio:` nodes inside one Camel route.
 *
 * Route topology (every ingress/egress is local; the only AWS dependency is Bedrock, which the
 * agents call):
 * ```
 *   file:carrier-events                drop shipment-*.json here -> the route reacts live
 *     -> process (parse JSON)          one file = a batch of CarrierEvents (JSON array)
 *     -> split(events)                 one exchange per raw tracking event
 *     -> aggregate(by shipmentId)      Aggregator EIP: collapse the event stream per shipment
 *     -> process                       build a grounded analyzer objective from the events
 *     -> agentio:etaAnalyzer           compass Analyzer: predicted delay, each number SQL-backed
 *     -> filter(willBeLate)            only shipments the analysis flags as delayed continue
 *     -> process                       wrap the AnalysisResult as the solver node's typed input
 *     -> agentio:rerouteSolver         composite: ConstraintGenerator -> Z3 proves a feasible reroute
 *     -> process (format report)       render a human-readable recommendation
 *     -> log + file:recommendations    print it and persist it
 *   onException -> log                 one bad shipment never aborts the stream; transcript retained
 * ```
 *
 * The two `agentio:` URIs are the whole point: the LLM decides what to do, the solver proves it is
 * feasible. Run it, drop a file into the watched folder, and watch the reroute happen on camera.
 *
 * NOTE: invoking the agents calls AWS Bedrock, so this `main` requires AWS credentials for
 * us-west-2 (same as the other agentic examples). Sibling of the Text2Sql [Runner] in this package.
 */
internal object RerouteRunner {
    private val LOG = LoggerFactory.getLogger(RerouteRunner::class.java)

    /** Agent calls are expensive; cap concurrent in-flight invocations per endpoint. */
    private const val MAX_CONCURRENCY = 2

    /** CamelContext management name — how this context is labelled in the Hawtio tree. */
    private const val MANAGEMENT_NAME = "agentio-reroute"

    /** How long the Aggregator waits for more events of a shipment before firing downstream (ms). */
    private const val AGGREGATION_COMPLETION_TIMEOUT_MS = 2_000L

    private const val PROPERTY_SHIPMENT_ID = "reroute.shipmentId"

    /** A single carrier tracking event, as it appears in the watched JSON files. */
    @Serializable
    data class CarrierEvent(
        val shipmentId: String,
        val status: String,
        val location: String,
        val timestamp: String,
        val note: String = "",
    )

    fun run(
        eventsDir: File,
        outputDir: File,
    ) {
        require(eventsDir.isDirectory) { "Carrier-events directory not found: ${eventsDir.absolutePath}" }
        outputDir.mkdirs()
        LOG.info("Watching {} for carrier-event files; recommendations -> {}", eventsDir.absolutePath, outputDir.absolutePath)

        // Build both agentic functions once and register them under the names the route resolves.
        val env = SupplyChainDatabase.environment
        val (etaAnalyzer, rerouteSolver) =
            runBlocking {
                val analyzer =
                    AnalyzerAgenticFunction.create(
                        env = env,
                        problemDomain = "Supply Chain In-Transit Delay Analysis",
                    )
                val constraintGenerator =
                    ConstraintGeneratorAgenticFunction.create(
                        env = env,
                        variables = ALL_SUPPLY_CHAIN_VARIABLES,
                        problemDomain = "Supply Chain Reroute Optimization (SMTLIB2 / Z3)",
                    )
                analyzer to RerouteSolverAgenticFunction(constraintGenerator, SupplyChainDatabase.DATASET_NAME)
            }

        val registry =
            SimpleRegistry().apply {
                bind("etaAnalyzer", etaAnalyzer)
                bind("rerouteSolver", rerouteSolver)
            }

        // Start the embedded Hawtio console (if its WAR was supplied by the Gradle task) before the
        // context, so the route diagram and per-node statistics are visible as soon as it starts.
        val consoleUrl = HawtioConsole.start()

        val context = DefaultCamelContext(registry)
        // Name the context for the Hawtio tree, and enable backlog tracing so the console's Trace
        // tab shows each shipment's exchange flowing through the analyzer and solver nodes.
        context.managementName = MANAGEMENT_NAME
        context.isBacklogTracing = true
        context.addRoutes(buildRoutes(eventsDir, outputDir))
        context.start()
        LOG.info("Route started. Drop a *.json batch of carrier events into the watched folder. Ctrl-C to stop.")
        if (consoleUrl != null) {
            LOG.info("Watch it live at {} (Camel tab -> Route Diagram / Trace).", consoleUrl)
        }
        // Keep the JVM alive so the file consumer keeps polling for newly dropped files.
        Runtime.getRuntime().addShutdownHook(Thread { context.stop() })
        Thread.currentThread().join()
    }

    private fun buildRoutes(
        eventsDir: File,
        outputDir: File,
    ): RouteBuilder =
        object : RouteBuilder() {
            override fun configure() {
                // One bad shipment must not abort the stream; log with the retained transcript context.
                onException(Throwable::class.java)
                    .handled(true)
                    .process { exchange ->
                        val shipmentId = exchange.getProperty(PROPERTY_SHIPMENT_ID, String::class.java)
                        val cause = exchange.getProperty(Exchange.EXCEPTION_CAUGHT, Throwable::class.java)
                        LOG.error("Reroute pipeline failed for shipment [{}]: {}", shipmentId, cause?.message, cause)
                    }

                // noop=true keeps files in place and dedupes by name, so the demo is re-runnable and
                // reacts to each newly dropped file without consuming it.
                from("file:${eventsDir.absolutePath}?noop=true&readLock=changed")
                    .routeId("reroute-demo")
                    .process { exchange ->
                        val json = requireNotNull(exchange.message.getBody(String::class.java)) { "empty event file" }
                        exchange.message.body =
                            JsonSchemaUtil.json.decodeFromString(EVENT_LIST_SERIALIZER, json)
                    }.id("parse-event-file")
                    .split(body())
                    .id("split-events")
                    // Aggregator EIP: collapse the split event stream back into one batch per shipment.
                    .aggregate(simple("\${body.shipmentId}"), AggregationStrategies.groupedBody())
                    .completionTimeout(AGGREGATION_COMPLETION_TIMEOUT_MS)
                    .id("aggregate-by-shipment")
                    .process { exchange ->
                        @Suppress("UNCHECKED_CAST")
                        val events = exchange.message.getBody(List::class.java) as List<CarrierEvent>
                        val shipmentId = events.firstOrNull()?.shipmentId ?: "UNKNOWN"
                        exchange.setProperty(PROPERTY_SHIPMENT_ID, shipmentId)
                        LOG.info("Analyzing shipment [{}] from {} tracking event(s)", shipmentId, events.size)
                        exchange.message.body =
                            AnalyzerAgenticFunction.Input(
                                objective = buildAnalyzerObjective(shipmentId, events),
                                datasetName = SupplyChainDatabase.DATASET_NAME,
                            )
                    }.id("build-analyzer-objective")
                    .to("agentio:etaAnalyzer?maxConcurrency=$MAX_CONCURRENCY")
                    .id("invoke-eta-analyzer")
                    // Only shipments the grounded analysis flags as delayed proceed to the solver.
                    .filter { exchange ->
                        val output = exchange.message.getBody(AnalyzerAgenticFunction.Output::class.java)
                        val delayed = output != null && isDelayed(output.analysisResult)
                        val shipmentId = exchange.getProperty(PROPERTY_SHIPMENT_ID, String::class.java)
                        LOG.info("Shipment [{}] delayed? {}", shipmentId, delayed)
                        delayed
                    }.id("filter-delayed-shipments")
                    .process { exchange ->
                        val output =
                            requireNotNull(exchange.message.getBody(AnalyzerAgenticFunction.Output::class.java)) {
                                "etaAnalyzer produced no output"
                            }
                        exchange.message.body = RerouteSolverAgenticFunction.Input(analysisResult = output.analysisResult)
                    }.id("build-solver-input")
                    .to("agentio:rerouteSolver?maxConcurrency=$MAX_CONCURRENCY")
                    .id("invoke-reroute-solver")
                    .process { exchange ->
                        val shipmentId = exchange.getProperty(PROPERTY_SHIPMENT_ID, String::class.java) ?: "UNKNOWN"
                        val output =
                            requireNotNull(exchange.message.getBody(RerouteSolverAgenticFunction.Output::class.java)) {
                                "rerouteSolver produced no output"
                            }
                        exchange.message.body = formatRecommendation(shipmentId, output)
                        exchange.message.setHeader(Exchange.FILE_NAME, "reroute-$shipmentId.md")
                    }.id("format-recommendation")
                    .log("\n\${body}")
                    .to("file:${outputDir.absolutePath}")
                    .id("write-recommendation")
            }
        }

    /** Builds a concrete, dataset-grounded objective for the analyzer from the shipment's events. */
    private fun buildAnalyzerObjective(
        shipmentId: String,
        events: List<CarrierEvent>,
    ): String {
        val timeline =
            events.joinToString(separator = "\n") { "  - [${it.timestamp}] ${it.status} @ ${it.location}: ${it.note}" }
        return """
            In-transit shipment "$shipmentId" has produced these carrier tracking events:
            $timeline

            Using the dataset, determine whether this shipment will miss its planned delivery date.
            Ground every number in SQL against the real tables (shipment, transportation_lane,
            vendor_lead_time, sourcing_rule, inv_level). Include result items for:
              - "predicted_delay_days": whole days the shipment is projected to arrive late (0 if on time),
              - the product and destination site involved,
              - candidate alternate sourcing rules / transportation lanes and their lead times and costs
                that could recover the delivery.
            If a value cannot be found in the data, write "unknown" rather than guessing.

            ENCODING (critical): each resultItem "value" is a polymorphic DataValue and MUST include
            its "type" discriminator, otherwise the result cannot be parsed. Use exactly one of:
              - {"type":"LongValue","value":<integer>}     e.g. predicted_delay_days -> {"type":"LongValue","value":0}
              - {"type":"DoubleValue","value":<decimal>}
              - {"type":"StringValue","value":"<text>"}    use for names/ids and for "unknown"
              - {"type":"BooleanValue","value":true|false}
            Never emit a value object without its "type" field (e.g. {"value":0} is invalid).
            """.trimIndent()
    }

    /**
     * The filter predicate: is this shipment actually delayed? True when the analysis contains a
     * "predicted_delay_days" result item with a positive numeric value. Absent/unknown => not delayed.
     */
    private fun isDelayed(analysis: AnalysisResult): Boolean {
        val delayItem =
            analysis.resultItems.firstOrNull { it.key.equals("predicted_delay_days", ignoreCase = true) }
                ?: return false
        return when (val value = delayItem.value) {
            is DataValue.LongValue -> value.value > 0L
            is DataValue.DoubleValue -> value.value > 0.0
            else -> false
        }
    }

    private fun formatRecommendation(
        shipmentId: String,
        output: RerouteSolverAgenticFunction.Output,
    ): String {
        val verdict = if (output.isFeasible) "FEASIBLE REROUTE FOUND" else "NO FEASIBLE REROUTE"
        val models =
            if (output.feasibleModels.isEmpty()) {
                "_(Z3 found no satisfying assignment — the analysis admits no feasible reroute.)_"
            } else {
                output.feasibleModels.withIndex().joinToString(separator = "\n") { (i, model) ->
                    "**Option ${i + 1}:** " + model.entries.joinToString(", ") { "${it.key} = ${it.value}" }
                }
            }
        return """
            # Reroute recommendation — shipment $shipmentId

            **Verdict:** $verdict

            ## Provably feasible options
            $models

            ## Why (LLM constraint encoding, verified by Z3)
            ${output.explanation}

            ## SMTLIB2 formula solved
            ```
            ${output.smtlibv2Formula}
            ```
            """.trimIndent()
    }

    private val EVENT_LIST_SERIALIZER =
        kotlinx.serialization.builtins.ListSerializer(CarrierEvent.serializer())
}

fun main(args: Array<String>) {
    val eventsDir =
        File(
            args.getOrNull(0)
                ?: error(
                    "Usage: provide the carrier-events directory to watch. " +
                        "A sample lives at agentio-examples/src/main/resources/reroute/carrier-events",
                ),
        )
    val outputDir = File(args.getOrNull(1) ?: "${System.getProperty("java.io.tmpdir")}/reroute-recommendations")
    RerouteRunner.run(eventsDir = eventsDir, outputDir = outputDir)
}
