package io.github.mbbhalla.agentio.experiments.adaptive

import io.github.mbbhalla.agentio.experiments.adaptive.function.NeedleRetrievalAgenticFunction.NeedleRetrievalOutput
import io.github.mbbhalla.agentio.core.lib.eval.AgenticFunctionEvaluator
import io.github.mbbhalla.agentio.core.model.LLM
import org.slf4j.LoggerFactory
import java.io.File
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Result of one experiment cell: (ExperimentType × LLM × Toggle) evaluated over N iterations.
 */
data class CellResult(
    val experimentType: ExperimentType,
    val llm: LLM,
    val adaptiveCmmEnabled: Boolean,
    val evaluationResult: AgenticFunctionEvaluator.EvaluationResult<NeedleRetrievalOutput>,
)

/**
 * Writes experiment results to a Markdown file.
 *
 * The output is structured around the key numbers that prove (or disprove) the hypothesis:
 *
 * 1. **Accuracy Δ** — CMM ON accuracy minus CMM OFF accuracy per model.
 *    Positive = adaptive reshuffling helps. This is the headline number.
 *
 * 2. **Token overhead** — How many extra input tokens CMM ON uses vs CMM OFF
 *    (probes + recall request). Should be <5% for the "near-zero cost" claim.
 *
 * 3. **Failure rate** — Whether the recall request causes more failures in CMM ON
 *    (model confused by the appended probe query). Should be equal or lower.
 */
object ResultsWriter {

    private val LOG = LoggerFactory.getLogger(ResultsWriter::class.java)

    fun write(
        results: List<CellResult>,
        suiteConfig: ExperimentSuiteConfig,
        outputFile: File,
    ) {
        val sb = StringBuilder()

        sb.appendLine("# Adaptive CMM Experiment Results")
        sb.appendLine()
        sb.appendLine("**Generated:** ${Instant.now().atZone(ZoneOffset.UTC).format(DateTimeFormatter.ISO_INSTANT)}")
        sb.appendLine()

        // Configuration section
        writeConfiguration(sb, suiteConfig)

        // Key findings — the numbers that matter most
        writeKeyFindings(sb, results, suiteConfig)

        // Per-experiment sections
        for (experimentType in suiteConfig.experimentTypes) {
            val experimentResults = results.filter { it.experimentType == experimentType }
            writeExperimentSection(sb, experimentType, experimentResults, suiteConfig)
        }

        // Cost and overhead analysis
        writeOverheadAnalysis(sb, results, suiteConfig)

        // Vote count deviation analysis for Distributed Counting
        if (suiteConfig.experimentTypes.contains(ExperimentType.DISTRIBUTED_COUNTING)) {
            writeVoteDeviationAnalysis(sb, results, suiteConfig)
        }

        // Per-trial raw data
        writePerTrialDetails(sb, results)

        // Conclusion
        writeConclusion(sb, results, suiteConfig)

        outputFile.parentFile?.mkdirs()
        outputFile.writeText(sb.toString())
        LOG.info("Results written to {}", outputFile.absolutePath)
    }

    private fun writeConfiguration(sb: StringBuilder, suiteConfig: ExperimentSuiteConfig) {
        sb.appendLine("## Configuration")
        sb.appendLine()
        sb.appendLine("| Parameter | Value |")
        sb.appendLine("|-----------|-------|")
        sb.appendLine("| Attempts per cell | ${suiteConfig.numIterations} |")
        sb.appendLine("| Max parallelism | ${suiteConfig.maxParallelism} |")
        sb.appendLine("| Models | ${suiteConfig.models.joinToString(", ") { "${it.name} (${formatTokenCount(it.maxContextTokens)})" }} |")
        sb.appendLine("| Experiments | ${suiteConfig.experimentTypes.joinToString(", ") { it.displayName }} |")
        sb.appendLine("| Total trials | ${suiteConfig.totalTrials()} |")
        sb.appendLine("| Filler target | 80% of available input budget (context - maxOutput) |")
        sb.appendLine("| Needle depth (target) | 0.5 (dead zone) |")
        sb.appendLine("| Context segments | 20 (fixed, regardless of model window) |")
        sb.appendLine()
    }

