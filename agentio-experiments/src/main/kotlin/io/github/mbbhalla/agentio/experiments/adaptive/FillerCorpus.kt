package io.github.mbbhalla.agentio.experiments.adaptive

import org.slf4j.LoggerFactory
import kotlin.random.Random

/**
 * Generates filler text to fill a target fraction of the model's context window.
 *
 * Uses a deterministic procedural generator that produces grammatically plausible,
 * semantically diverse paragraphs from a large vocabulary. Each paragraph is unique
 * (seeded by its index), coherent enough that the model must process it to determine
 * whether it contains the answer, and scales to any token count without repetition.
 *
 * Why not hardcoded passages? At 60% fill of a 1M context window, we need ~600K tokens.
 * Hardcoded templates would require thousands of unique paragraphs or heavy recycling
 * that creates artifacts (the model learns to skip repeated content).
 *
 * Why not pure gibberish? Random character strings are trivially skippable — the model
 * would find the needle instantly regardless of position, defeating the experiment.
 * The filler must be coherent enough to require processing.
 */
object FillerCorpus {
    private val LOG = LoggerFactory.getLogger(FillerCorpus::class.java)

    /**
     * Approximate characters per token for Claude's tokenizer (≤ 4.6).
     * Source: Anthropic documentation — ~4.33 chars/token for English text.
     * Inverted: ~0.23 tokens/char. We use chars/token directly for clarity.
     */
    private const val CHARS_PER_TOKEN = 4.33

    /** Target words per generated paragraph. */
    private const val WORDS_PER_PARAGRAPH = 200

    /** Average characters per word (including trailing space). */
    private const val CHARS_PER_WORD = 6.0

    /** Approximate tokens per paragraph. */
    private const val TOKENS_PER_PARAGRAPH = (WORDS_PER_PARAGRAPH * CHARS_PER_WORD / CHARS_PER_TOKEN).toInt()

    /** Sentences per paragraph. */
    private const val SENTENCES_PER_PARAGRAPH = 8

    /** Base seed for deterministic generation across runs. */
    private const val BASE_SEED = 42L

    // --- Vocabulary pools (large enough to avoid obvious repetition) ---

    private val DOMAINS =
        listOf(
            "marine biology",
            "urban planning",
            "medieval history",
            "quantum mechanics",
            "agricultural science",
            "volcanic geology",
            "behavioral economics",
            "textile manufacturing",
            "orbital mechanics",
            "culinary chemistry",
            "forest ecology",
            "digital typography",
            "bridge engineering",
            "sleep research",
            "ceramic arts",
            "trade logistics",
            "atmospheric science",
            "dental medicine",
            "railroad history",
            "game theory",
            "coral reef systems",
            "warehouse automation",
            "folk musicology",
            "polymer chemistry",
            "arctic exploration",
            "tax policy",
            "sports biomechanics",
            "clock making",
            "river hydrology",
            "prison reform",
            "satellite communications",
            "wine fermentation",
            "earthquake prediction",
            "childhood education",
            "submarine design",
            "postal systems",
            "desert ecology",
            "pension management",
            "lighthouse engineering",
            "seed banking",
        )

    private val SUBJECTS =
        listOf(
            "researchers",
            "analysts",
            "engineers",
            "practitioners",
            "scholars",
            "specialists",
            "investigators",
            "consultants",
            "technicians",
            "administrators",
            "coordinators",
            "supervisors",
            "directors",
            "planners",
            "architects",
            "designers",
            "operators",
            "inspectors",
            "auditors",
            "strategists",
            "theorists",
            "experimenters",
            "observers",
            "developers",
            "managers",
            "advisors",
            "examiners",
            "reviewers",
            "facilitators",
            "commissioners",
            "delegates",
            "representatives",
            "advocates",
            "pioneers",
            "mentors",
        )

    private val VERBS =
        listOf(
            "examined",
            "documented",
            "analyzed",
            "measured",
            "compared",
            "evaluated",
            "investigated",
            "catalogued",
            "surveyed",
            "monitored",
            "recorded",
            "assessed",
            "demonstrated",
            "confirmed",
            "established",
            "identified",
            "observed",
            "reported",
            "calculated",
            "estimated",
            "proposed",
            "recommended",
            "implemented",
            "developed",
            "discovered",
            "verified",
            "tested",
            "validated",
            "classified",
            "interpreted",
            "synthesized",
            "formulated",
            "constructed",
            "refined",
            "optimized",
            "standardized",
        )

    private val OBJECTS =
        listOf(
            "the underlying patterns",
            "the structural properties",
            "the temporal variations",
            "the spatial distributions",
            "the causal relationships",
            "the statistical correlations",
            "the environmental factors",
            "the operational parameters",
            "the historical records",
            "the experimental outcomes",
            "the theoretical predictions",
            "the observed anomalies",
            "the regulatory frameworks",
            "the performance metrics",
            "the resource allocations",
            "the quality indicators",
            "the risk assessments",
            "the cost projections",
            "the efficiency ratios",
            "the compliance standards",
            "the safety protocols",
            "the maintenance schedules",
            "the training procedures",
            "the documentation requirements",
            "the boundary conditions",
            "the threshold values",
            "the calibration data",
            "the reference materials",
            "the baseline measurements",
            "the control variables",
        )

