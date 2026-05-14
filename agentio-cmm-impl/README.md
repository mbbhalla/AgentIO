# agentio-cmm-impl

Context Memory Manager implementations for AgentIO. These are concrete `ContextMemoryManager` implementations that transform conversation context between turns to improve agent accuracy and manage context window limits.

## Package Structure

```
io.github.mbbhalla.agentio.cmm.impl/
├── adaptive/       Adaptive attention-based context reshuffling
└── compacting/     LLM-powered conversation summarization
```

## Adaptive Context Memory Manager

A novel CMM that empirically measures LLM attention distribution across the context window and reshuffles content to maximize alignment between segment importance and observed attention.

### The Problem

LLMs don't pay equal attention to all positions in the context window. Research shows a "lost in the middle" pattern — information near the start and end gets noticed, while the middle is ignored. If critical information lands in a dead zone, the model misses it.

### The Solution: Probe → Measure → Reshuffle

```
┌─────────────────────────────────────────────┐
│  1. PROBE                                   │
│     Embed unique markers in each segment    │
│     [CTX_PROBE_S0=abc123]                   │
│                                             │
│  2. MEASURE                                 │
│     Parse the model's response to see       │
│     which probes it recalled (and in what   │
│     order). Build an attention heatmap.     │
│                                             │
│  3. RESHUFFLE                               │
│     Move high-importance segments to        │
│     high-attention positions.               │
└─────────────────────────────────────────────┘
```

The key insight: you don't need model internals to measure attention. Embed a unique marker at each position, ask "which markers do you remember?", and the ones recalled first are in high-attention positions.

### Usage

```kotlin
import io.github.mbbhalla.agentio.cmm.impl.adaptive.AdaptiveContextMemoryManager
import io.github.mbbhalla.agentio.cmm.impl.adaptive.AdaptiveConfig

// Minimal — just add to your agent config
val agentConfig = AgentConfiguration(
    // ...
    contextMemoryManagers = ContextMemoryManagers(
        value = listOf(AdaptiveContextMemoryManager()),
    ),
)

// Custom configuration
AdaptiveContextMemoryManager(
    config = AdaptiveConfig(
        measurementFrequency = 1,           // measure every turn
        measurementStrategy = SingleProbeRecallStrategy,
        heatmapDecayFactor = 0.5,           // faster adaptation
        enablePiggyback = true,             // append recall request automatically
    ),
)
```

### Configuration

| Parameter | Default | Description |
|-----------|---------|-------------|
| `measurementFrequency` | `3` | Run measure + reshuffle every N turns |
| `measurementStrategy` | `MultiProbeRecallStrategy` | How to query and parse probe recall |
| `segmentExtractor` | `DefaultSegmentExtractor` | How to break conversation into segments |
| `segmentAssembler` | `DefaultSegmentAssembler` | How to write segments back |
| `heatmapDecayFactor` | `0.8` | EMA decay (higher = more stable, lower = faster adaptation) |
| `enablePiggyback` | `true` | Append recall request to user message (zero extra API calls) |

### Files

| File | Purpose |
|------|---------|
| `AdaptiveContextMemoryManager.kt` | Orchestrator + config + segment extractor/assembler interfaces |
| `AttentionHeatmap.kt` | Immutable EMA-smoothed position → attention score map |
| `ContextReshuffler.kt` | Constrained greedy assignment (importance × attention) |
| `ContextSegment.kt` | Data model for movable context units |
| `MeasurementStrategy.kt` | Probe query/response parsing strategies |
| `ProbeTokenManager.kt` | Probe embedding, stripping, and registry management |

---

## Compacting Context Memory Manager

A CMM that monitors context window usage and, when it crosses a configurable threshold, uses an LLM to summarize older conversation turns while preserving the anchor (initial instruction) and recent turns verbatim.

### The Problem

Agent conversations grow without bound. Every turn adds messages, tool calls, and results. After 20-30 turns, the conversation can consume 80-90% of the context window, leading to Bedrock rejections, quality degradation, and increased cost.

### The Solution

```
Before compaction (180K tokens):
┌─────────────────────────────────────────┐
│ [Anchor] Initial instruction            │ ← Preserved
│ [Middle] 25 messages of tool calls...   │ ← Summarized by LLM
│ [Recent] Last 4 turn pairs             │ ← Preserved
└─────────────────────────────────────────┘

After compaction (~40K tokens):
┌─────────────────────────────────────────┐
│ [Anchor] Initial instruction            │ ← Same
│ [Summary] 2K token LLM-generated summary│ ← Replaces middle
│ [Recent] Last 4 turn pairs             │ ← Same
└─────────────────────────────────────────┘
```

### Usage

```kotlin
import io.github.mbbhalla.agentio.cmm.impl.compacting.CompactingContextMemoryManager
import io.github.mbbhalla.agentio.cmm.impl.compacting.CompactionConfig

val compactingCmm = CompactingContextMemoryManager(
    compactionAgentConfiguration = AgentConfiguration(
        agentId = "compaction-agent",
        languageModelParameters = LanguageModelParameters(
            llm = LLM.ANTHROPIC_CLAUDE_OPUS_4_6_V1_CROSS_REGION_INFERENCE,
            temperature = Temperature(0.3f),
            topP = TopP(0.9f),
        ),
        bedrockRuntimeClient = bedrockClient,
        toolsProvider = EmptyToolsProvider,
    ),
    compactionConfig = CompactionConfig(threshold = 0.75),
)

val agentConfig = AgentConfiguration(
    // ...
    contextMemoryManagers = ContextMemoryManagers(
        value = listOf(compactingCmm),
    ),
)
```

### Configuration

| Parameter | Default | Description |
|-----------|---------|-------------|
| `threshold` | `0.75` | Fraction of max context tokens that triggers compaction |
| `preservedRecentTurns` | `4` | Number of recent turn pairs to keep verbatim |
| `minTurnGapBetweenCompactions` | `5` | Minimum turns between compactions (prevents thrashing) |

### Safety Properties

- **Fail-safe**: If compaction LLM fails, original conversation is returned unchanged
- **No recursion**: Compaction agent's CMM chain is forced to `NoOperationContextMemoryManager`
- **No tools**: Compaction agent uses `EmptyToolsProvider`
- **Role alternation validated**: Reconstruction asserts valid User→Assistant alternation

### Files

| File | Purpose |
|------|---------|
| `CompactingContextMemoryManager.kt` | Orchestrator — threshold check, split, summarize, reconstruct |
| `CompactionConfig.kt` | Validated configuration parameters |
| `ConversationCompactor.kt` | Pure functions for split → serialize → reconstruct pipeline |

---

## Chaining CMMs

Order matters. Put compaction before adaptive reshuffling:

```kotlin
contextMemoryManagers = ContextMemoryManagers(
    value = listOf(
        compactingCmm,                    // First: summarize old turns
        AdaptiveContextMemoryManager(),   // Then: reshuffle what remains
    ),
)
```

## Dependencies

- `agentio-core` (for `ContextMemoryManager` interface, `Conversation`, `AbstractAgenticFunction`, etc.)
- AWS SDK for Kotlin (Bedrock Runtime)
- Kotlinx Serialization
- Vavr
- SLF4J + Logback

## Build & Test

```bash
./gradlew :agentio-cmm-impl:build
./gradlew :agentio-cmm-impl:test
```
