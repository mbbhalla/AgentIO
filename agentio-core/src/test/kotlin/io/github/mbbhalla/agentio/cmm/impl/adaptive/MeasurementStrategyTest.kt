package io.github.mbbhalla.agentio.cmm.impl.adaptive

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

internal class MeasurementStrategyTest {
    private val registry =
        ProbeRegistry(
            probeToSegment = mapOf("aaa-111" to 0, "bbb-222" to 1, "ccc-333" to 2),
            segmentToProbe = mapOf(0 to "aaa-111", 1 to "bbb-222", 2 to "ccc-333"),
        )

    @Test
    fun `ProbeResponse should reject blank probeValue`() {
        assertThrows<IllegalArgumentException> {
            ProbeResponse(probeValue = "  ", segmentIndex = 0, rankPosition = 1, recalled = true)
        }
    }

    @Test
    fun `ProbeResponse should reject rankPosition less than 1`() {
        assertThrows<IllegalArgumentException> {
            ProbeResponse(probeValue = "abc", segmentIndex = 0, rankPosition = 0, recalled = true)
        }
    }

    @Test
    fun `MultiProbeRecallStrategy should parse multiple probes from response`() {
        val response =
            """
            Here are the probes I recall:
            CTX_PROBE_S0=aaa-111
            CTX_PROBE_S1=bbb-222
            CTX_PROBE_S2=ccc-333
            """.trimIndent()

        val results = MultiProbeRecallStrategy.parseResponse(response, registry)

        assertEquals(3, results.size)
        assertEquals(0, results[0].segmentIndex)
        assertEquals(1, results[0].rankPosition)
        assertEquals(1, results[1].segmentIndex)
        assertEquals(2, results[1].rankPosition)
        assertEquals(2, results[2].segmentIndex)
        assertEquals(3, results[2].rankPosition)
    }

    @Test
    fun `MultiProbeRecallStrategy should handle empty response`() {
        val results = MultiProbeRecallStrategy.parseResponse("No probes found", registry)
        assertTrue(results.isEmpty())
    }

    @Test
    fun `MultiProbeRecallStrategy should handle unknown probe values`() {
        val response = "CTX_PROBE_S0=dead-beef-0000-0000-000000000000"
        val results = MultiProbeRecallStrategy.parseResponse(response, registry)

        assertEquals(1, results.size)
        // "dead-beef-0000-0000-000000000000" is not in the registry
        assertEquals(null, results[0].segmentIndex)
    }

    @Test
    fun `SingleProbeRecallStrategy should parse only first probe`() {
        val response =
            """
            CTX_PROBE_S0=aaa-111
            CTX_PROBE_S1=bbb-222
            """.trimIndent()

        val results = SingleProbeRecallStrategy.parseResponse(response, registry)

        assertEquals(1, results.size)
        assertEquals(0, results[0].segmentIndex)
        assertEquals(1, results[0].rankPosition)
    }

    @Test
    fun `SingleProbeRecallStrategy should return empty for no match`() {
        val results = SingleProbeRecallStrategy.parseResponse("nothing here", registry)
        assertTrue(results.isEmpty())
    }

    @Test
    fun `MultiProbeRecallStrategy buildQuery should return non-empty string`() {
        val query = MultiProbeRecallStrategy.buildQuery(registry)
        assertTrue(query.isNotBlank())
        assertTrue(query.contains("CTX_PROBE"))
    }

    @Test
    fun `SingleProbeRecallStrategy buildQuery should return non-empty string`() {
        val query = SingleProbeRecallStrategy.buildQuery(registry)
        assertTrue(query.isNotBlank())
        assertTrue(query.contains("CTX_PROBE"))
    }

    @Test
    fun `MultiProbeRecallStrategy should handle lenient formatting`() {
        val response = "CTX_PROBE_S1 = bbb-222"
        val results = MultiProbeRecallStrategy.parseResponse(response, registry)

        assertEquals(1, results.size)
        assertEquals(1, results[0].segmentIndex)
    }
}