    /**
     * The most important section: does adaptive CMM work?
     * Three numbers per model tell the story.
     */
    private fun writeKeyFindings(
        sb: StringBuilder,
        results: List<CellResult>,
        suiteConfig: ExperimentSuiteConfig,
    ) {
        sb.appendLine("## Key Findings")
        sb.appendLine()

        for (experimentType in suiteConfig.experimentTypes) {
            val experimentResults = results.filter { it.experimentType == experimentType }

            sb.appendLine("### ${experimentType.displayName}")
            sb.appendLine()
            sb.appendLine("| Model | Window | CMM OFF Accuracy | CMM ON Accuracy | Accuracy Δ | Token Overhead | Failure Rate OFF | Failure Rate ON |")
            sb.appendLine("|-------|--------|-----------------|----------------|------------|---------------|-----------------|----------------|")

            for (llm in suiteConfig.models) {
                val offResult = experimentResults.find { it.llm == llm && !it.adaptiveCmmEnabled }
                val onResult = experimentResults.find { it.llm == llm && it.adaptiveCmmEnabled }

                val offAccuracy = offResult?.accuracyString() ?: "N/A"
                val onAccuracy = onResult?.accuracyString() ?: "N/A"
                val delta = computeAccuracyDelta(offResult, onResult)
                val tokenOverhead = computeTokenOverhead(offResult, onResult)
                val offFailureRate = offResult?.failureRateString() ?: "N/A"
                val onFailureRate = onResult?.failureRateString() ?: "N/A"

                sb.appendLine(
                    "| ${llm.name} | ${formatTokenCount(llm.maxContextTokens)} | " +
                        "$offAccuracy | $onAccuracy | $delta | $tokenOverhead | " +
                        "$offFailureRate | $onFailureRate |",
                )
            }
            sb.appendLine()

            // Interpretation
            sb.appendLine("**Interpretation:**")
            sb.appendLine()
            sb.appendLine("- **Accuracy Δ > 0** → Adaptive CMM improves needle retrieval (hypothesis supported)")
            sb.appendLine("- **Accuracy Δ ≈ 0** → No measurable effect (hypothesis not supported)")
            sb.appendLine("- **Accuracy Δ < 0** → Adaptive CMM hurts performance (recall request interferes)")
            sb.appendLine("- **Token Overhead < 5%** → Near-zero cost claim holds")
            sb.appendLine("- **Failure Rate ON ≤ Failure Rate OFF** → Recall request does not destabilize the agent")
            sb.appendLine()
        }
    }

    private fun writeExperimentSection(
        sb: StringBuilder,
        experimentType: ExperimentType,
        results: List<CellResult>,
        suiteConfig: ExperimentSuiteConfig,
    ) {
        sb.appendLine("## ${experimentType.displayName} — Detail")
        sb.appendLine()
        sb.appendLine("_${experimentType.description}_")
        sb.appendLine()

        // Per-model breakdown with answer samples
        for (llm in suiteConfig.models) {
            val offResult = results.find { it.llm == llm && !it.adaptiveCmmEnabled }
            val onResult = results.find { it.llm == llm && it.adaptiveCmmEnabled }

            sb.appendLine("### ${llm.name} (${formatTokenCount(llm.maxContextTokens)} window)")
            sb.appendLine()

            for ((label, cellResult) in listOf("CMM OFF" to offResult, "CMM ON" to onResult)) {
                if (cellResult == null) continue
                val eval = cellResult.evaluationResult

                sb.appendLine("**$label:** ${cellResult.accuracyString()}")
                sb.appendLine()

                // Show a few sample answers
                val successOutputs = eval.rawOutputs.filter { it.isSuccess }
                if (successOutputs.isNotEmpty()) {
                    val samples = successOutputs.take(5)
                    sb.appendLine("Sample answers (first ${samples.size}):")
                    sb.appendLine()
                    for ((_, tryOutput) in samples.withIndex()) {
                        val output = tryOutput.get().output
                        val matched = isOutputCorrect(cellResult.experimentType, cellResult.llm, output)
                        val icon = if (matched) "✅" else "❌"
                        sb.appendLine("- $icon `${output.answer.take(100)}`")
                    }
                    sb.appendLine()
                }

                if (eval.failures.isNotEmpty()) {
                    sb.appendLine("Failures (${eval.failures.size}):")
                    sb.appendLine()
                    for (failure in eval.failures.take(3)) {
                        sb.appendLine("- `${failure.message?.take(100)}`")
                    }
                    sb.appendLine()
                }
            }
        }
    }

