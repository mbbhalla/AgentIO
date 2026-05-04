package com.amazon.agentio.lib.ctx.cmm.adaptive

import java.util.UUID

/**
 * Immutable registry mapping probe values to segment indices (bijective).
 */
data class ProbeRegistry(
    val probeToSegment: Map<String, Int> = emptyMap(),
    val segmentToProbe: Map<Int, String> = emptyMap(),
) {
    init {
        require(probeToSegment.size == segmentToProbe.size) {
            "Bijective invariant violated: probeToSegment has ${probeToSegment.size} entries " +
                "but segmentToProbe has ${segmentToProbe.size} entries"
        }
        require(probeToSegment.values.toSet() == segmentToProbe.keys) {
            "Bijective invariant violated: probeToSegment values must equal segmentToProbe keys"
        }
    }

    /** Look up which segment index a probe value belongs to. */
    fun segmentFor(probeValue: String): Int? = probeToSegment[probeValue]

    /** Look up the probe value assigned to a segment index. */
    fun probeFor(segmentIndex: Int): String? = segmentToProbe[segmentIndex]

    val size: Int get() = probeToSegment.size
}

/**
 * Result of embedding probes: the instrumented segments and the registry to decode them.
 */
data class ProbeEmbeddingResult(
    val segments: List<ContextSegment>,
    val registry: ProbeRegistry,
) {
    init {
        require(segments.size == registry.size) {
            "Segments count (${segments.size}) must match registry size (${registry.size})"
        }
    }
}

/**
 * Pure functions for probe token lifecycle: generation, embedding, and formatting.
 *
 * Probe tokens are tagged UUIDs in the format `[CTX_PROBE_S{index}={uuid}]`.
 * They are embedded at segment boundaries to enable empirical attention measurement.
 *
 * All functions are pure — no mutable state. The [ProbeRegistry] is produced as
 * output and threaded through the pipeline explicitly.
 */
object ProbeTokenManager {

    const val PROBE_PREFIX = "CTX_PROBE"

    /** Regex for parsing probe tokens in structured format from context. */
    val PROBE_TOKEN_REGEX =
        """\[${PROBE_PREFIX}_S(\d+)=([0-9a-f\-]+)]""".toRegex()

    /** Lenient regex for parsing probe values from LLM responses. */
    val PROBE_RESPONSE_REGEX =
        """${PROBE_PREFIX}[_]?S?(\d+)\s*=\s*([0-9a-f\-]+)""".toRegex(RegexOption.IGNORE_CASE)

    fun formatProbeToken(segmentIndex: Int, probeValue: String): String =
        "[${PROBE_PREFIX}_S$segmentIndex=$probeValue]"

    /**
     * Embed probe tokens into segments. Returns instrumented segments + fresh registry.
     * Each segment gets a new UUID probe appended to its content.
     * Pure function — produces a new [ProbeEmbeddingResult] with no side effects.
     *
     * @param segments The context segments to instrument.
     * @param uuidGenerator Injectable UUID generator for testability. Defaults to random UUIDs.
     */
    fun embedProbes(
        segments: List<ContextSegment>,
        uuidGenerator: () -> String = { UUID.randomUUID().toString() },
    ): ProbeEmbeddingResult {
        val pairs = segments.map { segment ->
            val probeValue = uuidGenerator()
            val probeToken = formatProbeToken(segment.position, probeValue)
            val instrumentedSegment = segment.copy(content = "${segment.content}\n$probeToken")
            Triple(instrumentedSegment, probeValue, segment.position)
        }

        return ProbeEmbeddingResult(
            segments = pairs.map { it.first },
            registry = ProbeRegistry(
                probeToSegment = pairs.associate { it.second to it.third },
                segmentToProbe = pairs.associate { it.third to it.second },
            ),
        )
    }

    /**
     * Strip probe tokens from segment content. Pure function.
     */
    fun stripProbes(segments: List<ContextSegment>): List<ContextSegment> =
        segments.map { segment ->
            segment.copy(
                content = segment.content.replace(PROBE_TOKEN_REGEX, "").trimEnd(),
            )
        }
}
