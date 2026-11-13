package io.github.mbbhalla.agentio.module.patterns.cascade

import io.github.mbbhalla.agentio.core.lib.AgentOutput
import io.github.mbbhalla.agentio.core.model.LLM
import io.github.mbbhalla.agentio.core.model.ThinkingMode
import io.github.mbbhalla.agentio.module.patterns.common.PatternAgents
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory

/**
 * Model cascade pattern. Three tiers of rising cost: a tool-less model with a small output budget,
 * a stronger model with exact-computation tools, and the strongest model with tools plus a
 * thinking pass. Each task is accepted at the first tier whose answer passes both the model's own
 * confidence bar and, where the task allows, a deterministic verifier.
 */
internal object Runner {
    private const val FAST_AGENT_ID = "cascade-fast-a7b8c9d0-0001-0001-0001-000000000001"
    private const val TOOLED_AGENT_ID = "cascade-tooled-a7b8c9d0-0001-0001-0001-000000000002"
    private const val DELIBERATE_AGENT_ID = "cascade-deliberate-a7b8c9d0-0001-0001-0001-000000000003"

    /** Below this self-reported confidence a tier's answer is never accepted, verifier or not. */
    private const val MIN_CONFIDENCE = 0.8

    /** The fast tier is kept cheap with a tight output budget. */
    private const val FAST_TIER_MAX_OUTPUT_TOKENS = 1_500

    private const val HASH_SUBJECT = "AgentIO"
    private const val LONG_WORD = "Pneumonoultramicroscopicsilicovolcanoconiosis"

    /** A task plus the deterministic check (if any) that an answer must pass. */
    private data class Task(
        val prompt: String,
        val verify: (String) -> Boolean = { true },
    )

    private val TASKS =
        listOf(
            Task(prompt = "Rewrite in the passive voice: 'The quick brown fox jumps over the lazy dog.'"),
            Task(
                prompt = "Exactly how many characters are in the word '$LONG_WORD'?",
                verify = { answer -> Regex("\\b${LONG_WORD.length}\\b").containsMatchIn(answer) },
            ),
            Task(
                prompt = "What is the SHA-256 hex digest of the ASCII string '$HASH_SUBJECT'?",
                verify = { answer -> answer.lowercase().contains(Digests.sha256Hex(HASH_SUBJECT)) },
            ),
        )

    private val LOG = LoggerFactory.getLogger(Runner::class.java)

    fun run(args: Array<String>) =
        runBlocking {
            val tasks = args.takeIf { it.isNotEmpty() }?.map { Task(prompt = it) } ?: TASKS

            LOG.info("=== Model Cascade Pattern ===")

            val tasksByPrompt = tasks.associateBy { it.prompt }

            fun accept(
                input: TaskAgenticFunction.Input,
                output: AgentOutput<TaskAgenticFunction.Output>,
            ): Boolean {
                val confident = output.output.confidence >= MIN_CONFIDENCE
                val verified = tasksByPrompt.getValue(input.task).verify(output.output.answer)
                LOG.info("[CASCADE] confidence={} verified={} | {}", output.output.confidence, verified, output.output.method)
                return confident && verified
            }

            val cascade =
                ModelCascade(
                    tiers =
                        listOf(
                            ModelCascade.Tier(
                                name = "fast (Opus 4.5, no tools)",
                                agent =
                                    TaskAgenticFunction(
                                        agentId = FAST_AGENT_ID,
                                        llm = LLM.ANTHROPIC_CLAUDE_OPUS_4_5_V1_CROSS_REGION_INFERENCE,
                                        maxOutputTokens = FAST_TIER_MAX_OUTPUT_TOKENS,
                                    ),
                                accept = ::accept,
                            ),
                            ModelCascade.Tier(
                                name = "tooled (Opus 4.6 + compute tools)",
                                agent =
                                    TaskAgenticFunction(
                                        agentId = TOOLED_AGENT_ID,
                                        llm = LLM.ANTHROPIC_CLAUDE_OPUS_4_6_V1_CROSS_REGION_INFERENCE,
                                        toolsProvider = PatternAgents.inProcessTools("compute", ComputeToolsMcpServer()),
                                    ),
                                accept = ::accept,
                            ),
                            ModelCascade.Tier(
                                name = "deliberate (Opus 5.5 + tools + thinking)",
                                agent =
                                    TaskAgenticFunction(
                                        agentId = DELIBERATE_AGENT_ID,
                                        llm = LLM.ANTHROPIC_CLAUDE_OPUS_5_5_CROSS_REGION_INFERENCE,
                                        toolsProvider = PatternAgents.inProcessTools("compute", ComputeToolsMcpServer()),
                                        thinkingMode = ThinkingMode(maxIterations = 1),
                                    ),
                                accept = ::accept,
                            ),
                        ),
                )

            val outcomes =
                tasks.map { task ->
                    LOG.info("\n--- Task: {} ---", task.prompt)
                    task.prompt to cascade.invoke(TaskAgenticFunction.Input(task = task.prompt))
                }

            LOG.info("\n=== Summary ===")
            outcomes.forEach { (prompt, outcome) ->
                val tokens = outcome.attempts.sumOf { it.inputTokens + it.outputTokens }
                when (outcome) {
                    is ModelCascade.Outcome.Resolved ->
                        LOG.info(
                            "[{} attempt(s), {} tokens] resolved at '{}' -> {}\n    {}",
                            outcome.attempts.size,
                            tokens,
                            outcome.tier,
                            outcome.output.output.answer,
                            prompt,
                        )
                    is ModelCascade.Outcome.Exhausted ->
                        LOG.warn(
                            "[{} attempt(s), {} tokens] no tier produced an acceptable answer\n    {}",
                            outcome.attempts.size,
                            tokens,
                            prompt,
                        )
                }
            }
        }
}

fun main(args: Array<String>) {
    Runner.run(args)
}
