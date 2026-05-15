package io.github.mbbhalla.agentio.examples.gitanalyzer

import io.github.mbbhalla.agentio.core.common.JsonSchemaUtil
import io.github.mbbhalla.agentio.core.lib.AgentOutput
import io.github.mbbhalla.agentio.examples.gitanalyzer.function.GitAnalyzerAgenticFunction
import io.github.mbbhalla.agentio.examples.gitanalyzer.function.GitAnalyzerAgenticFunctionProvider
import io.vavr.control.Try
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import org.slf4j.LoggerFactory

internal object Runner {

    private const val AGENT_ID = "c3d4e5f6-a7b8-9012-cdef-345678901234"

    private val LOG = LoggerFactory.getLogger(Runner::class.java)

    fun run(
        args: Array<String>,
    ): Try<AgentOutput<GitAnalyzerAgenticFunction.Output>> = runBlocking {
        val repoPath = args[0]
        val agenticFunction = GitAnalyzerAgenticFunctionProvider.get(
            agentId = AGENT_ID,
            repoPath = repoPath,
        )

        agenticFunction.invoke(
            GitAnalyzerAgenticFunction.Input(
                repoPath = repoPath,
                analysisType = "activity",
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
