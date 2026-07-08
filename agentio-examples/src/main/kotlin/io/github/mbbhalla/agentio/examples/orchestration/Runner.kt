package io.github.mbbhalla.agentio.examples.orchestration

import io.github.mbbhalla.agentio.core.common.JsonSchemaUtil
import io.github.mbbhalla.agentio.examples.orchestration.function.DocumentationWorkerAgenticFunction
import io.github.mbbhalla.agentio.examples.orchestration.function.OrchestrationAgenticFunctionProvider
import io.github.mbbhalla.agentio.examples.orchestration.function.OrchestratorAgenticFunction
import io.github.mbbhalla.agentio.examples.orchestration.function.QualityWorkerAgenticFunction
import io.github.mbbhalla.agentio.examples.orchestration.function.SecurityWorkerAgenticFunction
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory

internal object Runner {
    private const val SECURITY_AGENT_ID = "orch-sec-a1b2c3d4-0001-0001-0001-000000000001"
    private const val QUALITY_AGENT_ID = "orch-qual-a1b2c3d4-0001-0001-0001-000000000002"
    private const val DOCS_AGENT_ID = "orch-docs-a1b2c3d4-0001-0001-0001-000000000003"
    private const val ORCHESTRATOR_AGENT_ID = "orch-main-a1b2c3d4-0001-0001-0001-000000000000"

    private val LOG = LoggerFactory.getLogger(Runner::class.java)

    fun run(args: Array<String>) =
        runBlocking {
            val projectPath = args[0]
            val language = args.getOrElse(1) { "kt" }

            LOG.info("=== Orchestration Pattern: Project Health Analysis ===")
            LOG.info("Project: {} | Language: {}", projectPath, language)

            // Phase 1: Dispatch workers in parallel
            LOG.info("\n--- Phase 1: Worker Dispatch (parallel) ---")

            val workerResults =
                coroutineScope {
                    val securityDeferred =
                        async {
                            LOG.info("[Orchestrator] Dispatching Security Worker...")
                            val worker = OrchestrationAgenticFunctionProvider.getSecurityWorker(SECURITY_AGENT_ID, projectPath)
                            worker.invoke(SecurityWorkerAgenticFunction.Input(projectPath = projectPath))
                        }

                    val qualityDeferred =
                        async {
                            LOG.info("[Orchestrator] Dispatching Quality Worker...")
                            val worker = OrchestrationAgenticFunctionProvider.getQualityWorker(QUALITY_AGENT_ID, projectPath)
                            worker.invoke(QualityWorkerAgenticFunction.Input(projectPath = projectPath, language = language))
                        }

                    val docsDeferred =
                        async {
                            LOG.info("[Orchestrator] Dispatching Documentation Worker...")
                            val worker = OrchestrationAgenticFunctionProvider.getDocumentationWorker(DOCS_AGENT_ID, projectPath)
                            worker.invoke(DocumentationWorkerAgenticFunction.Input(projectPath = projectPath, language = language))
                        }

                    Triple(securityDeferred.await(), qualityDeferred.await(), docsDeferred.await())
                }

            val (securityResult, qualityResult, docsResult) = workerResults

            if (securityResult.isFailure) {
                LOG.error("[Security Worker] Failed: {}", securityResult.exceptionOrNull()?.message)
                return@runBlocking
            }
            if (qualityResult.isFailure) {
                LOG.error("[Quality Worker] Failed: {}", qualityResult.exceptionOrNull()?.message)
                return@runBlocking
            }
            if (docsResult.isFailure) {
                LOG.error("[Documentation Worker] Failed: {}", docsResult.exceptionOrNull()?.message)
                return@runBlocking
            }

            val securityJson =
                JsonSchemaUtil.json.encodeToString(
                    SecurityWorkerAgenticFunction.Output.serializer(),
                    securityResult.getOrThrow().output,
                )
            val qualityJson =
                JsonSchemaUtil.json.encodeToString(
                    QualityWorkerAgenticFunction.Output.serializer(),
                    qualityResult.getOrThrow().output,
                )
            val docsJson =
                JsonSchemaUtil.json.encodeToString(
                    DocumentationWorkerAgenticFunction.Output.serializer(),
                    docsResult.getOrThrow().output,
                )

            LOG.info("[Security Worker] Risk: {}", securityResult.getOrThrow().output.riskLevel)
            LOG.info("[Quality Worker] Grade: {}", qualityResult.getOrThrow().output.grade)
            LOG.info("[Documentation Worker] Grade: {}", docsResult.getOrThrow().output.grade)

            // Phase 2: Orchestrator synthesizes
            LOG.info("\n--- Phase 2: Orchestrator Synthesis ---")

            val orchestrator = OrchestrationAgenticFunctionProvider.getOrchestrator(ORCHESTRATOR_AGENT_ID)
            val finalResult =
                orchestrator.invoke(
                    OrchestratorAgenticFunction.Input(
                        securityReport = securityJson,
                        qualityReport = qualityJson,
                        documentationReport = docsJson,
                        projectName = projectPath.substringAfterLast("/"),
                    ),
                )

            if (finalResult.isFailure) {
                LOG.error("[Orchestrator] Failed: {}", finalResult.exceptionOrNull()?.message)
                return@runBlocking
            }

            val output = finalResult.getOrThrow().output
            LOG.info("\n=== Project Health Report ===")
            LOG.info("Health Score: {}/10", output.healthScore)
            LOG.info("Summary: {}", output.executiveSummary)
            LOG.info("Action Items:")
            output.actionItems.forEach { item ->
                LOG.info("  [P{}] [{}] {}", item.priority, item.area, item.action)
            }
            LOG.info("Cross-cutting Risks:")
            output.crossCuttingRisks.forEach { risk ->
                LOG.info("  - {}", risk)
            }

            LOG.info(
                "\nFull output: {}",
                JsonSchemaUtil.json.encodeToString(
                    OrchestratorAgenticFunction.Output.serializer(),
                    output,
                ),
            )
        }
}

fun main(args: Array<String>) {
    Runner.run(args)
}
