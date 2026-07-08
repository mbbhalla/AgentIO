package io.github.mbbhalla.agentio.examples.fetch

import io.github.mbbhalla.agentio.core.common.JsonSchemaUtil
import io.github.mbbhalla.agentio.core.lib.AgentOutput
import io.github.mbbhalla.agentio.examples.fetch.function.FetchAgenticFunction
import io.github.mbbhalla.agentio.examples.fetch.function.FetchAgenticFunctionProvider
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory

internal object Runner {
    private const val AGENT_ID = "b2c3d4e5-f6a7-8901-bcde-f23456789012"

    private val LOG = LoggerFactory.getLogger(Runner::class.java)

    fun run(args: Array<String>): Result<AgentOutput<FetchAgenticFunction.Output>> =
        runBlocking {
            val agenticFunction =
                FetchAgenticFunctionProvider.get(
                    agentId = AGENT_ID,
                    mcpServerProcess = args[0],
                )

            agenticFunction
                .invoke(
                    FetchAgenticFunction.Input(
                        url = "https://www.anthropic.com/engineering/effective-context-engineering-for-ai-agents",
                        focus = "architecture",
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
