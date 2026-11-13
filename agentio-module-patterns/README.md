# agentio-module-patterns

Agent design patterns built with AgentIO. Each pattern is a self-contained package with a `Runner`
and a matching `Run*` Gradle task. Domain-specific examples that are not primarily about how
agents coordinate stay in [`agentio-examples`](../agentio-examples/README.md).

## Patterns

| Pattern | Gradle Task | Who decides control flow | Demonstrates |
|---------|-------------|--------------------------|--------------|
| Adversarial | `RunAdversarialAgenticFunction` | Code (bounded loop) | Designer ↔ Critic iterative refinement (evaluator-optimizer) |
| Orchestration | `RunOrchestrationAgenticFunction` | Code (fan-out, join) | Orchestrator + parallel Workers, synthesis without tools |
| GOAP | `RunGoapAgenticFunction` | Deterministic A* planner | Actions with typed preconditions/effects, blackboard, replan after every action |
| Plan-and-Execute | `RunPlanExecuteAgenticFunction` | LLM planner, code sequencer | Plan as data, step executor with tools, replan on failure, synthesis |
| Routing | `RunRoutingAgenticFunction` | LLM classifier, once | One cheap classification, exactly one specialist runs, decline on low confidence |
| Handoff | `RunHandoffAgenticFunction` | The working agent | Control transfers peer-to-peer with the transcript; policy enforced by code |
| Model Cascade | `RunModelCascadeAgenticFunction` | Code (verify, escalate) | Cheapest tier first, escalate on failed verification; token cost per attempt |
| Human-in-the-Loop | `RunHumanInTheLoopAgenticFunction` | A human | Propose → review → apply / revise / reject; the agent can only read |

All patterns are self-sufficient — no API keys or tokens required beyond AWS credentials for Bedrock.

## Running

```bash
# Adversarial — designer agent iterates with a critic agent until API design is approved
./gradlew :agentio-module-patterns:RunAdversarialAgenticFunction
./gradlew :agentio-module-patterns:RunAdversarialAgenticFunction -Prequirements="Build a library lending API"

# Orchestration — orchestrator dispatches parallel workers and synthesizes results
./gradlew :agentio-module-patterns:RunOrchestrationAgenticFunction

# GOAP — planner derives the action sequence to a ReleaseDecision from typed pre/post-conditions
./gradlew :agentio-module-patterns:RunGoapAgenticFunction
./gradlew :agentio-module-patterns:RunGoapAgenticFunction -PriskMode=llm   # exclude the cheap scan; planner reroutes

# Plan-and-Execute — LLM plans, code executes each step with tools, replans on failure
./gradlew :agentio-module-patterns:RunPlanExecuteAgenticFunction
./gradlew :agentio-module-patterns:RunPlanExecuteAgenticFunction -Pgoal="List every Gradle module and its test count"

# Routing — classify once, dispatch to exactly one specialist
./gradlew :agentio-module-patterns:RunRoutingAgenticFunction
./gradlew :agentio-module-patterns:RunRoutingAgenticFunction -Prequest="What weekday is 2026-07-04?"

# Handoff — triage agent hands the case to specialists, who may hand it onward
./gradlew :agentio-module-patterns:RunHandoffAgenticFunction
./gradlew :agentio-module-patterns:RunHandoffAgenticFunction -PcustomerId=cust-2002 -Pmessage="Why was I charged 40 dollars?"

# Model Cascade — cheapest tier first, escalate when verification fails
./gradlew :agentio-module-patterns:RunModelCascadeAgenticFunction
./gradlew :agentio-module-patterns:RunModelCascadeAgenticFunction -Ptask="How many vowels are in 'onomatopoeia'?"

# Human-in-the-Loop — scripted reviewer by default; interactive reads approve/reject/feedback from the terminal
./gradlew :agentio-module-patterns:RunHumanInTheLoopAgenticFunction
./gradlew :agentio-module-patterns:RunHumanInTheLoopAgenticFunction -Pmode=interactive
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

## GOAP (Goal-Oriented Action Planning)

The approach popularised for JVM agents by [Embabel](https://docs.embabel.com/embabel-agent/guide/0.3.1/). An action declares the fact
types it `requires` and the one it `produces`; facts live on an immutable, type-keyed `Blackboard`. A deterministic A* planner
(`GoapPlanner`) finds the cheapest action sequence whose end state contains the goal type. `GoapAgentProcess` runs the first action,
publishes its effect, and plans again from the new state (Embabel's OODA loop). A failed action is dropped from the available set and
the planner finds another route or reports `Stuck`.

The demo goal is a `ReleaseDecision` for this repository. Two actions produce `RiskAssessment`: a cost-1 deterministic scan and a
cost-4 LLM assessor. With both available the planner picks the scan (plan cost 10); with `-PriskMode=llm` it reroutes through the
LLM (cost 13). Nothing in the action code encodes that choice.

### Key Patterns

- **Typed preconditions and effects** — `GoapAction<O>(requires: Set<KClass<*>>, produces: KClass<O>, cost)`; the planner never looks inside a fact
- **`AgenticFunctionAction`** — adapts any `AbstractAgenticFunction<I, O>` into an action whose effect is its `Output` type
- **Replanning after every action** — the plan shrinks step by step and adapts to failures without any hardcoded sequence
- **LLM inside actions only** — control flow is deterministic and unit-tested (`GoapPlannerTest`)

### Architecture

```
 Blackboard[RepoPath]
      │ plan: collect_repo_facts -> heuristic_risk_scan -> draft_release_notes -> decide_release   (cost 10)
      ▼
 Blackboard[RepoPath, RepoFacts]
      │ replan: heuristic_risk_scan -> draft_release_notes -> decide_release
      ▼
 Blackboard[.., RiskAssessment]
      │ replan: draft_release_notes -> decide_release
      ▼
 Blackboard[.., ReleaseNotes]
      │ replan: decide_release
      ▼
 Blackboard[.., ReleaseDecision]   goal satisfied
