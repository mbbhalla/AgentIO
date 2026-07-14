package io.github.mbbhalla.agentio.examples.camel

import io.github.mbbhalla.agentio.core.lib.AgentOutput
import io.github.mbbhalla.agentio.core.lib.Instructible
import io.github.mbbhalla.agentio.module.compass.function.ConstraintGeneratorAgenticFunction
import io.github.mbbhalla.agentio.module.compass.model.AnalysisResult
import io.github.mbbhalla.agentio.module.solver.Logic
import io.github.mbbhalla.agentio.module.solver.SolverModel
import io.github.mbbhalla.agentio.module.solver.Z3SolverFacade
import kotlinx.serialization.Serializable

/**
 * A single `agentio:` node that turns a grounded [AnalysisResult] into a *provably feasible*
 * reroute decision.
 *
 * This is the demo's load-bearing idea: the LLM decides **what** to do (the
 * [ConstraintGeneratorAgenticFunction] emits an SMTLIB2 encoding of the reroute problem, every
 * bound backed by SQL over the dataset), and the Z3 solver proves **that** the decision is
 * feasible under those constraints ([Z3SolverFacade.solve]). If Z3 returns no model, there is no
 * feasible reroute — the agent cannot hallucinate one into existence.
 *
 * It is deliberately **not** an [io.github.mbbhalla.agentio.core.lib.AbstractAgenticFunction]:
 * it is a *composite* [Instructible] (LLM constraint-gen → deterministic solver) exposed behind
 * one URI, exactly the "any `Instructible` composite is one node" contract the camel module
 * documents. The Camel producer only requires `invoke` to return `Result<AgentOutput<O>>`, which
 * this honors by reusing the constraint generator's [io.github.mbbhalla.agentio.core.model.conversation.Conversation]
 * as the node's transcript so token usage and provenance survive downstream (and dead-letter) routing.
 */
class RerouteSolverAgenticFunction internal constructor(
    private val constraintGenerator: ConstraintGeneratorAgenticFunction,
    private val datasetName: String,
) : Instructible<RerouteSolverAgenticFunction.Input, Result<AgentOutput<RerouteSolverAgenticFunction.Output>>> {
    /**
     * The grounded analysis to encode and solve. The upstream `agentio:etaAnalyzer` node produces
     * this; a `.process {}` step wraps it as this typed input before the solver node.
     */
    @Serializable
    data class Input(
        val analysisResult: AnalysisResult,
    ) : Instructible.WithInstruction {
        override fun instructionId(): String = INSTRUCTION_ID

        override fun instruction(): String = "Encode the reroute decision implied by the analysis as SMTLIB2 and prove feasibility with Z3."

        override fun systemInstruction(): String? = null

        companion object {
            const val INSTRUCTION_ID: String = "reroute-solver"
        }
    }

    /**
     * The provable reroute decision. [feasibleModels] is empty when Z3 finds no satisfying
     * assignment — an explicit, honest "no feasible reroute" rather than a fabricated one.
     */
    @Serializable
    data class Output(
        val smtlibv2Formula: String,
        val explanation: String,
        val feasibleModels: List<Map<String, String>>,
    ) {
        val isFeasible: Boolean get() = feasibleModels.isNotEmpty()
    }

    override suspend fun invoke(input: Input): Result<AgentOutput<Output>> {
        // Stage 1 — LLM: encode the reroute problem as a Z3-checkable SMTLIB2 formula.
        val formulaResult =
            constraintGenerator.invoke(
                ConstraintGeneratorAgenticFunction.Input(
                    analysisResult = input.analysisResult,
                    datasetName = datasetName,
                ),
            )
        val formulaOutput =
            formulaResult.fold(
                onSuccess = { it },
                onFailure = { return Result.failure(it) },
            )

        // Stage 2 — Solver: prove feasibility. No model => no feasible reroute (not a failure).
        val models: Set<SolverModel> =
            runCatching {
                Z3SolverFacade.solve(
                    argSmtlibv2Formula = formulaOutput.output.smtlibv2Formula,
                    limit = SOLVER_MODEL_LIMIT,
                    logic = Logic.ALL,
                )
            }.getOrElse { return Result.failure(it) }

        val output =
            Output(
                smtlibv2Formula = formulaOutput.output.smtlibv2Formula.smtlibv2Formula,
                explanation = formulaOutput.output.explanation,
                feasibleModels = models.map { model -> model.variableValues.mapValues { it.value.toString() } },
            )

        // Reuse the constraint generator's conversation as this composite node's transcript so
        // token usage and provenance survive downstream (and dead-letter) routing.
        return Result.success(
            AgentOutput(
                instructionId = Input.INSTRUCTION_ID,
                conversation = formulaOutput.conversation,
                output = output,
            ),
        )
    }

    companion object {
        /** Cap on the number of distinct feasible reroute assignments Z3 enumerates. */
        private const val SOLVER_MODEL_LIMIT = 5
    }
}