    /**
     * Token overhead analysis: how much extra does CMM ON cost?
     */
    private fun writeOverheadAnalysis(
        sb: StringBuilder,
        results: List<CellResult>,
        suiteConfig: ExperimentSuiteConfig,
    ) {
        sb.appendLine("## Token Overhead Analysis")
        sb.appendLine()
        sb.appendLine("| Model | Experiment | CMM OFF Avg Input | CMM ON Avg Input | Overhead | CMM OFF Avg Output | CMM ON Avg Output |")
        sb.appendLine("|-------|-----------|------------------|-----------------|----------|-------------------|------------------|")

        for (experimentType in suiteConfig.experimentTypes) {
            for (llm in suiteConfig.models) {
                val offResult = results.find {
                    it.experimentType == experimentType && it.llm == llm && !it.adaptiveCmmEnabled
                }
                val onResult = results.find {
                    it.experimentType == experimentType && it.llm == llm && it.adaptiveCmmEnabled
                }

                val offAvgInput = offResult?.avgInputTokens() ?: 0
                val onAvgInput = onResult?.avgInputTokens() ?: 0
                val offAvgOutput = offResult?.avgOutputTokens() ?: 0
                val onAvgOutput = onResult?.avgOutputTokens() ?: 0

                val overhead = if (offAvgInput > 0) {
                    val pct = ((onAvgInput - offAvgInput).toDouble() / offAvgInput * 100)
                    "${if (pct >= 0) "+" else ""}${"%.1f".format(pct)}%"
                } else {
                    "N/A"
                }

                sb.appendLine(
                    "| ${llm.name} | ${experimentType.displayName} | " +
                        "${formatTokenCount(offAvgInput)} | ${formatTokenCount(onAvgInput)} | " +
                        "$overhead | ${formatTokenCount(offAvgOutput)} | ${formatTokenCount(onAvgOutput)} |",
                )
            }
        }
        sb.appendLine()
    }

    private fun writePerTrialDetails(sb: StringBuilder, results: List<CellResult>) {
        sb.appendLine("## Per-Trial Raw Data")
        sb.appendLine()
        sb.appendLine("| Experiment | Model | CMM | Iteration | Correct | Input Tokens | Output Tokens | Answer (truncated) |")
        sb.appendLine("|-----------|-------|-----|-----------|---------|-------------|--------------|-------------------|")

        for (result in results) {
            val cmmLabel = if (result.adaptiveCmmEnabled) "ON" else "OFF"

            result.evaluationResult.rawOutputs.forEachIndexed { index, tryOutput ->
                if (tryOutput.isSuccess) {
                    val agentOutput = tryOutput.get()
                    val output = agentOutput.output
                    val correct = isOutputCorrect(result.experimentType, result.llm, output)
                    val tokenUsage = agentOutput.conversation.tokenUsage
                    sb.appendLine(
                        "| ${result.experimentType.displayName} | ${result.llm.name} | $cmmLabel | ${index + 1} | " +
                            "${if (correct) "✅" else "❌"} | " +
                            "${tokenUsage.totalInputTokens} | ${tokenUsage.totalOutputTokens} | " +
                            "${output.answer.take(60).replace("|", "\\|")} |",
                    )
                } else {
                    sb.appendLine(
                        "| ${result.experimentType.displayName} | ${result.llm.name} | $cmmLabel | ${index + 1} | " +
                            "❌ FAIL | - | - | " +
                            "${tryOutput.cause.message?.take(60)?.replace("|", "\\|")} |",
                    )
                }
            }
        }
        sb.appendLine()
    }