    private val CONNECTORS =
        listOf(
            "Furthermore,",
            "In addition,",
            "Subsequently,",
            "Meanwhile,",
            "Consequently,",
            "Nevertheless,",
            "Specifically,",
            "Notably,",
            "Accordingly,",
            "Similarly,",
            "In contrast,",
            "As a result,",
            "For instance,",
            "In particular,",
            "Moreover,",
            "On the other hand,",
            "At the same time,",
            "In this context,",
            "To that end,",
            "With this in mind,",
            "Building on this,",
            "Taken together,",
            "In summary,",
            "Looking ahead,",
            "By comparison,",
            "In practice,",
            "Under these conditions,",
            "Given these findings,",
            "Along these lines,",
            "From this perspective,",
        )

    private val ADJECTIVES =
        listOf(
            "comprehensive",
            "preliminary",
            "longitudinal",
            "systematic",
            "comparative",
            "quantitative",
            "qualitative",
            "empirical",
            "theoretical",
            "experimental",
            "observational",
            "retrospective",
            "prospective",
            "cross-sectional",
            "iterative",
            "incremental",
            "fundamental",
            "applied",
            "interdisciplinary",
            "collaborative",
            "independent",
            "standardized",
            "customized",
            "automated",
            "manual",
            "conventional",
            "innovative",
            "traditional",
            "contemporary",
            "emerging",
        )

    private val QUANTITIES =
        listOf(
            "approximately 47%",
            "nearly 83 units",
            "roughly 12 instances",
            "about 156 samples",
            "over 2,400 records",
            "between 30 and 45 cases",
            "fewer than 200 observations",
            "more than 1,100 data points",
            "an estimated 67 categories",
            "precisely 94 measurements",
            "around 350 participants",
            "up to 78 iterations",
            "a minimum of 23 trials",
            "no fewer than 560 entries",
            "exactly 41 configurations",
            "some 890 specimens",
            "at least 15 variations",
            "close to 220 assessments",
            "upwards of 73 factors",
            "a total of 1,850 readings",
        )

    private val TIMEFRAMES =
        listOf(
            "during the initial phase",
            "over a three-year period",
            "in the subsequent analysis",
            "throughout the observation window",
            "following the preliminary review",
            "prior to the final assessment",
            "within the first quarter",
            "across multiple seasons",
            "after extensive calibration",
            "before the standardization effort",
            "in the early stages",
            "during peak activity periods",
            "at regular intervals",
            "over consecutive cycles",
            "between scheduled evaluations",
        )

    /**
     * Generate filler paragraphs to fill a target token count.
     *
     * Each paragraph is deterministically generated from a unique seed, ensuring:
     * - No two paragraphs are identical
     * - Results are reproducible across runs
     * - Scales to any token count without repetition artifacts
     *
     * @param targetTokens The total number of filler tokens to generate.
     * @return List of filler paragraph strings.
     */
    fun generate(targetTokens: Int): List<String> {
        require(targetTokens > 0) { "targetTokens must be > 0, was $targetTokens" }

        val numParagraphs = (targetTokens / TOKENS_PER_PARAGRAPH).coerceAtLeast(1)

        val paragraphs =
            (0 until numParagraphs).map { index ->
                generateParagraph(index)
            }

        val actualChars = paragraphs.sumOf { it.length }
        val estimatedTokens = (actualChars / CHARS_PER_TOKEN).toInt()

        LOG.debug(
            "Generated {} filler paragraphs (~{} tokens from {} chars) targeting {} tokens",
            paragraphs.size,
            estimatedTokens,
            actualChars,
            targetTokens,
        )

        return paragraphs
    }

    /**
     * Generate a single unique paragraph from a deterministic seed.
     * Each paragraph covers a randomly selected domain and uses varied sentence structures.
     */
    private fun generateParagraph(index: Int): String {
        val rng = Random(BASE_SEED + index)

        val domain = DOMAINS[rng.nextInt(DOMAINS.size)]
        val sentences =
            buildList {
                // Opening sentence introduces the domain
                add(buildOpeningSentence(rng, domain, index))

                // Body sentences with varied structure
                repeat(SENTENCES_PER_PARAGRAPH - 2) {
                    add(buildBodySentence(rng, domain))
                }

                // Closing sentence
                add(buildClosingSentence(rng, domain))
            }

        return sentences.joinToString(" ")
    }

    private fun buildOpeningSentence(
        rng: Random,
        domain: String,
        index: Int,
    ): String {
        val adj = ADJECTIVES[rng.nextInt(ADJECTIVES.size)]
        val subject = SUBJECTS[rng.nextInt(SUBJECTS.size)]
        val verb = VERBS[rng.nextInt(VERBS.size)]
        val obj = OBJECTS[rng.nextInt(OBJECTS.size)]
        val timeframe = TIMEFRAMES[rng.nextInt(TIMEFRAMES.size)]
        return "In the field of $domain, $subject $verb $obj $timeframe " +
            "as part of a $adj study (reference: section ${index + 1})."
    }

