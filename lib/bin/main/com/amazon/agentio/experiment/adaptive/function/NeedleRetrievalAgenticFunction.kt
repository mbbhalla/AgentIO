package com.amazon.agentio.experiment.adaptive.function

import aws.sdk.kotlin.services.bedrockruntime.BedrockRuntimeClient
import com.amazon.agentio.common.Description
import com.amazon.agentio.experiment.adaptive.ExperimentType
import com.amazon.agentio.experiment.adaptive.NeedleContext
import com.amazon.agentio.experiment.adaptive.NeedleContextBuilder
import com.amazon.agentio.lib.AbstractAgenticFunction
import com.amazon.agentio.lib.Instructible
import com.amazon.agentio.lib.ctx.cmm.ContextMemoryManagers
import com.amazon.agentio.lib.ctx.cmm.NoOperationContextMemoryManager
import com.amazon.agentio.lib.ctx.cmm.adaptive.AdaptiveConfig
import com.amazon.agentio.lib.ctx.cmm.adaptive.AdaptiveContextMemoryManager
import com.amazon.agentio.lib.ctx.provider.ContextProvider
import com.amazon.agentio.lib.ctx.provider.ContextProviders
import com.amazon.agentio.lib.tool.EmptyToolsProvider
import com.amazon.agentio.model.AgentConfiguration
import com.amazon.agentio.model.LLM
import com.amazon.agentio.model.LanguageModelParameters
import com.amazon.agentio.model.Temperature
import com.amazon.agentio.model.ThinkingMode
import kotlinx.serialization.Serializable
import kotlin.reflect.KClass
import kotlin.time.Duration.Companion.minutes

/**
 * Agentic function for needle retrieval experiments.
 *
 * The model receives a large context (filler + needle(s)) via [ContextProvider]s and
 * must answer a retrieval question. The output is scored for exact-match on the needle code.
 *
 * This function is used by both Needle-in-a-Haystack and Multi-Needle experiments.
 * The difference is in the [NeedleContext] built by [NeedleContextBuilder].
 */
class NeedleRetrievalAgenticFunction(
    agentConfiguration: AgentConfiguration,
) : AbstractAgenticFunction<
    NeedleRetrievalAgenticFunction.NeedleRetrievalInput,
    NeedleRetrievalAgenticFunction.NeedleRetrievalOutput,
    >(agentConfiguration) {

    @Serializable
    data class NeedleRetrievalInput(
        @field:Description("The retrieval question to answer based on the provided context")
        val question: String,

        @field:Description("Whether this is a counting/aggregation task (vs exact retrieval)")
        val isCountingTask: Boolean = false,
    ) : Instructible.WithInstruction {
        override fun instructionId(): String = "needle-retrieval"

        override fun instruction(): String = if (isCountingTask) {
            """
                You have been given a large body of text as context.
                Throughout this text, various groups have cast votes for either Proposal A or Proposal B.
                The votes are expressed in varied natural language (e.g., "endorsed", "backed", "supported", 
                "voted in favor of", "championed", etc.).
                
                Your task: carefully read through ALL of the provided context and count every single vote.
                
                Report your answer as:
                - The total count of votes for Proposal A
                - The total count of votes for Proposal B
                - Which proposal received more votes
                
                Be as accurate as possible. Every paragraph may contain a vote.
            """.trimIndent()
        } else {
            """
                You have been given a large body of text as context.
                Carefully read through ALL of the provided context.
                Then answer this question: '$question'
                
                Your answer MUST include the exact code/value from the context.
                Do not make up or guess any codes. Only report what you find in the context.
            """.trimIndent()
        }

        override fun systemInstruction(): String = if (isCountingTask) {
            """
                You are a precise data aggregation assistant.
                Your task is to count all votes for Proposal A and Proposal B in the provided text.
                Votes are expressed in varied natural language throughout the text.
                Read every paragraph carefully. Report exact counts.
                Be concise and precise in your answer.
            """.trimIndent()
        } else {
            """
                You are a precise information retrieval assistant.
                Your task is to find specific facts embedded in a large body of text.
                Read the entire context carefully and answer the question with the exact value found.
                Be concise. Include the exact code or value in your answer.
            """.trimIndent()
        }
    }

    @Serializable
    data class NeedleRetrievalOutput(
        @field:Description("The answer to the retrieval question, including the exact code found")
        val answer: String,

        @field:Description("Count of votes for Proposal A (only for counting tasks, 0 otherwise)")
        val proposalACount: Int = 0,

        @field:Description("Count of votes for Proposal B (only for counting tasks, 0 otherwise)")
        val proposalBCount: Int = 0,
    )

    override fun getInputKClass(): KClass<NeedleRetrievalInput> = NeedleRetrievalInput::class
    override fun getOutputKClass(): KClass<NeedleRetrievalOutput> = NeedleRetrievalOutput::class
}