    private fun writeConclusion(
        sb: StringBuilder,
        results: List<CellResult>,
        suiteConfig: ExperimentSuiteConfig,
    ) {
        sb.appendLine("## Conclusion")
        sb.appendLine()

        // Auto-generate a preliminary conclusion based on the numbers
        val allDeltas = mutableListOf<Double>()
        for (experimentType in suiteConfig.experimentTypes) {
            for (llm in suiteConfig.models) {
                val offResult = results.find {
                    it.experimentType == experimentType && it.llm == llm && !it.adaptiveCmmEnabled
                }
                val onResult = results.find {
                    it.experimentType == experimentType && it.llm == llm && it.adaptiveCmmEnabled
                }
                val delta = computeAccuracyDeltaNumeric(offResult, onResult)
                if (delta != null) allDeltas.add(delta)
            }
        }

        if (allDeltas.isEmpty()) {
            sb.appendLine("_Insufficient data to draw conclusions._")
        } else {
            val avgDelta = allDeltas.average()
            val allPositive = allDeltas.all { it > 0 }
            val allNonNegative = allDeltas.all { it >= 0 }

            when {
                allPositive && avgDelta >= 10 -> {
                    sb.appendLine("**Hypothesis SUPPORTED.** Adaptive CMM consistently improves needle retrieval " +
                        "accuracy across all tested models, with an average improvement of +${"%.0f".format(avgDelta)}%. " +
                        "The probe-recall mechanism successfully identifies and exploits positional attention patterns.")
                }
                allNonNegative && avgDelta > 0 -> {
                    sb.appendLine("**Hypothesis PARTIALLY SUPPORTED.** Adaptive CMM shows a positive trend " +
                        "(average Δ = +${"%.0f".format(avgDelta)}%) but the effect is modest. " +
                        "More iterations or harder test conditions may be needed to establish significance.")
                }
                avgDelta <= 0 -> {
                    sb.appendLine("**Hypothesis NOT SUPPORTED.** Adaptive CMM does not improve needle retrieval " +
                        "accuracy in this experiment (average Δ = ${"%.0f".format(avgDelta)}%). " +
                        "Possible causes: insufficient warm-up turns, probe recall not informative for these models, " +
                        "or the filler content does not trigger sufficient positional bias.")
                }
                else -> {
                    sb.appendLine("**MIXED RESULTS.** Adaptive CMM improves accuracy on some models but not others " +
                        "(average Δ = ${"%.0f".format(avgDelta)}%). Model-specific analysis is needed.")
                }
            }
        }
        sb.appendLine()
    }

