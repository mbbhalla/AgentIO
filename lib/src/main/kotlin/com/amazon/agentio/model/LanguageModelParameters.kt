package com.amazon.agentio.model

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray

@JvmInline
value class Temperature(
    val value: Float,
) {
    init {
        require(value in 0.0..1.0) {
            "Temperature Value must be between [0.0, 1.0]"
        }
    }
}

@JvmInline
value class TopP(
    val value: Float,
) {
    init {
        require(value in 0.0..1.0) {
            "TopP Value must be [0.0, 1.0]"
        }
    }
}

enum class LLM(
    val id: String,

    // Max Context window token length
    val maxContextTokens: Int,

    // Max Model response token length
    val maxOutputTokens: Int,

    // model specific config
    val additionalModelRequestFields: Map<String, JsonElement>,
) {
    /*
        Claude models:
        https://platform.claude.com/docs/en/about-claude/models/overview
        https://docs.anthropic.com/en/docs/about-claude/model-deprecations

        Model status as of 2026-05-02:
        - Sonnet 3.7: RETIRED (Feb 19, 2026) — removed
        - Sonnet 4: DEPRECATED (retires June 15, 2026) — kept with warning
        - Opus 4.5: ACTIVE (EOL not sooner than Nov 24, 2026)
        - Opus 4.6: ACTIVE (EOL not sooner than Feb 5, 2027)
     */

    @Deprecated("Sonnet 4 is deprecated by Anthropic and retires June 15, 2026. Migrate to Sonnet 4.6.")
    ANTHROPIC_CLAUDE_SONNET_4_CROSS_REGION_INFERENCE(
        id = "us.anthropic.claude-sonnet-4-20250514-v1:0",
        maxContextTokens = 1_000_000, // with below beta headers, else default is 200_000
        maxOutputTokens = 64_000,
        additionalModelRequestFields = mapOf(
            "anthropic_beta" to buildJsonArray {
                add(JsonPrimitive("context-1m-2025-08-07"))
            },
        ),
    ),

    ANTHROPIC_CLAUDE_OPUS_4_5_V1_CROSS_REGION_INFERENCE(
        id = "us.anthropic.claude-opus-4-5-20251101-v1:0",
        maxContextTokens = 200_000,
        maxOutputTokens = 64_000,
        additionalModelRequestFields = emptyMap(),
    ),

    ANTHROPIC_CLAUDE_OPUS_4_6_V1_CROSS_REGION_INFERENCE(
        id = "us.anthropic.claude-opus-4-6-v1",
        maxContextTokens = 1_000_000,
        maxOutputTokens = 128_000,
        additionalModelRequestFields = emptyMap(),
    ),

    // add more as needed ...
}

data class LanguageModelParameters(
    val llm: LLM,
    val temperature: Temperature? = null,
    val topP: TopP? = null,
    val additionalModelRequestFields: Map<String, JsonElement> = emptyMap(),
    val maxOutputTokens: Int = llm.maxOutputTokens,
) {
    init {
        require(temperature != null || topP != null) {
            "At least one of temperature or topP must be specified"
        }
    }
}
