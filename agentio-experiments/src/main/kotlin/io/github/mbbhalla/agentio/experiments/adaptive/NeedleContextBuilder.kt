package io.github.mbbhalla.agentio.experiments.adaptive

import io.github.mbbhalla.agentio.core.model.LLM
import org.slf4j.LoggerFactory

/**
 * A needle: a fact embedded in the filler at a specific fractional depth.
 *
 * @property text The needle sentence containing the retrievable fact.
 * @property code The exact string the model must produce to score a match.
 * @property depth Fractional position in [0.0, 1.0] among filler passages.
 * @property importanceScore Importance hint for the adaptive CMM segment extractor.
 * @property isTarget Whether this is the needle the retrieval question asks about.
 */
data class Needle(
    val text: String,
    val code: String,
    val depth: Double,
    val importanceScore: Double,
    val isTarget: Boolean,
) {
    init {
        require(depth in 0.0..1.0) { "depth must be in [0.0, 1.0], was $depth" }
        require(importanceScore in 0.0..1.0) {
            "importanceScore must be in [0.0, 1.0], was $importanceScore"
        }
        require(code.isNotBlank()) { "code must not be blank" }
    }
}

/**
 * The assembled context for one experiment trial: filler chunks interleaved with needles,
 * plus the retrieval question and expected answer.
 *
 * [contextBlocks] contains a small number of large text chunks (typically 10-20),
 * each becoming one [io.github.mbbhalla.agentio.cmm.impl.adaptive.ContextSegment] for the
 * adaptive CMM. This keeps the segment count manageable regardless of model window size.
 */
data class NeedleContext(
    /** Ordered list of text blocks (chunked filler + needle sentences). */
    val contextBlocks: List<String>,

    /** The retrieval question to ask the model. */
    val retrievalQuestion: String,

    /** The exact code string(s) the model must produce. */
    val expectedCodes: List<String>,

    /** All needles embedded in this context. */
    val needles: List<Needle>,

    /** Approximate total tokens in the context. */
    val approximateTokens: Int,

    /** Ground truth vote counts for Distributed Counting experiment. Null for other experiments. */
    val groundTruthVotes: VoteCounts? = null,
)

/**
 * Ground truth vote counts for the Distributed Counting experiment.
 */
data class VoteCounts(
    val proposalA: Int,
    val proposalB: Int,
) {
    val total: Int get() = proposalA + proposalB
    val winner: String get() = if (proposalA > proposalB) "Proposal A" else "Proposal B"
}

/**
 * Builds the context (filler + needles) for each experiment type, scaled to the model's
 * context window.
 *
 * Filler paragraphs are generated procedurally (see [FillerCorpus]) and then bundled
 * into [TARGET_SEGMENT_COUNT] chunks so the adaptive CMM operates on a manageable
 * number of segments regardless of whether the model has a 200K or 1M context window.
 */
object NeedleContextBuilder {

    private val LOG = LoggerFactory.getLogger(NeedleContextBuilder::class.java)

    /**
     * Fraction of the model's **available input budget** to fill with filler + needles.
     *
     * The available input budget is `maxContextTokens - maxOutputTokens` — the space
     * left for input after Bedrock reserves room for the model's output.
     *
     * Set at 80% to push the context into the range where the lost-in-the-middle
     * effect manifests strongly. At lower fill fractions, capable models (especially
     * with large context windows) find the needle trivially and both CMM ON/OFF
     * saturate at 100% accuracy, producing no measurable signal.
     *
     * A 10% buffer is reserved for prompt overhead (instruction text, JSON schemas,
     * output format = ~5-10K tokens) and token estimation error.
     */
    private const val FILL_FRACTION = 0.8

    /** Buffer reserved for prompt overhead and token estimation error. */
    private const val OVERHEAD_BUFFER_FRACTION = 0.1

    /**
     * Target number of context segments (chunks) for the adaptive CMM.
     * Each chunk becomes one ContentBlock.Text → one ContextSegment.
     * Kept in the 10-20 range to match the CMM's design assumptions
     * (the paper's worked example uses 6 segments).
     */
    private const val TARGET_SEGMENT_COUNT = 20

    /**
     * Approximate characters per token for Claude's tokenizer (≤ 4.6).
     * Source: Anthropic documentation — ~4.33 chars/token for English text.
     */
    private const val CHARS_PER_TOKEN = 4.33

    fun build(
        experimentType: ExperimentType,
        llm: LLM,
    ): NeedleContext = when (experimentType) {
        ExperimentType.NEEDLE_IN_HAYSTACK -> buildSingleNeedle(llm)
        ExperimentType.MULTI_NEEDLE -> buildMultiNeedle(llm)
        ExperimentType.DISTRIBUTED_COUNTING -> buildDistributedCounting(llm)
    }

