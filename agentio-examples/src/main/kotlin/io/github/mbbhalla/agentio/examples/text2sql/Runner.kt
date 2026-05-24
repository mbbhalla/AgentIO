package io.github.mbbhalla.agentio.examples.text2sql

import io.github.mbbhalla.agentio.core.common.JsonSchemaUtil
import io.github.mbbhalla.agentio.core.lib.AgentOutput
import io.github.mbbhalla.agentio.examples.text2sql.data.RetailDatabase
import io.github.mbbhalla.agentio.examples.text2sql.model.Dataset
import io.github.mbbhalla.agentio.examples.text2sql.function.Text2SqlAgenticFunction
import io.github.mbbhalla.agentio.examples.text2sql.function.Text2SqlAgenticFunctionProvider
import io.vavr.control.Try
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import org.slf4j.LoggerFactory

internal object Runner {

    private const val AGENT_ID = "a1b2c3d4-e5f6-7890-abcd-ef1234567890"

    private val LOG = LoggerFactory.getLogger(Runner::class.java)

    fun run(
        args: Array<String>,
    ): Try<AgentOutput<Text2SqlAgenticFunction.Output>> = runBlocking {
        val query = args.firstOrNull()
            ?: "What products have inventory below safety stock levels?"

        val agenticFunction = Text2SqlAgenticFunctionProvider.get(agentId = AGENT_ID)

        agenticFunction.invoke(
            Text2SqlAgenticFunction.Input(text = query),
        ).onFailure {
            LOG.error(it.message)
            it.printStackTrace()
        }.onSuccess { agentOutput ->
            LOG.info("Generated SQL: ${agentOutput.output.sql}")
            LOG.info("Results:")
            LOG.info(
                JsonSchemaUtil.json.encodeToString(
                    RetailDatabase.executeQuery(agentOutput.output.sql),
                ),
            )
        }
    }
}

fun main(args: Array<String>) {
    Runner.run(args)
}
