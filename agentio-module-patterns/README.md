# agentio-module-patterns

Agent design patterns built with AgentIO. Each pattern is a self-contained package with a `Runner`
and a matching `Run*` Gradle task. Domain-specific examples that are not primarily about how
agents coordinate stay in [`agentio-examples`](../agentio-examples/README.md).

## Patterns

| Pattern | Gradle Task | Demonstrates |
|---------|-------------|--------------|
| Adversarial | `RunAdversarialAgenticFunction` | Designer ↔ Critic iterative refinement (evaluator-optimizer loop) |
| Orchestration | `RunOrchestrationAgenticFunction` | Orchestrator + parallel Workers, synthesis without tools |

All patterns are self-sufficient — no API keys or tokens required beyond AWS credentials for Bedrock.

## Running

```bash
# Adversarial — designer agent iterates with a critic agent until API design is approved
./gradlew :agentio-module-patterns:RunAdversarialAgenticFunction
./gradlew :agentio-module-patterns:RunAdversarialAgenticFunction -Prequirements="Build a library lending API"

# Orchestration — orchestrator dispatches parallel workers and synthesizes results
./gradlew :agentio-module-patterns:RunOrchestrationAgenticFunction
```

## Adversarial (Designer ↔ Critic)

Two agents iterating on an API design: a Designer proposes, a Critic reviews adversarially, and the Designer revises based on feedback until the Critic approves (max 3 iterations).

### Key Patterns

- **Multi-agent communication** — Designer output feeds into Critic input, Critic feedback loops back to Designer
- **Iterative refinement** — bounded loop with convergence condition (`verdict == "APPROVED"`)
- **Shared MCP tooling** — both agents use the same `ApiDesignMcpServer` for schema validation and security checks
- **Custom tools** — `parse_requirements`, `validate_schema_consistency`, `check_security_patterns`

### Architecture

```
┌────────────┐   design JSON   ┌──────────────┐
│  Designer  │ ──────────────▶ │    Critic    │
│   Agent    │ ◀────────────── │    Agent     │
└────────────┘   feedback      └──────────────┘
     │                               │
     └───── ApiDesignMcpServer ──────┘
```

## Orchestration (Orchestrator + Workers)

An Orchestrator dispatches three Worker agents in parallel, each with a focused responsibility, then synthesizes their reports into a unified project health assessment.

### Key Patterns

- **Parallel worker dispatch** — `coroutineScope { async { ... } }` for concurrent execution
- **Separation of concerns** — each worker has its own `AbstractMcpServer` with domain-specific tools
- **Orchestrator has no tools** — purely synthesizes worker outputs using LLM reasoning
- **Worker isolation** — workers execute their responsibility and do NOT orchestrate

### Architecture

```
                ┌──────────────────┐
                │   Orchestrator   │  (no tools, synthesis only)
                └────────┬─────────┘
           ┌─────────────┼─────────────┐
           ▼             ▼             ▼
┌──────────────┐ ┌──────────────┐ ┌──────────────┐
│   Security   │ │   Quality    │ │    Docs      │
│    Worker    │ │    Worker    │ │    Worker    │
└──────┬───────┘ └──────┬───────┘ └──────┬───────┘
       │                │                │
  scan_deps     test_coverage +     scan_docs
                complexity
```

## Project Structure

```
io.github.mbbhalla.agentio.module.patterns/
├── adversarial/
│   ├── Runner.kt
│   ├── function/AdversarialAgenticFunctions.kt
│   └── server/ApiDesignMcpServer.kt, ApiDesignTools.kt
└── orchestration/
    ├── Runner.kt
    ├── function/OrchestrationAgenticFunctions.kt
    └── server/OrchestrationMcpServers.kt, OrchestrationTools.kt
```

## Dependencies

- `agentio-core`