    /**
     * Vote count deviation analysis for the Distributed Counting experiment.
     *
     * For each successful trial, computes:
     * - Diff-A = |model's A count - true A count|
     * - Diff-B = |model's B count - true B count|
     *
     * Reports average, P50, P90, P99, and max for each, comparing CMM ON vs OFF.
     */
    private fun writeVoteDeviationAnalysis(
        sb: StringBuilder,
        results: List<CellResult>,
        suiteConfig: ExperimentSuiteConfig,
    ) {
        sb.appendLine("## Vote Count Deviation Analysis")
        sb.appendLine()

        for (llm in suiteConfig.models) {
            val offResult = results.find {
                it.experimentType == ExperimentType.DISTRIBUTED_COUNTING &&
                    it.llm == llm && !it.adaptiveCmmEnabled
            }
            val onResult = results.find {
                it.experimentType == ExperimentType.DISTRIBUTED_COUNTING &&
                    it.llm == llm && it.adaptiveCmmEnabled
            }

            val groundTruth = NeedleContextBuilder.build(ExperimentType.DISTRIBUTED_COUNTING, llm)
                .groundTruthVotes ?: continue

            sb.appendLine("### ${llm.name}")
            sb.appendLine()
            sb.appendLine("**Ground truth:** Proposal A = ${groundTruth.proposalA}, " +
                "Proposal B = ${groundTruth.proposalB}, Total = ${groundTruth.total}")
            sb.appendLine()

            // Per-trial deviation table
            sb.appendLine("#### Per-Trial Deviations")
            sb.appendLine()
            sb.appendLine("| CMM | Iteration | Model A | Model B | Diff-A | Diff-B |")
            sb.appendLine("|-----|-----------|---------|---------|--------|--------|")

            val offDeviations = mutableListOf<Pair<Int, Int>>() // (diffA, diffB)
            val onDeviations = mutableListOf<Pair<Int, Int>>()

            for ((label, cellResult, deviations) in listOf(
                Triple("OFF", offResult, offDeviations),
                Triple("ON", onResult, onDeviations),
            )) {
                if (cellResult == null) continue
                cellResult.evaluationResult.rawOutputs.forEachIndexed { index, tryOutput ->
                    if (tryOutput.isSuccess) {
                        val output = tryOutput.get().output
                        val modelA = output.proposalACount
                        val modelB = output.proposalBCount
                        if (modelA > 0 || modelB > 0) {
                            val diffA = kotlin.math.abs(modelA - groundTruth.proposalA)
                            val diffB = kotlin.math.abs(modelB - groundTruth.proposalB)
                            deviations.add(diffA to diffB)
                            sb.appendLine(
                                "| $label | ${index + 1} | $modelA | $modelB | $diffA | $diffB |",
                            )
                        } else {
                            sb.appendLine("| $label | ${index + 1} | $modelA | $modelB | - | - |")
                        }
                    } else {
                        sb.appendLine("| $label | ${index + 1} | FAILED | FAILED | - | - |")
                    }
                }
            }
            sb.appendLine()

            // Summary statistics
            sb.appendLine("#### Summary Statistics")
            sb.appendLine()
            sb.appendLine("| Metric | CMM OFF Diff-A | CMM OFF Diff-B | CMM ON Diff-A | CMM ON Diff-B |")
            sb.appendLine("|--------|---------------|---------------|--------------|--------------|")

            val offDiffA = offDeviations.map { it.first }.sorted()
            val offDiffB = offDeviations.map { it.second }.sorted()
            val onDiffA = onDeviations.map { it.first }.sorted()
            val onDiffB = onDeviations.map { it.second }.sorted()

            sb.appendLine("| Samples | ${offDiffA.size} | ${offDiffB.size} | ${onDiffA.size} | ${onDiffB.size} |")
            sb.appendLine(
                "| Average | ${offDiffA.avg()} | ${offDiffB.avg()} " +
                    "| ${onDiffA.avg()} | ${onDiffB.avg()} |",
            )
            sb.appendLine(
                "| P50 (median) | ${offDiffA.percentile(50)} | ${offDiffB.percentile(50)} " +
                    "| ${onDiffA.percentile(50)} | ${onDiffB.percentile(50)} |",
            )
            sb.appendLine(
                "| P90 | ${offDiffA.percentile(90)} | ${offDiffB.percentile(90)} " +
                    "| ${onDiffA.percentile(90)} | ${onDiffB.percentile(90)} |",
            )
            sb.appendLine(
                "| P99 | ${offDiffA.percentile(99)} | ${offDiffB.percentile(99)} " +
                    "| ${onDiffA.percentile(99)} | ${onDiffB.percentile(99)} |",
            )
            sb.appendLine(
                "| Max | ${offDiffA.maxOrNull() ?: "-"} | ${offDiffB.maxOrNull() ?: "-"} " +
                    "| ${onDiffA.maxOrNull() ?: "-"} | ${onDiffB.maxOrNull() ?: "-"} |",
            )
            sb.appendLine()

            // Interpretation
            sb.appendLine("**Interpretation:** Lower deviation = more accurate vote counting. " +
                "If CMM ON has lower average/P90/max deviations than CMM OFF, the adaptive " +
                "reshuffling is improving the model's ability to aggregate information from " +
                "across the context.")
            sb.appendLine()
        }
    }

    /** Average of a sorted int list, formatted to 1 decimal. */
    private fun List<Int>.avg(): String =
        if (isEmpty()) "-" else "%.1f".format(average())

