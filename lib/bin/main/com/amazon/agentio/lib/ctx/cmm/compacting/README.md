# Compacting Context Memory Manager

## The Problem

Agent conversations grow without bound. Every Bedrock Converse API call sends the full conversation history — every user message, every assistant response, every tool call and result. In tool-heavy agentic flows, a single tool result can be thousands of tokens. After 20-30 turns, the conversation can consume 80-90% of the LLM's context window.

When the context window fills up:
- **Bedrock rejects the request** with a validation error if input tokens exceed the model's limit.
- **Quality degrades** as the model struggles to attend to relevant information buried in a massive context.
- **Cost increases** linearly with input token count.

```
Context Window (200K tokens)
┌──────────────────────────────────────────────────────────┐
│ System prompt + initial instruction                      │ ← Always needed
│ Assistant: "I'll search for..."                          │
│ User: [ToolResult: 15K tokens of JSON]                   │
│ Assistant: "Based on the results..."                     │
│ User: [ToolResult: 20K tokens of JSON]                   │ ← Stale after
│ Assistant: "Let me also check..."                        │   later turns
│ User: [ToolResult: 8K tokens of JSON]                    │   summarized
│ ...                                                      │   these already
│ Assistant: "Here's what I found so far..."               │
│ User: "Now do X with the results"                        │ ← Recent turns
│ Assistant: "I'll process X..."                           │   need to stay
│ User: [ToolResult: 12K tokens]                           │   verbatim
└──────────────────────────────────────────────────────────┘
                    ↑ 75% full — time to compact
```

The middle of the conversation contains tool results and reasoning that the model has already processed and incorporated into later responses. Keeping it verbatim wastes context space.

## The Solution

The Compacting Context Memory Manager monitors context usage and, when it crosses a configurable threshold, uses an LLM to summarize the middle portion of the conversation while preserving the anchor (initial instruction) and recent turns verbatim.

```
Before compaction (180K tokens):
┌─────────────────────────────────────────┐
│ [Anchor] Initial instruction            │ ← Preserved
│ [Middle] 25 messages of tool calls,     │
│          results, reasoning...          │ ← Summarized by LLM
│ [Recent] Last 4 turn pairs             │ ← Preserved
└─────────────────────────────────────────┘

After compaction (~40K tokens):
┌─────────────────────────────────────────┐
│ [Anchor] Initial instruction            │ ← Same
│ [Ack] "Understood. Continuing."         │ ← Role alternation
│ [Summary] 2K token summary of middle    │ ← LLM-generated
│ [Ack] "I have the context summary."     │ ← Role alternation
│ [Recent] Last 4 turn pairs             │ ← Same
└─────────────────────────────────────────┘
```

## How It Works

### Architecture

`CompactingContextMemoryManager` extends `AbstractAgenticFunction` — it is itself an agentic function that uses an LLM to perform summarization. This means:

- The compaction LLM can be **different from the main agent's LLM**. Use a cheaper, faster model (e.g., Sonnet) for compaction while the main agent uses a more capable model (e.g., Opus).
- The compaction call goes through the same Bedrock Converse API pipeline with proper error handling.
- Recursion is prevented: the compaction agent's CMM chain is forced to `NoOperationContextMemoryManager`, its tools are set to `EmptyToolsProvider`, and its turn limit is capped at 5.

### Decision Flow

Each turn, the CMM evaluates whether compaction is needed:

```
Turn N arrives
    │
    ├─ lastTurnInputTokens == 0?  → Skip (no Bedrock call this turn)
    │
    ├─ usageRatio < threshold?    → Skip (context still has room)
    │
    ├─ turnsSinceLastCompaction    → Skip (too soon, prevent thrashing)
    │   < minTurnGap?
    │
    └─ Trigger compaction
         │
         ├─ Split conversation into [Anchor, Middle, Recent]
         ├─ middle.size < 2?  → Skip (nothing to compact)
         ├─ Serialize middle to text (preserving roles, tool data)
         ├─ Call compaction LLM to summarize
         ├─ Reconstruct conversation with summary
         └─ Return compacted conversation
```

### Conversation Splitting

`ConversationCompactor.split()` divides the conversation into three regions:

| Region | What it contains | What happens to it |
|--------|-----------------|-------------------|
| **Anchor** | First message (always User). Contains the initial instruction/system prompt. | Never touched. |
| **Middle** | Everything between anchor and recent. Tool calls, results, reasoning. | Serialized to text and summarized by the compaction LLM. |
| **Recent** | Last N turn pairs (configurable). The most recent exchanges. | Preserved verbatim — recency matters for coherence. |

```kotlin
val split = ConversationCompactor.split(
    conversation = conversation,
    preservedTurnPairs = 4,  // preserve last 8 messages (4 User + 4 Assistant)
)
// split.anchor  → [Message 0]
// split.middle  → [Message 1 .. Message N-8]
// split.recent  → [Message N-7 .. Message N]
```

### Middle Serialization

`ConversationCompactor.middleToText()` converts the middle messages into a text format the compaction LLM can process. It preserves:

- **Role labels** — `[User]` and `[Assistant]` prefixes
- **Text content** — verbatim
- **Tool use names** — `[ToolUse: search_tool]`
- **Tool result content** — the actual data returned by tools (Text and JSON), not just the tool use ID

```
[Assistant]
Let me search for the relevant documentation.
[ToolUse: ReadInternalWebsites]

[User]
[ToolResult: tooluse_abc123]
{"status":"success","content":"The Brazil build system is..."}

[Assistant]
Based on the documentation, here's what I found...
```

