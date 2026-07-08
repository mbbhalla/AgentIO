package io.github.mbbhalla.agentio.examples.text2sql

import io.github.mbbhalla.agentio.core.common.JsonSchemaUtil
import io.github.mbbhalla.agentio.core.lib.AgentOutput
import io.github.mbbhalla.agentio.core.lib.eval.AgenticFunctionEvaluator
import io.github.mbbhalla.agentio.core.lib.eval.ConversationMetrics
import io.github.mbbhalla.agentio.core.lib.eval.MetricAgentOutputSelector
import io.github.mbbhalla.agentio.core.lib.eval.MostFrequentAgentOutputSelector
import io.github.mbbhalla.agentio.core.lib.eval.SelectionMode
import io.github.mbbhalla.agentio.data.env.DatabaseEnvironment
import io.github.mbbhalla.agentio.data.env.SelectSqlStatement
import io.github.mbbhalla.agentio.examples.text2sql.data.EmployeeDatabase
import io.github.mbbhalla.agentio.examples.text2sql.data.RetailDatabase
import io.github.mbbhalla.agentio.module.text2sql.Text2SqlAgenticFunction
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicInteger

/**
 * Demonstrates self-consistency for text-to-SQL: the same natural-language query is converted to
 * SQL several times, then [AgenticFunctionEvaluator] picks the answer the model agreed on most
 * often. Text-to-SQL is non-deterministic at temperature > 0, so a single generation can be an
 * unlucky outlier; majority voting across independent trials is a well-known accuracy booster.
 *
 * The selection strategy is "most frequent SQL, breaking ties toward the cheapest conversation"
 * (fewest total tokens) — so when several distinct queries are equally popular, the leanest one wins.
 */
internal object Runner {
    private const val AGENT_ID = "a1b2c3d4-e5f6-7890-abcd-ef1234567890"

    /** Number of independent SQL generations to vote across. */
    private const val NUM_TRIALS = 5

    /** How many trials may run concurrently. */
    private const val MAX_PARALLELISM = 3

    private val LOG = LoggerFactory.getLogger(Runner::class.java)

    fun run(
        env: DatabaseEnvironment,
        query: String,
    ): Result<AgentOutput<Text2SqlAgenticFunction.Output>> =
        runBlocking {
            /*
                Pre-create one independent function per trial. create() is a suspend function that
                spins up its own MCP server/client, but the evaluator's factory is not suspend — so
                we build the pool here and hand instances out by index. Independent clients also let
                trials run concurrently without sharing a single stdio transport.
             */
            val functions =
                (0 until NUM_TRIALS).map { trial ->
                    Text2SqlAgenticFunction.create(
                        agentId = "$AGENT_ID-$trial",
                        env = env,
                    )
                }
            val nextFunction = AtomicInteger(0)

            val evaluator =
                AgenticFunctionEvaluator(
                    AgenticFunctionEvaluator.EvaluationConfig(
                        agenticFunctionFactory = { functions[nextFunction.getAndIncrement()] },
                        numIterations = NUM_TRIALS,
                        maxParallelism = MAX_PARALLELISM,
                        // Majority vote on the generated SQL; break ties toward the cheapest run.
                        selectionStrategy =
                            MostFrequentAgentOutputSelector(
                                tieBreaker =
                                    MetricAgentOutputSelector(
                                        mode = SelectionMode.MINIMIZE,
                                        metric = ConversationMetrics::totalTokens,
                                    ),
                            ),
                        onIterationComplete = { progress ->
                            LOG.info(
                                "[Trial {}/{}] {} (failures so far: {})",
                                progress.completedCount,
                                progress.totalIterations,
                                if (progress.result.isSuccess) "generated SQL" else "FAILED",
                                progress.failuresSoFar,
                            )
                        },
                    ),
                )

            val evaluation = evaluator.evaluate(Text2SqlAgenticFunction.Input(text = query))

            logVoteDistribution(evaluation)

            evaluation.selectedOutput
                .onFailure {
                    LOG.error("No SQL could be selected across $NUM_TRIALS trials: ${it.message}")
                    it.printStackTrace()
                }.onSuccess { agentOutput ->
                    LOG.info("Selected SQL: ${agentOutput.output.sql}")
                    LOG.info("Results:")
                    val validated = SelectSqlStatement(agentOutput.output.sql)
                    LOG.info(JsonSchemaUtil.json.encodeToString(env.executeQuery(validated)))
                }
        }

    /** Logs how many trials produced each distinct SQL — the evidence behind the majority vote. */
    private fun logVoteDistribution(evaluation: AgenticFunctionEvaluator.EvaluationResult<Text2SqlAgenticFunction.Output>) {
        val countBySql =
            evaluation.allOutputs
                .filter { it.isSuccess }
                .groupingBy { it.getOrThrow().output.sql }
                .eachCount()

        LOG.info(
            "Vote distribution across {} trials ({} succeeded, {} distinct SQL):",
            evaluation.totalIterations,
            countBySql.values.sum(),
            countBySql.size,
        )
        countBySql
            .entries
            .sortedByDescending { it.value }
            .forEach { (sql, count) -> LOG.info("  [{} votes] {}", count, sql) }
    }
}

fun main(args: Array<String>) {
    when (System.getProperty("agentio.text2sql.entrypoint", "retail")) {
        "employee" -> {
            val query =
                args.firstOrNull()
                    ?: "Who are the top-rated employees and what projects are they working on?"
            Runner.run(EmployeeDatabase.environment, query)
        }
        else -> {
            val query =
                args.firstOrNull()
                    ?: "What products have inventory below safety stock levels?"
            Runner.run(RetailDatabase.environment, query)
        }
    }
}
