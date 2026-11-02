package io.github.mbbhalla.agentio.core.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

internal class LanguageModelParametersTest {
    private val samplingModel = LLM.ANTHROPIC_CLAUDE_OPUS_4_6_V1_CROSS_REGION_INFERENCE
    private val nonSamplingModel = LLM.ANTHROPIC_CLAUDE_OPUS_5_5_CROSS_REGION_INFERENCE

    @Test
    fun opus55_hasExpectedModelLimits() {
        assertEquals("us.anthropic.claude-opus-5-5", nonSamplingModel.id)
        assertEquals(1_000_000, nonSamplingModel.maxContextTokens)
        assertEquals(128_000, nonSamplingModel.maxOutputTokens)
    }

    @Test
    fun create_whenSamplingModelWithTemperature_succeeds() {
        val params = LanguageModelParameters(llm = samplingModel, temperature = Temperature(0.2f))
        assertEquals(0.2f, params.temperature?.value)
    }

    @Test
    fun create_whenSamplingModelWithoutSampling_throws() {
        assertThrows<IllegalArgumentException> { LanguageModelParameters(llm = samplingModel) }
    }

    @Test
    fun create_whenNonSamplingModelWithoutSampling_succeeds() {
        val params = LanguageModelParameters(llm = nonSamplingModel)
        assertNull(params.temperature)
        assertNull(params.topP)
        assertEquals(nonSamplingModel.maxOutputTokens, params.maxOutputTokens)
    }

    @Test
    fun create_whenNonSamplingModelWithTemperature_throws() {
        assertThrows<IllegalArgumentException> {
            LanguageModelParameters(llm = nonSamplingModel, temperature = Temperature(0.2f))
        }
    }

    @Test
    fun create_whenNonSamplingModelWithTopP_throws() {
        assertThrows<IllegalArgumentException> {
            LanguageModelParameters(llm = nonSamplingModel, topP = TopP(0.9f))
        }
    }
}
