# Adaptive Context Memory Manager

## The Problem

Large language models (LLMs) don't pay equal attention to everything you put in their context window. Research shows a consistent pattern called **"lost in the middle"**: information placed near the beginning or end of the context gets noticed, while information in the middle gets ignored.

Here's what the attention distribution looks like in practice:

```
Attention
  ▲
  │ ██                                              ██
  │ ██ ██                                        ██ ██
  │ ██ ██ ██                                  ██ ██ ██
  │ ██ ██ ██ ██                            ██ ██ ██ ██
  │ ██ ██ ██ ██ ██ ██                ██ ██ ██ ██ ██ ██
  │ ██ ██ ██ ██ ██ ██ ██ ██ ██ ██ ██ ██ ██ ██ ██ ██ ██
  └──────────────────────────────────────────────────────►
    Start              Middle                        End
                    ↑ DEAD ZONE ↑
```

This means if your agent puts a critical error log in the middle of the context and a low-importance repository overview near the end, the model will pay more attention to the overview and less to the error log — the opposite of what you want.

**Why existing solutions fall short:**

- **Static reordering** (like LangChain's `LongContextReorder`) assumes the attention curve is always the same U-shape. But the actual shape varies by model, context length, and content. A fixed rule can't adapt.
- **Attention-weight-based methods** (like Attention Sorting) read the model's internal attention matrices to figure out where it's looking. This only works on open-source models where you can access internals. It doesn't work on Claude, GPT, or Gemini — the commercial APIs that most production agents use.

## The Solution

The Adaptive Context Memory Manager solves this by **measuring where the model is actually paying attention at runtime, then moving important content to those high-attention positions**.

The key insight: you don't need to open up the model to measure attention. You can ask the model itself. If you embed a unique marker at each position in the context and then ask "which markers do you remember?", the ones it recalls first are in the positions it's paying the most attention to.

### The Cycle: Probe → Measure → Reshuffle

The system runs a continuous three-step cycle as middleware between your agent and the LLM:

```
Your Agent
    │
    │ assembles context (system prompt, docs, conversation, tool results)
    ▼
┌─────────────────────────────────────────────┐
│  1. PROBE                                   │
│     Embed unique markers in each segment    │
│     [CTX_PROBE_S0=abc123]                   │
│     [CTX_PROBE_S1=def456]                   │
│     ...                                     │
│                                             │
│  2. MEASURE                                 │
│     Parse the model's last response to see  │
│     which probes it recalled and in what    │
│     order. Build an attention heatmap.      │
│                                             │
│  3. RESHUFFLE                               │
│     Move high-importance segments to        │
│     high-attention positions.               │
│     Move low-importance segments to         │
│     dead zones (where they do less harm).   │
└─────────────────────────────────────────────┘
    │
    │ optimized context
    ▼
  LLM (Claude, GPT, etc.)
```

This cycle repeats across the agent's lifetime. The heatmap gets more accurate over time as measurements accumulate.

## How It Works — Step by Step

### Step 1: Segment Extraction

The conversation is broken into **segments** — the atomic units that can be moved around. Each segment gets an importance score.

**Code:** [`DefaultSegmentExtractor`](AdaptiveContextMemoryManager.kt) (line ~82)

```kotlin
// Each ContentBlock.Text in User messages becomes a segment
conversation.messages
    .filter { it.message.role == ConversationRole.User }
    .flatMap { it.message.content.filterIsInstance<ContentBlock.Text>() }
    .filter { /* exclude recall request blocks */ }
    .mapIndexed { position, textBlock ->
        ContextSegment(
            content = textBlock.value,
            importanceScore = estimateImportance(textBlock.value, isFirst),
            position = position,
            constraints = SegmentConstraints(isAnchored = isFirst),
        )
    }
```

The default importance scorer is a simple keyword heuristic:
- First segment (system prompt): **0.95** + anchored (can't be moved)
- Contains "error" or "exception": **0.85**
- Contains "result" or "output": **0.75**
- Everything else: **0.50**

For production, you should supply your own `SegmentExtractor` with domain-specific scoring.

**Code:** [`ContextSegment`](ContextSegment.kt)

Each segment carries:
- `content` — the actual text
- `importanceScore` — how important this segment is (0.0 to 1.0)
- `position` — where it currently sits in the context
- `constraints` — rules about placement (anchored? part of an adjacency group?)

### Step 2: Probe Embedding

Each segment gets a unique marker appended to its content — a UUID wrapped in a recognizable tag.

**Code:** [`ProbeTokenManager.embedProbes()`](ProbeTokenManager.kt)

```kotlin
// For each segment, generate a fresh UUID and append a probe token
val probeValue = uuidGenerator()  // e.g., "a1b2c3d4-e5f6-..."
val probeToken = "[CTX_PROBE_S0=a1b2c3d4-e5f6-...]"
segment.copy(content = "${segment.content}\n$probeToken")
```

After embedding, the context looks like:

```
[System prompt text...]
[CTX_PROBE_S0=a1b2c3d4-e5f6-7890-abcd-ef1234567890]

[Some retrieved documentation...]
[CTX_PROBE_S1=f9e8d7c6-b5a4-3210-fedc-ba0987654321]

[Error log from failing test...]
[CTX_PROBE_S2=11223344-5566-7788-99aa-bbccddeeff00]
```

The probes are:
- **Unique** — UUID v4, no collision with natural content
- **Semantically inert** — the bracket/tag format doesn't look like natural language
- **Rotated every cycle** — fresh UUIDs each time, so the model can't memorize old ones
- **Lightweight** — ~15-20 tokens per probe

The function also returns a `ProbeRegistry` — a bidirectional map that lets you look up which segment a probe belongs to, and vice versa.

**Code:** [`ProbeRegistry`](ProbeTokenManager.kt)

```kotlin
data class ProbeRegistry(
    val probeToSegment: Map<String, Int>,  // UUID → segment index
    val segmentToProbe: Map<Int, String>,  // segment index → UUID
)
```

### Step 3: Piggyback Measurement Request

After embedding probes, the system appends a recall request to the last user message. This is the "piggyback" — it rides along with whatever the user was already asking, so there's no extra API call.

**Code:** [`appendRecallRequest()`](AdaptiveContextMemoryManager.kt)

The recall request text (from `MultiProbeRecallStrategy.buildQuery()`):

```
Several CTX_PROBE values are embedded in the context above.
Without searching exhaustively, report the CTX_PROBE values that come to mind
most readily, ranked by confidence. Format each as: CTX_PROBE_S<index>=<value>
```

The model answers the user's real question AND reports which probes it noticed. The recall request is tagged with `[AGENTIO_RECALL_REQUEST]` so the segment extractor knows to skip it on subsequent turns.

### Step 4: Parse the Response

Next turn, the system reads the model's response and extracts any probe values it mentioned.

**Code:** [`measureFromLatestResponse()`](AdaptiveContextMemoryManager.kt)

```kotlin
// Find the last assistant message
val lastAssistantText = conversation.messages
    .lastOrNull { it.message.role == ConversationRole.Assistant }
    // ... extract text content ...

// Parse out any probe values using a lenient regex
val responses = config.measurementStrategy.parseResponse(lastAssistantText, state.registry)
```

**Code:** [`MultiProbeRecallStrategy.parseResponse()`](MeasurementStrategy.kt)

The parser uses a lenient regex that handles variations in how the model formats its response:

```kotlin
val PROBE_RESPONSE_REGEX =
    """CTX_PROBE[_]?S?(\d+)\s*=\s*([0-9a-f\-]+)""".toRegex(RegexOption.IGNORE_CASE)
```

Each match becomes a `ProbeResponse` with a rank (order of appearance = order of recall confidence). The rank is converted to a score:

```
Rank 1 (recalled first)  → score 1.0
Rank 2 (recalled second) → score 0.5
Rank 3 (recalled third)  → score 0.33
Not recalled             → score 0.0
```

The intuition: probes the model recalls first are in positions it's paying the most attention to.

### Step 5: Update the Heatmap

The scores are folded into a running average (EMA) that smooths out noise across turns.

**Code:** [`AttentionHeatmap.update()`](AttentionHeatmap.kt)

```kotlin
fun update(measurements: Map<Int, Double>): AttentionHeatmap {
    val updatedScores = (scores.keys + measurements.keys).associateWith { position ->
        val old = scores.getOrDefault(position, 0.0)
        val measured = measurements[position]
        if (measured != null) {
            decayFactor * old + (1.0 - decayFactor) * measured  // EMA blend
        } else {
            decayFactor * old  // unmeasured positions decay toward zero
        }
    }
    return copy(scores = updatedScores)
}
```

With the default decay factor of **0.8**:
- A measured position: `new = 0.8 × old + 0.2 × measured`
- An unmeasured position: `new = 0.8 × old` (fades toward zero)

After a few turns, the heatmap might look like:

```
Position:  0     1     2     3     4     5
Score:    0.92  0.15  0.41  0.08  0.78  0.85
Zone:     HOT   dead  warm  dead  HOT   HOT
```

This tells you: the model is paying strong attention to positions 0, 4, and 5, and almost ignoring positions 1 and 3.

### Step 6: Reshuffle

The reshuffler solves a simple optimization: **put the most important segments where the model is looking the hardest**.

**Code:** [`ContextReshuffler.reshuffle()`](ContextReshuffler.kt)

The algorithm:

1. **Partition** segments into anchored (can't move) and movable.
2. **Sort** movable segments by importance (highest first).
3. **Sort** available positions by attention score (highest first).
4. **Zip** them: most important → hottest position, second most important → second hottest, etc.
5. **Repair** adjacency constraints (tool call + result must stay together).

```kotlin
val (anchored, movable) = segments.partition { it.constraints.isAnchored }
val sortedPositions = availablePositions.sortedByDescending { heatmap.scoreAt(it) }
val sortedMovable = movable.sortedByDescending { it.importanceScore }
val assigned = sortedMovable.zip(sortedPositions).map { (segment, position) ->
    segment.copy(position = position)
}
```

**Example:** If your error log (importance 0.95) was stuck at position 2 (attention 0.41) and a boilerplate overview (importance 0.40) was at position 5 (attention 0.85), the reshuffler swaps them. The error log moves to position 5 where the model will actually read it.

### Step 7: Reassemble and Return

The reshuffled segments are written back into the conversation, fresh probes are embedded at the new positions, and the conversation is returned to the agent framework for the next LLM call.

**Code:** [`DefaultSegmentAssembler.assemble()`](AdaptiveContextMemoryManager.kt)

## File Guide

| File | What it does |
|------|-------------|
| [`AdaptiveContextMemoryManager.kt`](AdaptiveContextMemoryManager.kt) | The orchestrator. Owns the Probe → Measure → Reshuffle lifecycle. Also contains `AdaptiveConfig`, `AdaptiveState`, `DefaultSegmentExtractor`, `DefaultSegmentAssembler`, and the `SegmentExtractor`/`SegmentAssembler` interfaces. |
| [`ContextSegment.kt`](ContextSegment.kt) | Data model for a segment — the atomic unit of reshuffling. Carries content, importance, position, and structural constraints (anchoring, adjacency groups). |
| [`ProbeTokenManager.kt`](ProbeTokenManager.kt) | Pure functions for probe lifecycle: `embedProbes()`, `stripProbes()`, `formatProbeToken()`. Also contains `ProbeRegistry` (bidirectional UUID ↔ segment index map) and `ProbeEmbeddingResult`. |
| [`AttentionHeatmap.kt`](AttentionHeatmap.kt) | Immutable EMA-smoothed position → score map. `update()` returns a new heatmap. `hotZones()` and `deadZones()` return sorted views. |
| [`MeasurementStrategy.kt`](MeasurementStrategy.kt) | Interface for measurement approaches + two implementations: `MultiProbeRecallStrategy` (default, asks for all probes ranked) and `SingleProbeRecallStrategy` (asks for just the top one). Also contains the `ProbeResponse` data class. |
| [`ContextReshuffler.kt`](ContextReshuffler.kt) | Pure function for the constrained greedy assignment. Sorts importance × attention, zips, then repairs adjacency groups. |

## Design Principles

**Everything is immutable except one `var`.** `AttentionHeatmap`, `ContextSegment`, `ProbeRegistry`, `AdaptiveState` are all immutable `data class`es. `ProbeTokenManager` and `ContextReshuffler` are stateless `object`s with pure functions. The only mutable thing is `var state` in `AdaptiveContextMemoryManager`, and it's replaced atomically with a new immutable value each cycle.

**Pure functions everywhere.** `embedProbes()` takes segments in, returns instrumented segments + registry out. `reshuffle()` takes segments + heatmap in, returns reshuffled segments out. No side effects, no global state. This makes every component independently testable.

**Pluggable via interfaces.** `SegmentExtractor`, `SegmentAssembler`, and `MeasurementStrategy` are all interfaces (or `fun interface`s). Swap in your own implementation without touching the orchestrator.

**Black-box by design.** The entire system works through the standard text API. No attention weights, no model internals, no special API flags. If you can send text to a model and read text back, this works.

## Quick Start

### Minimal setup (just add to your agent config):

```kotlin
val agentConfig = AgentConfiguration(
    agentId = "my-agent",
    languageModelParameters = LanguageModelParameters(
        llm = LLM.ANTHROPIC_CLAUDE_SONNET_4_CROSS_REGION_INFERENCE,
        temperature = Temperature(0.1f),
        topP = TopP(0.9f),
    ),
    bedrockRuntimeClient = bedrockClient,
    toolsProvider = myToolsProvider,

    // Add the adaptive CMM ← this is the only change
    contextMemoryManagers = ContextMemoryManagers(
        value = listOf(AdaptiveContextMemoryManager()),
    ),
)
```

### Custom configuration:

```kotlin
AdaptiveContextMemoryManager(
    config = AdaptiveConfig(
        measurementFrequency = 1,                          // measure every turn
        measurementStrategy = SingleProbeRecallStrategy,   // cheaper, less precise
        heatmapDecayFactor = 0.5,                          // faster adaptation
        enablePiggyback = true,                            // append recall request automatically
    ),
)
```

### With domain-specific importance scoring:

```kotlin
val myExtractor = SegmentExtractor { conversation ->
    conversation.messages
        .filter { it.message.role == ConversationRole.User }
        .flatMap { it.message.content.filterIsInstance<ContentBlock.Text>() }
        .filter { !it.value.startsWith(AdaptiveContextMemoryManager.RECALL_REQUEST_MARKER) }
        .mapIndexed { position, textBlock ->
            ContextSegment(
                content = textBlock.value,
                importanceScore = myDomainScorer(textBlock.value),
                position = position,
                constraints = SegmentConstraints(isAnchored = position == 0),
            )
        }
}

AdaptiveContextMemoryManager(
    config = AdaptiveConfig(segmentExtractor = myExtractor),
)
```

### Chaining with other CMMs:

```kotlin
contextMemoryManagers = ContextMemoryManagers(
    value = listOf(
        CompactingContextMemoryManager(compactionConfig),  // summarize old turns first
        AdaptiveContextMemoryManager(),                     // then reshuffle what remains
    ),
)
```

Order matters: put summarization *before* the adaptive CMM so it reshuffles the already-compacted context.

## Configuration Reference

| Parameter | Default | What it does |
|-----------|---------|-------------|
| `measurementFrequency` | `3` | Run the full measure + reshuffle cycle every N turns. Lower = more responsive but more overhead. |
| `measurementStrategy` | `MultiProbeRecallStrategy` | How to ask the model about probes and how to parse the response. |
| `segmentExtractor` | `DefaultSegmentExtractor` | How to break the conversation into segments and score their importance. |
| `segmentAssembler` | `DefaultSegmentAssembler` | How to write reshuffled segments back into the conversation. |
| `heatmapDecayFactor` | `0.8` | EMA decay. Higher = more stable heatmap, slower to adapt. Lower = faster adaptation, more noise. |
| `enablePiggyback` | `true` | Append the recall request to the user's message automatically. Set to `false` for structured-output agents where the extra text would interfere. |

## Tests

All tests are in `lib/src/test/kotlin/com/amazon/agentio/lib/ctx/cmm/adaptive/`:

| Test file | What it covers |
|-----------|---------------|
| `AdaptiveContextMemoryManagerTest.kt` | End-to-end orchestration, piggyback wiring, measurement frequency, recall request marker filtering |
| `AttentionHeatmapTest.kt` | EMA update, decay behavior, hot/dead zone sorting |
| `ContextReshufflerTest.kt` | Greedy assignment, anchor constraints, adjacency group repair |
| `ContextSegmentTest.kt` | Validation of importance range, position non-negativity, constraint defaults |
| `MeasurementStrategyTest.kt` | Multi-probe and single-probe parsing, edge cases in regex matching |
| `ProbeTokenManagerTest.kt` | Embed/strip round-trip, registry bijection, UUID format |

Run them:

```bash
./gradlew :lib:test --tests 'com.amazon.agentio.lib.ctx.cmm.adaptive.*'
```
