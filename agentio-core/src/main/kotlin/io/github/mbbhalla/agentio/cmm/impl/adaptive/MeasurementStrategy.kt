package io.github.mbbhalla.agentio.cmm.impl.adaptive

/**
 * A parsed probe response from the LLM — one recalled probe value with metadata.
 */
data class ProbeResponse(
    val probeValue: String,
    val segmentIndex: Int?,
    val rankPosition: Int,
    val recalled: Boolean,
) {
    init {
        require(probeValue.isNotBlank()) {
            "probeValue must not be blank"
        }
        require(rankPosition >= 1) {
            "rankPosition must be >= 1, was $rankPosition"
        }
    }
}

/**
 * Strategy for constructing probe measurement queries and parsing LLM responses.
 *
 * Each strategy encodes its own query format, response parsing logic, and cost characteristics.
 * Implementations are stateless — all state is passed in and returned explicitly.
 */
interface MeasurementStrategy {
    /**
     * Build the probe recall query text to send to the LLM.
     *
     * @param registry The current probe registry mapping probes to segments.
     * @return The query text to inject into the conversation.
     */
    fun buildQuery(registry: ProbeRegistry): String

    /**
     * Parse the LLM's response text to extract probe recall data.
     *
     * @param responseText The raw text from the LLM response.
     * @param registry The probe registry to resolve segment indices.
     * @return List of parsed probe responses.
     */
    fun parseResponse(
        responseText: String,
        registry: ProbeRegistry,
    ): List<ProbeResponse>
}

/**
 * Asks the LLM to report all CTX_PROBE values it can find, ranked by confidence.
 * Builds a full attention distribution from a single query.
 */
object MultiProbeRecallStrategy : MeasurementStrategy {
    private val QUERY_TEMPLATE =
        """
        |[SIDE NOTE — do NOT let this interrupt your primary task]
        |Several CTX_PROBE values are embedded in the context above.
        |Before continuing with your main task, briefly note any CTX_PROBE values
        |that come to mind, ranked by confidence. Format: CTX_PROBE_S<index>=<value>
        |Then continue working on the primary task as normal.
        """.trimMargin()

    override fun buildQuery(registry: ProbeRegistry): String = QUERY_TEMPLATE

    override fun parseResponse(
        responseText: String,
        registry: ProbeRegistry,
    ): List<ProbeResponse> =
        ProbeTokenManager.PROBE_RESPONSE_REGEX
            .findAll(responseText)
            .mapIndexed { rank, match ->
                val probeValue = match.groupValues[2]
                ProbeResponse(
                    probeValue = probeValue,
                    segmentIndex = registry.segmentFor(probeValue),
                    rankPosition = rank + 1,
                    recalled = true,
                )
            }.toList()
}

/**
 * Asks the LLM to report only the single most readily recalled CTX_PROBE value.
 * Cheapest strategy — identifies only the single highest-attention region.
 */
object SingleProbeRecallStrategy : MeasurementStrategy {
    private val QUERY_TEMPLATE =
        """
        |[SIDE NOTE — do NOT let this interrupt your primary task]
        |A CTX_PROBE value is embedded in the context above.
        |Before continuing with your main task, briefly note the CTX_PROBE value
        |that comes to mind most readily. Format: CTX_PROBE_S<index>=<value>
        |Then continue working on the primary task as normal.
        """.trimMargin()

    override fun buildQuery(registry: ProbeRegistry): String = QUERY_TEMPLATE

    override fun parseResponse(
        responseText: String,
        registry: ProbeRegistry,
    ): List<ProbeResponse> =
        ProbeTokenManager.PROBE_RESPONSE_REGEX
            .find(responseText)
            ?.let { match ->
                val probeValue = match.groupValues[2]
                listOf(
                    ProbeResponse(
                        probeValue = probeValue,
                        segmentIndex = registry.segmentFor(probeValue),
                        rankPosition = 1,
                        recalled = true,
                    ),
                )
            }
            ?: emptyList()
}