/**
 * Factory that creates a fresh [NeedleRetrievalAgenticFunction] with isolated state.
 *
 * Each call to [create] produces a new instance with:
 * - A fresh [AdaptiveContextMemoryManager] (no heatmap leakage between trials)
 * - A fresh [BedrockRuntimeClient]
 * - The needle context injected via [ContextProvider]s
 *
 * The factory is the unit of isolation between trials in the evaluator.
 */
object NeedleRetrievalFunctionFactory {

    /** Temperature for experiment runs — low for reproducibility. */
    private val EXPERIMENT_TEMPERATURE = Temperature(0.1f)

    /**
     * Create a fresh [NeedleRetrievalAgenticFunction] for one trial.
     *
     * @param experimentType Which experiment (single-needle or multi-needle).
     * @param llm Which model to use.
     * @param adaptiveCmmEnabled Whether to enable the adaptive CMM.
     * @param bedrockRegion AWS region for the Bedrock client.
     */
    fun create(
        experimentType: ExperimentType,
        llm: LLM,
        adaptiveCmmEnabled: Boolean,
        bedrockRegion: String,
    ): NeedleRetrievalAgenticFunction {
        val needleContext = NeedleContextBuilder.build(experimentType, llm)

        val contextMemoryManagers = if (adaptiveCmmEnabled) {
            ContextMemoryManagers(
                value = listOf(
                    AdaptiveContextMemoryManager(
                        config = AdaptiveConfig(
                            measurementFrequency = 1,
                            enablePiggyback = true,
                        ),
                    ),
                ),
            )
        } else {
            ContextMemoryManagers(
                value = listOf(NoOperationContextMemoryManager),
            )
        }

        // Inject the filler + needle context via ContextProviders.
        // Each context block becomes a separate text content block in the initial
        // User message, which the adaptive CMM's DefaultSegmentExtractor will
        // treat as individual segments for reshuffling.
        val contextProviders = ContextProviders(
            value = needleContext.contextBlocks.map { block ->
                NeedleContextProvider(block)
            },
        )

        val agentConfiguration = AgentConfiguration(
            agentId = "needle-retrieval-${experimentType.name}-${llm.name}-$adaptiveCmmEnabled",
            problemDomain = "Information Retrieval",
            languageModelParameters = LanguageModelParameters(
                llm = llm,
                temperature = EXPERIMENT_TEMPERATURE,
                additionalModelRequestFields = llm.additionalModelRequestFields,
            ),
            bedrockRuntimeClient = BedrockRuntimeClient {
                this.region = bedrockRegion
                this.httpClient {
                    socketReadTimeout = 15.minutes
                }
            },
            toolsProvider = EmptyToolsProvider,
            contextMemoryManagers = contextMemoryManagers,
            contextProviders = contextProviders,
            thinkingMode = ThinkingMode(maxIterations = 0),
            maxTurnLimit = 10,
        )

        return NeedleRetrievalAgenticFunction(agentConfiguration)
    }
}

/**
 * A [ContextProvider] that returns a fixed text block.
 * Used to inject filler passages and needle sentences into the conversation.
 */
private class NeedleContextProvider(
    private val text: String,
) : ContextProvider {
    override fun <I : Instructible.WithInstruction> context(input: I): String = text
}
