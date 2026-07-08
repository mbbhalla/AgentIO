package io.github.mbbhalla.agentio.examples.adversarial

import io.github.mbbhalla.agentio.core.common.JsonSchemaUtil
import io.github.mbbhalla.agentio.examples.adversarial.function.AdversarialAgenticFunctionProvider
import io.github.mbbhalla.agentio.examples.adversarial.function.CriticAgenticFunction
import io.github.mbbhalla.agentio.examples.adversarial.function.DesignerAgenticFunction
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory

internal object Runner {
    private const val DESIGNER_AGENT_ID = "adv-designer-a1b2c3d4-e5f6-7890-abcd-ef0123456789"
    private const val CRITIC_AGENT_ID = "adv-critic-b2c3d4e5-f6a7-8901-bcde-f01234567890"
    private const val MAX_ITERATIONS = 3

    private val LOG = LoggerFactory.getLogger(Runner::class.java)

    fun run(args: Array<String>) =
        runBlocking {
            val requirements =
                args.getOrElse(0) {
                    """
                    Build an e-commerce API for a marketplace. Users can create accounts, list products,
                    add items to cart, and place orders. Products must have categories and search must
                    support filtering by price range. Orders require payment processing and must track
                    delivery status. Admin users can manage product listings and view analytics.
                    """.trimIndent()
                }

            LOG.info("=== Adversarial API Design Pattern ===")
            LOG.info("Requirements: {}", requirements)

            val designer = AdversarialAgenticFunctionProvider.getDesigner(DESIGNER_AGENT_ID)
            val critic = AdversarialAgenticFunctionProvider.getCritic(CRITIC_AGENT_ID)

            var criticFeedback = ""
            var finalDesign: DesignerAgenticFunction.Output? = null

            for (iteration in 1..MAX_ITERATIONS) {
                LOG.info("\n--- Iteration {} of {} ---", iteration, MAX_ITERATIONS)

                LOG.info("[Designer] Producing API design...")
                val designResult =
                    designer.invoke(
                        DesignerAgenticFunction.Input(
                            requirements = requirements,
                            criticFeedback = criticFeedback,
                            iteration = iteration,
                        ),
                    )

                if (designResult.isFailure) {
                    LOG.error("[Designer] Failed: {}", designResult.exceptionOrNull()?.message)
                    return@runBlocking
                }

                val designOutput = designResult.getOrThrow().output
                finalDesign = designOutput
                LOG.info("[Designer] Produced {} endpoints, {} data models", designOutput.endpoints.size, designOutput.dataModels.size)

                val designJson =
                    JsonSchemaUtil.json.encodeToString(
                        DesignerAgenticFunction.Output.serializer(),
                        designOutput,
                    )

                LOG.info("[Critic] Reviewing design...")
                val criticResult =
                    critic.invoke(
                        CriticAgenticFunction.Input(
                            designJson = designJson,
                            originalRequirements = requirements,
                            iteration = iteration,
                        ),
                    )

                if (criticResult.isFailure) {
                    LOG.error("[Critic] Failed: {}", criticResult.exceptionOrNull()?.message)
                    return@runBlocking
                }

                val criticOutput = criticResult.getOrThrow().output
                LOG.info(
                    "[Critic] Verdict: {} | Issues: {} | Suggestions: {}",
                    criticOutput.verdict,
                    criticOutput.criticalIssues.size,
                    criticOutput.suggestions.size,
                )

                if (criticOutput.verdict == "APPROVED") {
                    LOG.info("=== Design APPROVED after {} iteration(s) ===", iteration)
                    break
                }

                criticFeedback = criticOutput.feedbackForDesigner
                LOG.info("[Critic] Feedback: {}", criticFeedback)
            }

            LOG.info("\n=== Final Design ===")
            finalDesign?.let { design ->
                LOG.info(JsonSchemaUtil.json.encodeToString(DesignerAgenticFunction.Output.serializer(), design))
            }
        }
}

fun main(args: Array<String>) {
    Runner.run(args)
}