    /**
     * Compute the filler token target for a given model.
     *
     * Available input budget = maxContextTokens - maxOutputTokens
     * Filler target = availableBudget × FILL_FRACTION × (1 - OVERHEAD_BUFFER_FRACTION)
     *
     * Example for Opus 4.6 (1M context, 128K output):
     *   available = 1,000,000 - 128,000 = 872,000
     *   filler = 872,000 × 0.8 × 0.9 = ~627,840 tokens
     *
     * Example for Sonnet 3.7 (200K context, 128K output):
     *   available = 200,000 - 128,000 = 72,000
     *   filler = 72,000 × 0.8 × 0.9 = ~51,840 tokens
     */
    private fun computeFillerTarget(llm: LLM): Int {
        val availableInputBudget = llm.maxContextTokens - llm.maxOutputTokens
        return (availableInputBudget * FILL_FRACTION * (1.0 - OVERHEAD_BUFFER_FRACTION)).toInt()
    }

    private fun buildSingleNeedle(llm: LLM): NeedleContext {
        val targetTokens = computeFillerTarget(llm)
        val fillerParagraphs = FillerCorpus.generate(targetTokens)

        val needle = Needle(
            text = "The secret code for Project Aurora is ZEPHYR-7742.",
            code = "ZEPHYR-7742",
            depth = 0.5,
            importanceScore = 0.95,
            isTarget = true,
        )

        val contextBlocks = buildChunkedContext(fillerParagraphs, listOf(needle))

        LOG.debug(
            "Built single-needle context for {}: {} paragraphs → {} chunks, " +
                "needle at depth 0.5, ~{} tokens",
            llm.name,
            fillerParagraphs.size,
            contextBlocks.size,
            targetTokens,
        )

        return NeedleContext(
            contextBlocks = contextBlocks,
            retrievalQuestion = "What is the secret code for Project Aurora?",
            expectedCodes = listOf(needle.code),
            needles = listOf(needle),
            approximateTokens = targetTokens,
        )
    }

    private fun buildMultiNeedle(llm: LLM): NeedleContext {
        val targetTokens = computeFillerTarget(llm)
        val fillerParagraphs = FillerCorpus.generate(targetTokens)

        val needles = listOf(
            Needle(
                text = "The access key for the Berlin laboratory is COBALT-3391.",
                code = "COBALT-3391",
                depth = 0.1,
                importanceScore = 0.5,
                isTarget = false,
            ),
            Needle(
                text = "The entry code for the Sydney warehouse is MARBLE-5528.",
                code = "MARBLE-5528",
                depth = 0.3,
                importanceScore = 0.5,
                isTarget = false,
            ),
            Needle(
                text = "The secret code for Project Aurora is ZEPHYR-7742.",
                code = "ZEPHYR-7742",
                depth = 0.5,
                importanceScore = 0.95,
                isTarget = true,
            ),
            Needle(
                text = "The vault combination for the Montreal office is PRISM-8864.",
                code = "PRISM-8864",
                depth = 0.7,
                importanceScore = 0.5,
                isTarget = false,
            ),
            Needle(
                text = "The launch sequence for the Cape Town facility is QUARTZ-1107.",
                code = "QUARTZ-1107",
                depth = 0.9,
                importanceScore = 0.5,
                isTarget = false,
            ),
        )

        val contextBlocks = buildChunkedContext(fillerParagraphs, needles)

        LOG.debug(
            "Built multi-needle context for {}: {} paragraphs → {} chunks, " +
                "{} needles, ~{} tokens",
            llm.name,
            fillerParagraphs.size,
            contextBlocks.size,
            needles.size,
            targetTokens,
        )

        return NeedleContext(
            contextBlocks = contextBlocks,
            retrievalQuestion = "What is the secret code for Project Aurora?",
            expectedCodes = listOf("ZEPHYR-7742"),
            needles = needles,
            approximateTokens = targetTokens,
        )
    }

