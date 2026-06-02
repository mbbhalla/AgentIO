# agentio-core

Core SDK module providing the foundational abstractions for building GenAI agents on the JVM, plus bundled implementations of context memory managers and event listeners.

## Package Structure

```
io.github.mbbhalla.agentio.core/
├── common/              Annotations, constants, extensions, JSON schema utilities
├── model/               Data models (AgentConfiguration, Conversation, LLM, etc.)
└── lib/
    ├── AbstractAgenticFunction.kt   The core agent abstraction
    ├── ctx/
    │   ├── cmm/         ContextMemoryManager interface + chain orchestrator
    │   ├── provider/    ContextProvider interface (read from external sources)
    │   └── writer/      ContextWriter interface (persist to external sinks)
    ├── eval/            AgenticFunctionEvaluator, AgenticFunctionTrials
    ├── event/           EventListener interface + chain orchestrator
    ├── server/          AbstractMcpServer (in-process MCP via PipedStreams)
    └── tool/            ToolsProvider, AbstractMcpTool, McpClients

io.github.mbbhalla.agentio.cmm.impl/
└── compacting/     LLM-powered conversation summarization

io.github.mbbhalla.agentio.eventlistener.impl/
└── checkpoint/     Periodic conversation checkpointing to durable storage
```

## Key Abstractions

### AbstractAgenticFunction

The core building block. Extend this to create an agent with typed input/output:

```kotlin
class MyAgent(config: AgentConfiguration) :
    AbstractAgenticFunction<MyAgent.Input, MyAgent.Output>(config) {

    @Serializable
    data class Input(val question: String) : Instructible.WithInstruction {
        override fun instructionId() = "my-agent"
        override fun instruction() = "Answer: '$question'"
        override fun systemInstruction(): String? = null
    }

    @Serializable
    data class Output(val answer: String)

    override fun getInputKClass() = Input::class
    override fun getOutputKClass() = Output::class
}
```

### ContextMemoryManager

Interface for context transformation middleware. Implementations process the conversation between turns to improve agent accuracy or manage context window limits.

```kotlin
interface ContextMemoryManager {
    fun shouldExecuteOnTurn(turnNumber: Int): Boolean
    suspend fun getContext(input: ContextMemoryManagerInput): Conversation
}
```

CMMs are chained via `ContextMemoryManagers` — an ordered list where each CMM's output feeds the next.

### ToolsProvider / McpClients

Interface for providing tools to the agent. `McpClients` is the standard implementation that connects to one or more MCP servers:

```kotlin
val toolsProvider = McpClients(
    set = setOf(
        NamedClient(name = "search", client = mcpClient, deniedTools = emptySet()),
    ),
)
```

### AbstractMcpTool / AbstractMcpServer

Build MCP-compliant tools and servers that run in-process:

```kotlin
class MyTool : AbstractMcpTool<MyTool.Input, MyTool.Output>() {
    override fun name() = "my_tool"
    override fun description() = "Does something useful"
    override fun invoke(input: Input): Output { /* ... */ }
    // ...
}

class MyServer : AbstractMcpServer() {
    override fun tools() = setOf(MyTool()())
    override fun capabilities() = ServerCapabilities(/* ... */)
    override fun name() = "my-server"
    override fun version() = "1.0.0"
}
```

### AgenticFunctionEvaluator

Run an agent N times and aggregate results for accuracy measurement:

```kotlin
val evaluator = AgenticFunctionEvaluator(
    EvaluationInput.withFunction(
        agenticFunction = agent,
        input = testInput,
        numIterations = 50,
        maxParallelism = 5,
        outputMatcher = { it.answer.contains("expected") },
    )
)
val result = evaluator.evaluate()
// result.successAndMatchIterations, result.failures, etc.
```

---

## Bundled CMM: Compacting Context Memory Manager

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

Multiple CMMs can be chained — each transforms the conversation in order:

```kotlin
contextMemoryManagers = ContextMemoryManagers(
    value = listOf(
        compactingCmm,
        // additional CMMs as needed
    ),
)
```

---

## Bundled EventListener: Checkpointing

An `EventListener` that captures periodic snapshots of agent conversation state, enabling recovery, auditing, and debugging of long-running agent executions.

### The Problem

Multi-turn agent conversations accumulate significant state over time. If a process crashes, network fails, or you simply want to inspect what happened at turn 15 of a 40-turn execution, that state is lost unless explicitly persisted.

### The Solution

```
Agent Turn Loop
    │
    ▼
TurnCompleted event ──→ CheckpointingEventListener
                              │
                              ▼
                        CheckpointTrigger (should we checkpoint?)
                              │ yes
                              ▼
                        CheckpointWriter (persist the snapshot)
                              │
                              ▼
                        checkpoint_snapshot_{agentId}_turn_{N}.json
```

### Usage

```kotlin
import io.github.mbbhalla.agentio.eventlistener.impl.checkpoint.CheckpointingEventListener
import io.github.mbbhalla.agentio.eventlistener.impl.checkpoint.CheckpointTrigger
import io.github.mbbhalla.agentio.eventlistener.impl.checkpoint.FileSystemCheckpointWriter
import java.nio.file.Path

val checkpointListener = CheckpointingEventListener(
    trigger = CheckpointTrigger.EveryNTurns(n = 5),
    writer = FileSystemCheckpointWriter(directory = Path.of("/tmp/checkpoints")),
)

val agentConfig = AgentConfiguration(
    // ...
    eventListeners = EventListeners(setOf(checkpointListener)),
)
```

### Components

| Class | Purpose |
|-------|---------|
| `CheckpointingEventListener` | Orchestrator — listens for `TurnCompleted`, evaluates trigger, delegates to writer |
| `CheckpointTrigger` | Sealed class defining when to checkpoint (currently: `EveryNTurns`) |
| `CheckpointWriter` | Functional interface for persisting checkpoints to any backend |
| `FileSystemCheckpointWriter` | Built-in writer that serializes checkpoints as JSON files to a local directory |
| `Checkpoint` | Immutable data class holding agentId, turnNumber, timestamp, and conversation state |
| `CheckpointSerializer` | Converts `Checkpoint` into a serializable JSON representation |

### Custom Writers

Implement `CheckpointWriter` to persist checkpoints to any backend:

```kotlin
fun interface CheckpointWriter {
    suspend fun write(checkpoint: Checkpoint)
}

// Example: S3 writer
class S3CheckpointWriter(
    private val s3Client: S3Client,
    private val bucket: String,
) : CheckpointWriter {
    override suspend fun write(checkpoint: Checkpoint) {
        val key = "checkpoints/${checkpoint.agentId}/turn_${checkpoint.turnNumber}.json"
        val content = CheckpointSerializer.serialize(checkpoint)
        s3Client.putObject(PutObjectRequest { /* ... */ })
    }
}
```

---

## Dependencies

- AWS SDK for Kotlin (Bedrock Runtime)
- Model Context Protocol Kotlin SDK
- Kotlinx Serialization
- Kotlinx Coroutines
- Jackson (JSON schema generation/validation)
- Vavr (functional Try type)
- SLF4J + Logback

## Build

```bash
./gradlew :agentio-core:build
./gradlew :agentio-core:test
```