### Reconstruction

`ConversationCompactor.reconstruct()` builds a valid conversation from the compacted pieces. The key challenge is maintaining Bedrock's strict User→Assistant role alternation:

```
Anchor(User) → Ack(Assistant) → Summary(User) → Ack(Assistant) → Recent...
```

- An Assistant ack is always inserted between Anchor and Summary (both are User role).
- A second ack is inserted before Recent only if Recent starts with a User message.
- A `require` assertion validates role alternation on every reconstruction.

Token usage totals are reset to 0 after compaction since the old totals are meaningless for the new, shorter conversation. Last-turn values are preserved so the next threshold check has signal.

## Configuration

```kotlin
CompactionConfig(
    threshold = 0.75,                  // Compact when 75% of context window is used
    preservedRecentTurns = 4,          // Keep last 4 turn pairs (8 messages) verbatim
    minTurnGapBetweenCompactions = 5,  // Wait at least 5 turns between compactions
)
```

| Parameter | Default | Range | What it does |
|-----------|---------|-------|-------------|
| `threshold` | `0.75` | `[0.1, 0.99]` | Fraction of `LLM.maxContextTokens` that triggers compaction. Lower = more aggressive compaction. |
| `preservedRecentTurns` | `4` | `≥ 1` | Number of recent turn pairs to keep verbatim. Higher = more context preserved but less compression. |
| `minTurnGapBetweenCompactions` | `5` | `≥ 1` | Minimum turns between compactions. Prevents thrashing when the agent is near the threshold. |

## Usage

### Basic setup

```kotlin
val compactingCmm = CompactingContextMemoryManager(
    compactionAgentConfiguration = AgentConfiguration(
        agentId = "compaction-agent",
        languageModelParameters = LanguageModelParameters(
            llm = LLM.ANTHROPIC_CLAUDE_SONNET_3_7_V1_CROSS_REGION_INFERENCE,
            temperature = Temperature(0.3f),
        ),
        bedrockRuntimeClient = bedrockClient,
        toolsProvider = EmptyToolsProvider,
    ),
    compactionConfig = CompactionConfig(threshold = 0.75),
)

val agentConfig = AgentConfiguration(
    agentId = "my-agent",
    languageModelParameters = LanguageModelParameters(
        llm = LLM.ANTHROPIC_CLAUDE_OPUS_4_6_V1_CROSS_REGION_INFERENCE,
        temperature = Temperature(0.5f),
    ),
    bedrockRuntimeClient = bedrockClient,
    toolsProvider = myToolsProvider,
    contextMemoryManagers = ContextMemoryManagers(
        value = listOf(compactingCmm),
    ),
)
```

### Chaining with Adaptive CMM

```kotlin
contextMemoryManagers = ContextMemoryManagers(
    value = listOf(
        compactingCmm,                    // First: summarize old turns
        AdaptiveContextMemoryManager(),   // Then: reshuffle what remains
    ),
)
```

Order matters: compaction runs first to reduce context size, then the adaptive CMM reshuffles the remaining content for optimal attention placement.

## File Guide

| File | What it does |
|------|-------------|
| [`CompactingContextMemoryManager.kt`](CompactingContextMemoryManager.kt) | The orchestrator. Extends `AbstractAgenticFunction` to use an LLM for summarization. Implements `ContextMemoryManager` for the CMM chain. Contains the threshold check, turn gap enforcement, and compaction trigger logic. |
| [`CompactionConfig.kt`](CompactionConfig.kt) | Configuration data class with validated parameters: threshold, preserved recent turns, and minimum turn gap. |
| [`ConversationCompactor.kt`](ConversationCompactor.kt) | Pure functions for the split → serialize → reconstruct pipeline. `split()` divides the conversation, `middleToText()` serializes the middle region, `reconstruct()` builds a valid conversation from the summary, and `buildCompactionInstruction()` generates the LLM prompt. |

## Safety Properties

- **Fail-safe**: If the compaction LLM call fails, the original conversation is returned unchanged. The agent continues without compaction.
- **No recursion**: The compaction agent's CMM chain is forced to `NoOperationContextMemoryManager`. It cannot trigger another compaction.
- **No tools**: The compaction agent has `EmptyToolsProvider`. It can only summarize, not call tools.
- **Role alternation validated**: `reconstruct()` asserts that the output conversation has valid User→Assistant alternation. A bug here would crash immediately rather than produce a malformed conversation that Bedrock rejects later.
- **Thrashing prevention**: `minTurnGapBetweenCompactions` ensures the CMM doesn't compact every turn when the agent hovers near the threshold.

## Tests

Tests are in `lib/src/test/kotlin/com/amazon/agentio/lib/ctx/cmm/compacting/`:

| Test file | What it covers |
|-----------|---------------|
| `CompactionConfigTest.kt` | Validation of threshold range, preserved turns minimum, turn gap minimum, and default values. |
| `ConversationCompactorTest.kt` | Split correctness (anchor/middle/recent boundaries, small conversations, oversized preservedTurnPairs), middle serialization (text, ToolUse, ToolResult content), reconstruction (role alternation with User-first and Assistant-first recent, compaction marker, anchor/recent preservation, token usage reset, stopReason carry-over, empty recent edge case). |

Run them:

```bash
./gradlew :lib:test --tests 'com.amazon.agentio.lib.ctx.cmm.compacting.*'
```
