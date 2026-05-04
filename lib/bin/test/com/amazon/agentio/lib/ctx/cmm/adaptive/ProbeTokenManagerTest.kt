package com.amazon.agentio.lib.ctx.cmm.adaptive

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

internal class ProbeTokenManagerTest {

    @Test
    fun `formatProbeToken should produce correct format`() {
        val token = ProbeTokenManager.formatProbeToken(3, "abc-123")
        assertEquals("[CTX_PROBE_S3=abc-123]", token)
    }

    @Test
    fun `embedProbes should instrument each segment with a probe token`() {
        val segments = listOf(
            ContextSegment(content = "first", importanceScore = 0.5, position = 0),
            ContextSegment(content = "second", importanceScore = 0.7, position = 1),
        )
        var counter = 0
        val uuids = listOf("uuid-aaa", "uuid-bbb")

        val result = ProbeTokenManager.embedProbes(segments) { uuids[counter++] }

        assertEquals(2, result.segments.size)
        assertTrue(result.segments[0].content.contains("[CTX_PROBE_S0=uuid-aaa]"))
        assertTrue(result.segments[1].content.contains("[CTX_PROBE_S1=uuid-bbb]"))
    }

    @Test
    fun `embedProbes should produce a bijective registry`() {
        val segments = listOf(
            ContextSegment(content = "a", importanceScore = 0.5, position = 0),
            ContextSegment(content = "b", importanceScore = 0.5, position = 1),
        )
        var counter = 0
        val uuids = listOf("uuid-x", "uuid-y")

        val result = ProbeTokenManager.embedProbes(segments) { uuids[counter++] }

        assertEquals(0, result.registry.segmentFor("uuid-x"))
        assertEquals(1, result.registry.segmentFor("uuid-y"))
        assertEquals("uuid-x", result.registry.probeFor(0))
        assertEquals("uuid-y", result.registry.probeFor(1))
    }

    @Test
    fun `stripProbes should remove all probe tokens from content`() {
        val segments = listOf(
            ContextSegment(
                content = "hello\n[CTX_PROBE_S0=abc-def-1234-5678-abcdef012345]",
                importanceScore = 0.5,
                position = 0,
            ),
            ContextSegment(
                content = "world\n[CTX_PROBE_S1=fed-cba-9876-5432-fedcba987654]",
                importanceScore = 0.5,
                position = 1,
            ),
        )

        val stripped = ProbeTokenManager.stripProbes(segments)

        assertEquals("hello", stripped[0].content)
        assertEquals("world", stripped[1].content)
    }

    @Test
    fun `stripProbes on content without probes should be idempotent`() {
        val segments = listOf(
            ContextSegment(content = "no probes here", importanceScore = 0.5, position = 0),
        )

        val stripped = ProbeTokenManager.stripProbes(segments)

        assertEquals("no probes here", stripped[0].content)
    }

    @Test
    fun `PROBE_TOKEN_REGEX should match valid probe tokens`() {
        val text = "some text [CTX_PROBE_S5=a1b2c3d4-e5f6-7890-abcd-ef1234567890] more text"
        val match = ProbeTokenManager.PROBE_TOKEN_REGEX.find(text)

        assertTrue(match != null)
        assertEquals("5", match!!.groupValues[1])
        assertEquals("a1b2c3d4-e5f6-7890-abcd-ef1234567890", match.groupValues[2])
    }

    @Test
    fun `PROBE_RESPONSE_REGEX should match lenient LLM response formats`() {
        val responses = listOf(
            "CTX_PROBE_S3=abc-def",
            "CTX_PROBE_S3 = abc-def",
            "ctx_probe_s3=abc-def",
        )

        responses.forEach { text ->
            val match = ProbeTokenManager.PROBE_RESPONSE_REGEX.find(text)
            assertTrue(match != null, "Should match: $text")
        }
    }

    @Test
    fun `ProbeRegistry should reject non-bijective mappings`() {
        assertThrows<IllegalArgumentException> {
            ProbeRegistry(
                probeToSegment = mapOf("a" to 0, "b" to 1),
                segmentToProbe = mapOf(0 to "a"),
            )
        }
    }

    @Test
    fun `ProbeRegistry empty should be valid`() {
        val registry = ProbeRegistry()
        assertEquals(0, registry.size)
    }

    @Test
    fun `ProbeEmbeddingResult should reject mismatched sizes`() {
        assertThrows<IllegalArgumentException> {
            ProbeEmbeddingResult(
                segments = listOf(
                    ContextSegment(content = "a", importanceScore = 0.5, position = 0),
                ),
                registry = ProbeRegistry(),
            )
        }
    }

    @Test
    fun `embed then strip should restore original content`() {
        val original = listOf(
            ContextSegment(content = "first segment", importanceScore = 0.8, position = 0),
            ContextSegment(content = "second segment", importanceScore = 0.6, position = 1),
        )

        val embedded = ProbeTokenManager.embedProbes(original)
        val stripped = ProbeTokenManager.stripProbes(embedded.segments)

        assertEquals("first segment", stripped[0].content)
        assertEquals("second segment", stripped[1].content)
    }

    @Test
    fun `ProbeRegistry segmentFor should return null for unknown probe`() {
        val registry = ProbeRegistry(
            probeToSegment = mapOf("known" to 0),
            segmentToProbe = mapOf(0 to "known"),
        )
        assertEquals(null, registry.segmentFor("unknown"))
    }

    @Test
    fun `ProbeRegistry probeFor should return null for unknown segment`() {
        val registry = ProbeRegistry(
            probeToSegment = mapOf("known" to 0),
            segmentToProbe = mapOf(0 to "known"),
        )
        assertEquals(null, registry.probeFor(99))
    }
}
