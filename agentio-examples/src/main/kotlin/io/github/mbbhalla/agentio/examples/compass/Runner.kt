package io.github.mbbhalla.agentio.examples.compass

import io.github.mbbhalla.agentio.core.common.JsonSchemaUtil
import io.github.mbbhalla.agentio.module.compass.function.AnalyzerAgenticFunction
import io.github.mbbhalla.agentio.module.compass.function.ConstraintGeneratorAgenticFunction
import io.github.mbbhalla.agentio.module.solver.Logic
import io.github.mbbhalla.agentio.module.solver.Z3SolverFacade
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory

internal object Runner {
    private val LOG = LoggerFactory.getLogger(Runner::class.java)

    fun run(objective: String) =
        runBlocking {
            val env = SupplyChainDatabase.environment

            LOG.info("=== STAGE 1: Analyzer — '{}'", objective)
            val analyzer =
                AnalyzerAgenticFunction.create(
                    env = env,
                    problemDomain = "Supply Chain Analysis",
                )
            val analysisTry =
                analyzer.invoke(
                    AnalyzerAgenticFunction.Input(
                        objective = objective,
                        datasetName = SupplyChainDatabase.DATASET_NAME,
                    ),
                )
            check(analysisTry.isSuccess) { "Analyzer failed: ${analysisTry.exceptionOrNull()}" }
            val analysisOutput = analysisTry.getOrThrow().output

            LOG.info("Analyzer produced {} result items", analysisOutput.analysisResult.resultItems.size)
            LOG.info(
                "AnalysisResult JSON:\n{}",
                JsonSchemaUtil.json.encodeToString(AnalyzerAgenticFunction.Output.serializer(), analysisOutput),
            )

            LOG.info("=== STAGE 2: ConstraintGenerator")
            val constraintGenerator =
                ConstraintGeneratorAgenticFunction.create(
                    env = env,
                    variables = ALL_SUPPLY_CHAIN_VARIABLES,
                    problemDomain = "Supply Chain Optimization (SMTLIB2 / Z3)",
                )
            val formulaTry =
                constraintGenerator.invoke(
                    ConstraintGeneratorAgenticFunction.Input(
                        analysisResult = analysisOutput.analysisResult,
                        datasetName = SupplyChainDatabase.DATASET_NAME,
                    ),
                )
            check(formulaTry.isSuccess) { "ConstraintGenerator failed: ${formulaTry.exceptionOrNull()}" }
            val formulaOutput = formulaTry.getOrThrow().output

            LOG.info("ConstraintGenerator produced SMTLIB2 formula:\n{}", formulaOutput.smtlibv2Formula.smtlibv2Formula)
            LOG.info("Explanation:\n{}", formulaOutput.explanation)

            LOG.info("=== STAGE 3: Solver")
            val models =
                Z3SolverFacade.solve(
                    argSmtlibv2Formula = formulaOutput.smtlibv2Formula,
                    limit = 5,
                    logic = Logic.ALL,
                )
            LOG.info("Solver returned {} model(s)", models.size)
            models.forEachIndexed { i, model -> LOG.info("Model #{}: {}", i + 1, model.variableValues) }
        }
}

fun main(args: Array<String>) {
    val objective =
        args.firstOrNull()
            ?: "Overstock at site DC-SEATTLE for product SSD-1TB-NVMe in the month of June 2025"
    Runner.run(objective)
}