    private fun buildBodySentence(
        rng: Random,
        domain: String,
    ): String {
        val connector = CONNECTORS[rng.nextInt(CONNECTORS.size)]
        val subject = SUBJECTS[rng.nextInt(SUBJECTS.size)]
        val verb = VERBS[rng.nextInt(VERBS.size)]
        val obj = OBJECTS[rng.nextInt(OBJECTS.size)]
        val quantity = QUANTITIES[rng.nextInt(QUANTITIES.size)]
        val adj = ADJECTIVES[rng.nextInt(ADJECTIVES.size)]
        val timeframe = TIMEFRAMES[rng.nextInt(TIMEFRAMES.size)]

        // Vary sentence structure to avoid monotony
        return when (rng.nextInt(4)) {
            0 ->
                "$connector $subject $verb $obj involving $quantity $timeframe, " +
                    "yielding $adj results relevant to $domain."
            1 ->
                "$connector the $adj analysis of $domain revealed that $subject " +
                    "$verb $obj encompassing $quantity."
            2 ->
                "$connector $timeframe, $subject working in $domain $verb $obj " +
                    "and documented $quantity through $adj methods."
            else ->
                "$connector $quantity were attributed to $obj after $subject " +
                    "$verb the $adj aspects of $domain $timeframe."
        }
    }

    private fun buildClosingSentence(
        rng: Random,
        domain: String,
    ): String {
        val adj = ADJECTIVES[rng.nextInt(ADJECTIVES.size)]
        val subject = SUBJECTS[rng.nextInt(SUBJECTS.size)]
        val obj = OBJECTS[rng.nextInt(OBJECTS.size)]
        return "These findings underscore the importance of $adj approaches " +
            "as $subject continue to investigate $obj within $domain."
    }

    // --- Vote sentence generation for Distributed Counting experiment ---

    private val VOTE_VERBS_FOR =
        listOf(
            "endorsed",
            "backed",
            "supported",
            "voted in favor of",
            "expressed approval for",
            "championed",
            "advocated for",
            "threw their weight behind",
            "rallied behind",
            "gave their backing to",
            "came out in support of",
            "aligned with",
            "signaled their preference for",
            "opted for",
            "chose to support",
            "declared their support for",
            "affirmed their commitment to",
            "registered their vote for",
            "indicated a preference for",
            "stood behind",
        )

    private val VOTE_VERBS_AGAINST =
        listOf(
            "opposed",
            "voted against",
            "rejected",
            "declined to support",
            "expressed reservations about",
            "raised objections to",
            "pushed back against",
            "argued against",
            "dissented from",
            "withheld support for",
        )

    private val VOTE_CONTEXTS =
        listOf(
            "during the annual review session",
            "at the quarterly planning meeting",
            "in the cross-departmental consultation",
            "following the budget presentation",
            "after reviewing the impact assessment",
            "in response to the feasibility study",
            "during the stakeholder feedback round",
            "at the strategic alignment workshop",
            "in the final deliberation phase",
            "after the technical evaluation briefing",
            "during the governance committee hearing",
            "at the resource allocation summit",
            "following the risk assessment review",
            "in the policy advisory session",
            "during the operational readiness check",
        )

    /**
     * Generate a vote sentence for the Distributed Counting experiment.
     * Each vote is for either Proposal A or Proposal B, expressed in varied natural language.
     *
     * @param index Paragraph index (used for deterministic seeding).
     * @param domain The domain of the paragraph this vote is embedded in.
     * @param voteForA Whether this vote is for Proposal A (true) or Proposal B (false).
     * @return A natural language sentence expressing the vote.
     */
    fun generateVoteSentence(
        index: Int,
        domain: String,
        voteForA: Boolean,
    ): String {
        val rng = Random(BASE_SEED + index + 99999) // offset seed to avoid correlation with paragraph
        val subject = SUBJECTS[rng.nextInt(SUBJECTS.size)]
        val context = VOTE_CONTEXTS[rng.nextInt(VOTE_CONTEXTS.size)]

        val (proposal, verb) =
            if (voteForA) {
                "Proposal A" to VOTE_VERBS_FOR[rng.nextInt(VOTE_VERBS_FOR.size)]
            } else {
                "Proposal B" to VOTE_VERBS_FOR[rng.nextInt(VOTE_VERBS_FOR.size)]
            }

        return when (rng.nextInt(4)) {
            0 -> "The $subject from the $domain division $verb $proposal $context."
            1 -> "$context, $subject representing $domain $verb $proposal."
            2 -> "On behalf of the $domain group, $subject $verb $proposal $context."
            else -> "The $domain delegation's $subject $verb $proposal $context."
        }
    }
}