    /**
     * Build context for the Distributed Counting experiment.
     *
     * Every filler paragraph gets a vote sentence woven into it. Votes are for
     * Proposal A or Proposal B, with a deterministic split that's close but not
     * equal (making the task non-trivial). The model must aggregate all votes
     * and report counts.
     */
    private fun buildDistributedCounting(llm: LLM): NeedleContext {
        val targetTokens = computeFillerTarget(llm)
        val fillerParagraphs = FillerCorpus.generate(targetTokens)

        // Deterministic vote assignment: ~55% A, ~45% B (close split)
        val voteRng = kotlin.random.Random(VOTE_SEED)
        var countA = 0
        var countB = 0

        val paragraphsWithVotes = fillerParagraphs.mapIndexed { index, paragraph ->
            val voteForA = voteRng.nextDouble() < 0.55
            if (voteForA) countA++ else countB++

            // Extract the domain from the paragraph (it's in the opening sentence)
            val domain = extractDomain(paragraph)
            val voteSentence = FillerCorpus.generateVoteSentence(index, domain, voteForA)

            // Weave the vote into the middle of the paragraph
            val sentences = paragraph.split(". ").toMutableList()
            val insertPos = (sentences.size / 2).coerceAtLeast(1)
            sentences.add(insertPos, voteSentence)
            sentences.joinToString(". ")
        }

        val groundTruth = VoteCounts(proposalA = countA, proposalB = countB)

        // Chunk the paragraphs (no needles for this experiment)
        val contextBlocks = buildChunkedContext(paragraphsWithVotes, emptyList())

        LOG.debug(
            "Built distributed-counting context for {}: {} paragraphs → {} chunks, " +
                "votes: A={}, B={}, total={}, ~{} tokens",
            llm.name,
            paragraphsWithVotes.size,
            contextBlocks.size,
            countA,
            countB,
            countA + countB,
            targetTokens,
        )

        return NeedleContext(
            contextBlocks = contextBlocks,
            retrievalQuestion = "Count ALL the votes for Proposal A and Proposal B recorded " +
                "throughout the entire context. Report the exact count for each proposal.",
            expectedCodes = listOf(groundTruth.winner),
            needles = emptyList(),
            approximateTokens = targetTokens,
            groundTruthVotes = groundTruth,
        )
    }

    /** Seed for deterministic vote assignment. */
    private const val VOTE_SEED = 12345L

    /** Extract the domain name from a generated paragraph's opening sentence. */
    private fun extractDomain(paragraph: String): String {
        // Opening sentence format: "In the field of <domain>, ..."
        val match = "In the field of ([^,]+),".toRegex().find(paragraph)
        return match?.groupValues?.get(1) ?: "general studies"
    }

    /**
     * Bundle filler paragraphs into [TARGET_SEGMENT_COUNT] chunks, inserting needles
     * at their specified fractional depths within the appropriate chunk.
     *
     * Each chunk becomes one ContentBlock.Text in the conversation → one ContextSegment
     * for the adaptive CMM. This keeps the segment count constant regardless of how many
     * filler paragraphs were generated (which scales with the model's context window).
     *
     * Needles are inserted into the chunk that corresponds to their depth, ensuring the
     * needle text is embedded within a larger block of filler — not isolated as its own
     * segment (which would make it trivially identifiable).
     */
    private fun buildChunkedContext(
        fillerParagraphs: List<String>,
        needles: List<Needle>,
    ): List<String> {
        val totalParagraphs = fillerParagraphs.size
        val paragraphsPerChunk = (totalParagraphs / TARGET_SEGMENT_COUNT).coerceAtLeast(1)

        // Sort needles by depth for ordered insertion
        val sortedNeedles = needles.sortedBy { it.depth }

        // Build chunks
        val chunks = mutableListOf<String>()
        var paragraphIndex = 0

        for (chunkIndex in 0 until TARGET_SEGMENT_COUNT) {
            val chunkEnd = if (chunkIndex == TARGET_SEGMENT_COUNT - 1) {
                totalParagraphs // Last chunk gets all remaining paragraphs
            } else {
                ((chunkIndex + 1) * paragraphsPerChunk).coerceAtMost(totalParagraphs)
            }

            val chunkParagraphs = mutableListOf<String>()

            // Add filler paragraphs for this chunk
            while (paragraphIndex < chunkEnd) {
                chunkParagraphs.add(fillerParagraphs[paragraphIndex])
                paragraphIndex++
            }

            // Insert any needles whose depth falls within this chunk's range
            val chunkStartFraction = chunkIndex.toDouble() / TARGET_SEGMENT_COUNT
            val chunkEndFraction = (chunkIndex + 1).toDouble() / TARGET_SEGMENT_COUNT

            for (needle in sortedNeedles) {
                if (needle.depth >= chunkStartFraction && needle.depth < chunkEndFraction) {
                    // Insert needle in the middle of this chunk's paragraphs
                    val insertPos = (chunkParagraphs.size / 2).coerceAtLeast(0)
                    chunkParagraphs.add(insertPos, needle.text)
                }
            }

            // Handle needles at depth 1.0 (goes in the last chunk)
            if (chunkIndex == TARGET_SEGMENT_COUNT - 1) {
                for (needle in sortedNeedles) {
                    if (needle.depth >= chunkEndFraction) {
                        chunkParagraphs.add(needle.text)
                    }
                }
            }

            if (chunkParagraphs.isNotEmpty()) {
                chunks.add(chunkParagraphs.joinToString("\n\n"))
            }
        }

        return chunks
    }

    /**
     * Estimate token count for a text string using character-based estimation.
     */
    fun estimateTokens(text: String): Int =
        (text.length / CHARS_PER_TOKEN).toInt()
}
