# agentio-eventlistener-impl

Event listener implementations for AgentIO. These are concrete `EventListener` implementations that react to agent lifecycle events (emitted via the core `EventListener` interface) to provide cross-cutting capabilities like checkpointing.

## Package Structure

```
io.github.mbbhalla.agentio.eventlistener.impl/
└── checkpoint/     Periodic conversation checkpointing to durable storage
```

## Checkpointing Event Listener

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

The listener hooks into the agent's event system, evaluates a trigger condition on each `TurnCompleted` event, and delegates persistence to a pluggable `CheckpointWriter`.

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
| `CheckpointingEventListener` | Orchestrator — listens for `TurnCompleted` events, evaluates trigger, delegates to writer |
| `CheckpointTrigger` | Sealed class defining when to checkpoint (currently: `EveryNTurns`) |
| `CheckpointWriter` | Functional interface for persisting checkpoints to any backend |
| `FileSystemCheckpointWriter` | Built-in writer that serializes checkpoints as JSON files to a local directory |
| `Checkpoint` | Immutable data class holding agentId, turnNumber, timestamp, and full conversation state |
| `CheckpointSerializer` | Converts `Checkpoint` into a serializable JSON representation |

### Trigger Strategies

| Trigger | Description |
|---------|-------------|
| `EveryNTurns(n)` | Checkpoint every N completed turns (e.g., every 5th turn) |

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

### Checkpoint JSON Structure

Each checkpoint file contains a full conversation snapshot:

```json
{
    "agentId": "my-agent",
    "turnNumber": 10,
    "checkpointTimestamp": 1715000000000,
    "conversation": {
        "messages": [
            { "role": "user", "content": [...], "timestamp": 1714999000000 },
            { "role": "assistant", "content": [...], "timestamp": 1714999001000 }
        ],
        "tokenUsage": {
            "totalInputTokens": 12500,
            "totalOutputTokens": 3200,
            "lastTurnInputTokens": 1100,
            "lastTurnOutputTokens": 450,
            "lastTurnTotalTokens": 1550
        },
        "stopReason": "end_turn",
        "thinkingModeCounter": 0
    }
}
```

## Dependencies

- `agentio-core` (for `EventListener` interface, `Event`, `Conversation`, etc.)
- AWS SDK for Kotlin (Bedrock Runtime — for `ContentBlock` types in serialization)
- Kotlinx Serialization
- Kotlinx Coroutines
- SLF4J

## Build & Test

```bash
./gradlew :agentio-eventlistener-impl:build
./gradlew :agentio-eventlistener-impl:test
```