    /** Percentile of a sorted int list. */
    private fun List<Int>.percentile(p: Int): String {
        if (isEmpty()) return "-"
        val index = ((p / 100.0) * (size - 1)).toInt().coerceIn(0, size - 1)
        return this[index].toString()
    }

    // --- Helper functions ---

    /**
     * Determine if an output is correct using structured fields — no free text parsing.
     */
    private fun isOutputCorrect(
        experimentType: ExperimentType,
        llm: LLM,
        output: NeedleRetrievalOutput,
    ): Boolean {
        return if (experimentType == ExperimentType.DISTRIBUTED_COUNTING) {
            val groundTruth = NeedleContextBuilder.build(experimentType, llm).groundTruthVotes
                ?: return false
            if (output.proposalACount <= 0 && output.proposalBCount <= 0) return false
            val modelWinnerIsA = output.proposalACount > output.proposalBCount
            val trueWinnerIsA = groundTruth.proposalA > groundTruth.proposalB
            modelWinnerIsA == trueWinnerIsA
        } else {
            val expectedCodes = NeedleContextBuilder.build(experimentType, llm).expectedCodes
            expectedCodes.any { code -> output.answer.contains(code, ignoreCase = true) }
        }
    }

    private fun CellResult.accuracyString(): String {
        val n = evaluationResult.totalIterations
        val matches = evaluationResult.successAndMatchIterations
        val pct = if (n > 0) (matches.toDouble() / n * 100).toInt() else 0
        return "$matches/$n ($pct%)"
    }

    private fun CellResult.failureRateString(): String {
        val n = evaluationResult.totalIterations
        val failures = evaluationResult.failures.size
        val pct = if (n > 0) (failures.toDouble() / n * 100).toInt() else 0
        return "$failures/$n ($pct%)"
    }

    private fun CellResult.avgInputTokens(): Int {
        val successOutputs = evaluationResult.rawOutputs.filter { it.isSuccess }
        if (successOutputs.isEmpty()) return 0
        return successOutputs.sumOf { it.get().conversation.tokenUsage.totalInputTokens } / successOutputs.size
    }

    private fun CellResult.avgOutputTokens(): Int {
        val successOutputs = evaluationResult.rawOutputs.filter { it.isSuccess }
        if (successOutputs.isEmpty()) return 0
        return successOutputs.sumOf { it.get().conversation.tokenUsage.totalOutputTokens } / successOutputs.size
    }

    private fun computeAccuracyDelta(offResult: CellResult?, onResult: CellResult?): String {
        val delta = computeAccuracyDeltaNumeric(offResult, onResult) ?: return "N/A"
        val sign = if (delta >= 0) "+" else ""
        return "**$sign${delta.toInt()}%**"
    }

    private fun computeAccuracyDeltaNumeric(offResult: CellResult?, onResult: CellResult?): Double? {
        if (offResult == null || onResult == null) return null
        val offN = offResult.evaluationResult.totalIterations
        val onN = onResult.evaluationResult.totalIterations
        if (offN == 0 || onN == 0) return null
        val offPct = offResult.evaluationResult.successAndMatchIterations.toDouble() / offN * 100
        val onPct = onResult.evaluationResult.successAndMatchIterations.toDouble() / onN * 100
        return onPct - offPct
    }

    private fun computeTokenOverhead(offResult: CellResult?, onResult: CellResult?): String {
        if (offResult == null || onResult == null) return "N/A"
        val offAvg = offResult.avgInputTokens()
        val onAvg = onResult.avgInputTokens()
        if (offAvg == 0) return "N/A"
        val pct = ((onAvg - offAvg).toDouble() / offAvg * 100)
        return "${if (pct >= 0) "+" else ""}${"%.1f".format(pct)}%"
    }

    private fun formatTokenCount(tokens: Int): String = when {
        tokens >= 1_000_000 -> "${tokens / 1_000_000}M"
        tokens >= 1_000 -> "${tokens / 1_000}K"
        else -> tokens.toString()
    }
}