```

## Plan-and-Execute

The LLM counterpart to GOAP: a Planner agent writes the plan as data (`Plan` of `PlanStep`s), an Executor agent runs one step at a
time with read-only workspace tools, and the `PlanAndExecute` loop records each `StepResult`. On a `FAILED` step it asks the Planner
for a revised plan covering the remaining work (bounded by `maxReplans`), then a Synthesizer writes the deliverable strictly from
the gathered facts.

### Key Patterns

- **Plan as data** — the planner has no tools; it reasons about a tool catalogue and emits steps the code then sequences
- **Executor isolation** — one step per invocation, prior facts passed in, honest `FAILED` reporting required
- **Replan on failure** — an executor invocation that fails outright is itself a `FAILED` step, so the loop can route around it
- **Synthesis without tools** — the final agent cannot go looking for more data; gaps are reported, not invented

### Architecture

```
┌─────────┐  Plan   ┌──────────────────────┐  step i   ┌──────────┐
│ Planner │ ──────▶ │  PlanAndExecute loop │ ────────▶ │ Executor │──▶ WorkspaceMcpServer
└─────────┘ ◀────── │  (code)              │ ◀──────── └──────────┘
   replan on FAILED └──────────┬───────────┘ StepResult
                               │ all steps done
                               ▼
                        ┌─────────────┐
                        │ Synthesizer │ ──▶ deliverable + gaps
                        └─────────────┘
```

## Routing

A cheap Router agent (no tools, temperature 0) classifies a request into a closed `Route` enum with a confidence. `RequestRouter`
dispatches to exactly one specialist, or declines when the route is `UNSUPPORTED` or confidence is below the threshold, without
spending a specialist call. Compare with orchestration, where every worker runs.

### Key Patterns

- **Closed route set** — the output schema's enum rules out unknown destinations; the registry is checked at construction
- **One `SpecialistAgenticFunction` class** — same input/output shape, different system prompt and tools per route
- **Specialists with exact tools** — the date specialist computes with `DateToolsMcpServer`, the repository specialist with `WorkspaceMcpServer`
- **Confidence gate** — low-confidence or out-of-scope requests are declined explicitly

```
 request ──▶ Router ──▶ REPOSITORY ──▶ repository specialist (workspace tools)
                   ├──▶ DATES ───────▶ date specialist (date tools)
                   ├──▶ WRITING ─────▶ writing specialist (no tools)
                   └──▶ UNSUPPORTED ─▶ declined (no specialist call)
```

## Handoff

Swarm-style control transfer. A Triage agent takes a support case and hands it to Billing or Technical; a specialist finishes its
part and may hand onward. The transcript travels with every handoff. The agent decides *when* to hand off; `HandoffPolicy` and the
hop budget are enforced by `HandoffConversation` in code.

### Key Patterns

- **Handoff in the typed output** — `HandoffDecision` is a closed enum (`STAY`, `TO_BILLING`, ...) rather than a tool call or a nullable target
- **Policy enforced by code** — a forbidden transfer ends the conversation as `Failed`; the model cannot route around it
- **Context carried, not re-derived** — each agent sees the full transcript so it does not redo earlier work
- **Role-scoped tools** — only Billing can look up invoices, only Technical can check service status

```
 customer ──▶ TRIAGE ──▶ BILLING ──▶ TECHNICAL ──▶ STAY (resolved)
                         lookup_invoices   check_service_status
