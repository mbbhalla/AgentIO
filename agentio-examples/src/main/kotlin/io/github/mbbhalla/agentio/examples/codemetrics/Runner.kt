package io.github.mbbhalla.agentio.examples.codemetrics

import io.github.mbbhalla.agentio.core.common.JsonSchemaUtil
import io.github.mbbhalla.agentio.core.lib.AgentOutput
import io.github.mbbhalla.agentio.examples.codemetrics.function.CodeMetricsAgenticFunction
import io.github.mbbhalla.agentio.examples.codemetrics.function.CodeMetricsAgenticFunctionProvider
import io.vavr.control.Try
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import java.nio.file.Path

internal object Runner {
    private const val AGENT_ID = "d4e5f6a7-b8c9-0123-defa-456789012345"

    private val LOG = LoggerFactory.getLogger(Runner::class.java)

    fun run(args: Array<String>): Try<AgentOutput<CodeMetricsAgenticFunction.Output>> =
        runBlocking {
            val projectPath = args[0]
            val checkpointDir = Path.of(args[1])
            val agenticFunction =
                CodeMetricsAgenticFunctionProvider.get(
                    agentId = AGENT_ID,
                    projectPath = projectPath,
                    checkpointDir = checkpointDir,
                )

            agenticFunction
                .invoke(
                    CodeMetricsAgenticFunction.Input(
                        projectPath = projectPath,
                        language = "kt",
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
