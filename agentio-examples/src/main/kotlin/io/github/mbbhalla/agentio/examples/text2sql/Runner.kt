package io.github.mbbhalla.agentio.examples.text2sql

import io.github.mbbhalla.agentio.core.common.JsonSchemaUtil
import io.github.mbbhalla.agentio.core.lib.AgentOutput
import io.github.mbbhalla.agentio.data.env.DatabaseEnvironment
import io.github.mbbhalla.agentio.data.env.SelectSqlStatement
import io.github.mbbhalla.agentio.examples.text2sql.data.EmployeeDatabase
import io.github.mbbhalla.agentio.examples.text2sql.data.RetailDatabase
import io.github.mbbhalla.agentio.examples.text2sql.function.Text2SqlAgenticFunction
import io.github.mbbhalla.agentio.examples.text2sql.function.Text2SqlAgenticFunctionProvider
import io.vavr.control.Try
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory

internal object Runner {
    private const val AGENT_ID = "a1b2c3d4-e5f6-7890-abcd-ef1234567890"

    private val LOG = LoggerFactory.getLogger(Runner::class.java)

    fun run(
        env: DatabaseEnvironment,
        query: String,
    ): Try<AgentOutput<Text2SqlAgenticFunction.Output>> =
        runBlocking {
            val agenticFunction =
                Text2SqlAgenticFunctionProvider.get(
                    agentId = AGENT_ID,
                    env = env,
                )

            agenticFunction
                .invoke(
                    Text2SqlAgenticFunction.Input(text = query),
                ).onFailure {
                    LOG.error(it.message)
                    it.printStackTrace()
                }.onSuccess { agentOutput ->
                    LOG.info("Generated SQL: ${agentOutput.output.sql}")
                    LOG.info("Results:")
                    val validated = SelectSqlStatement(agentOutput.output.sql)
                    LOG.info(JsonSchemaUtil.json.encodeToString(env.executeQuery(validated)))
                }
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
