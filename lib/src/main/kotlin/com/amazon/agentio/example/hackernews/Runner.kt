package com.amazon.agentio.example.hackernews

import com.amazon.agentio.common.JsonSchemaUtil
import com.amazon.agentio.example.hackernews.function.HackerNewsAgenticFunction
import com.amazon.agentio.example.hackernews.function.HackerNewsAgenticFunctionProvider
import com.amazon.agentio.lib.AgentOutput
import io.vavr.control.Try
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import org.slf4j.LoggerFactory

internal object Runner {

    private const val AGENT_ID = "04177af0-e1c3-4a47-9256-af892e5e5c05"

    private val LOG = LoggerFactory.getLogger(Runner::class.java)

    fun run(
        args: Array<String>,
    ): Try<AgentOutput<HackerNewsAgenticFunction.HackerNewsAgenticFunctionOutput>> = runBlocking {
        val agenticFunction = HackerNewsAgenticFunctionProvider.get(
            agentId = AGENT_ID,
            mcpServerProcess = args[0],
        )

        agenticFunction.invoke(
            HackerNewsAgenticFunction.HackerNewsAgenticFunctionInput(
                value = "Latest in Agentic AI Agent collaborations",
            ),
        ).onFailure {
            LOG.error(it.message)
            it.printStackTrace()
        }.onSuccess { agentOutput ->
            LOG.info(JsonSchemaUtil.json.encodeToString(agentOutput.output))
        }
    }
}

fun main(args: Array<String>) {
    Runner.run(args)
}