```

## Model Cascade

`ModelCascade` tries tiers in order of cost and escalates only when a tier's result is rejected: the invocation failed, or the
tier's `accept` check (self-reported confidence plus, where the task allows, a deterministic verifier) said no. Every attempt
carries its token usage so the saving from answering cheaply, and the price of escalating, are visible.

The demo tiers: Opus 4.5 with no tools and a small output budget → Opus 4.6 with exact-computation tools → Opus 5.5 with tools and a
thinking pass. A passive-voice rewrite and a letter count resolve at the first tier; the SHA-256 task is honestly reported at 0.1
confidence by the tool-less tier and resolves at the tooled tier, verified against a locally computed digest.

### Key Patterns

- **Verify-then-escalate** — the accept check sees the input, so a verifier can compare the answer with the task
- **Honest confidence** — the system prompt asks the model to rate low when it lacks the means; the gate enforces the bar
- **Same function, different budgets** — `TaskAgenticFunction` is identical across tiers except model, tools and thinking mode
- **Cost accounting** — `Attempt.inputTokens`/`outputTokens` per tier

## Human-in-the-Loop

The advisor agent proposes fleet right-sizing changes from a read-only tool. `HumanInTheLoop` hands each `RightsizingProposal` to an
`ApprovalGate`: `Approve` applies it (a pure `Fleet.apply`), `Revise(feedback)` feeds the reviewer's words into the next proposal,
`Reject` ends the loop with the fleet untouched. The agent never has a tool that changes anything.

### Key Patterns

- **The consequential step is outside the agent** — applying changes happens in code, only after approval
- **Feedback loop with a human** — `Revise` feedback becomes part of the next instruction, bounded by `maxRounds`
- **Pluggable gate** — `ScriptedApprovalGate` for unattended runs and tests, `ConsoleApprovalGate` for a real reviewer
- **Pure state transition** — `Fleet.apply` returns a new fleet and rejects impossible changes, so before/after cost is exact

## Project Structure

```
io.github.mbbhalla.agentio.module.patterns/
├── common/
│   ├── PatternAgents.kt           — Bedrock client, AgentConfiguration helper, in-process MCP wiring, LoggingEventListener
│   └── WorkspaceMcpServer.kt      — read-only list_directory, read_file_head, count_files confined to one directory
├── adversarial/
│   ├── Runner.kt
│   ├── function/AdversarialAgenticFunctions.kt
│   └── server/ApiDesignMcpServer.kt, ApiDesignTools.kt
├── orchestration/
│   ├── Runner.kt
│   ├── function/OrchestrationAgenticFunctions.kt
│   └── server/OrchestrationMcpServers.kt, OrchestrationTools.kt
├── goap/
│   ├── Blackboard.kt, GoapAction.kt, GoapPlanner.kt, GoapAgentProcess.kt, AgenticFunctionAction.kt
│   ├── release/ReleaseFacts.kt, ReleaseActions.kt, ReleaseAgenticFunctions.kt
│   └── Runner.kt
├── planexecute/
│   ├── PlanExecuteModel.kt, PlanExecuteAgenticFunctions.kt, PlanAndExecute.kt
│   └── Runner.kt
├── routing/
│   ├── RoutingAgenticFunctions.kt, RequestRouter.kt, DateToolsMcpServer.kt
│   └── Runner.kt
├── handoff/
│   ├── SupportModel.kt, SupportAgenticFunction.kt, HandoffConversation.kt, SupportMcpServer.kt
│   └── Runner.kt
├── cascade/
│   ├── ModelCascade.kt, TaskAgenticFunction.kt, ComputeToolsMcpServer.kt
│   └── Runner.kt
└── hitl/
    ├── FleetModel.kt, FleetMcpServer.kt, ApprovalGate.kt, RightsizingAdvisorAgenticFunction.kt, HumanInTheLoop.kt
    └── Runner.kt
```

## Tests

The pattern machinery is pure and tested without an LLM: `GoapPlannerTest` (planning, cost choice, replanning on failure, stuck),
`ModelCascadeTest` (acceptance, escalation, exhaustion, verifier access to input), `HandoffPolicyTest`, `FleetTest` (pure apply,
rejection of impossible changes, scripted gate).

```bash
./gradlew :agentio-module-patterns:test
```

## Dependencies

- `agentio-core`
