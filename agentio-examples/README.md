# agentio-examples

Example agents built with AgentIO, demonstrating how to use the SDK for real-world tasks.

## Examples

| Example | MCP Type | Gradle Task | Demonstrates |
|---------|----------|-------------|--------------|
| Hacker News | External (uvx) | `RunHackerNewsAgenticFunction` | External MCP server via `StdioClientTransport` |
| Fetch | External (uvx) | `RunFetchAgenticFunction` | Technical content analysis from URLs |
| Git Analyzer | In-process | `RunGitAnalyzerAgenticFunction` | `AbstractMcpServer` + `PipedStreamsExchange` + custom tools |
| Code Metrics | In-process | `RunCodeMetricsAgenticFunction` | `AbstractMcpServer` + `EventListener` for observability |
| Adversarial | In-process | `RunAdversarialAgenticFunction` | Agent ↔ CriticAgent iterative refinement pattern |
| Orchestration | In-process | `RunOrchestrationAgenticFunction` | Orchestrator + parallel Workers pattern |
| Text2SQL (Retail) | In-process | `RunText2SqlAgenticFunction-RetailDB` | DuckDB from SQL statements + correctness-at-construction |
| Text2SQL (Employee) | In-process | `RunText2SqlAgenticFunction-EmployeeDB` | DuckDB from Parquet files + correctness-at-construction |
| COMPASS | In-process | `RunCompassAgenticFunction` | Multi-agent, domain-agnostic: NL problem → grounded analysis → SMTLIB2 formula → Z3 solver models (sample run uses supply-chain data) |

All examples are self-sufficient — no API keys or tokens required.

## Running

```bash
# Hacker News — find and summarize news on a topic
./gradlew :agentio-examples:RunHackerNewsAgenticFunction

# Fetch — fetch a URL and extract structured technical insights
./gradlew :agentio-examples:RunFetchAgenticFunction

# Git Analyzer — analyze this repo's git history using in-process MCP tools
./gradlew :agentio-examples:RunGitAnalyzerAgenticFunction

# Code Metrics — analyze codebase complexity with full event observability
./gradlew :agentio-examples:RunCodeMetricsAgenticFunction

# Adversarial — designer agent iterates with a critic agent until API design is approved
./gradlew :agentio-examples:RunAdversarialAgenticFunction

# Orchestration — orchestrator dispatches parallel workers and synthesizes results
./gradlew :agentio-examples:RunOrchestrationAgenticFunction

# Text2SQL (Retail) — NL to SQL against in-memory DuckDB retail database (from SQL statements)
./gradlew :agentio-examples:RunText2SqlAgenticFunction-RetailDB
./gradlew :agentio-examples:RunText2SqlAgenticFunction-RetailDB -Pquery="which sites have inventory more than 1000"

# Text2SQL (Employee) — NL to SQL against in-memory DuckDB employee database (from Parquet files)
./gradlew :agentio-examples:RunText2SqlAgenticFunction-EmployeeDB
./gradlew :agentio-examples:RunText2SqlAgenticFunction-EmployeeDB -Pquery="which engineers have a rating above 4"

# COMPASS — NL problem → grounded analysis → SMTLIB2 formula → Z3 solver models
# (domain-agnostic; this example runs against a supply-chain dataset)
./gradlew :agentio-examples:RunCompassAgenticFunction
./gradlew :agentio-examples:RunCompassAgenticFunction \
    -Pobjective="Overstock at site DC-NEWARK for product MB-Z690 in June 2025"
```

## Hacker News Agent

An agent that searches and summarizes Hacker News content using an external MCP server (`mcp-hn`) for tool access.

### Key Patterns

- Connecting to an external MCP server via `StdioClientTransport`
- Using `McpClients` as the `ToolsProvider`
- Structured input/output with `@Serializable` data classes
- System prompt with dynamic content (current timestamp)

## Fetch Agent

An agent that fetches web content and produces structured technical summaries — key points, takeaways, and technologies mentioned.

### Key Patterns

- External MCP server (`mcp-server-fetch`) for web content retrieval
- Structured multi-field output (title, keyPoints, takeaways, technologies)
- Focused analysis via configurable `focus` parameter

## Git Analyzer Agent

An agent that analyzes a git repository's development activity using custom in-process MCP tools that wrap git commands.

### Key Patterns

- **In-process MCP via `AbstractMcpServer`** — no external process needed
- **`PipedStreamsExchange`** — agent and MCP server communicate within the same JVM
- **Custom `AbstractMcpTool` implementations** — `git_log`, `git_diff_stat`, `git_file_authors`
- Runs against the AgentIO repository itself

## Code Metrics Agent

An agent that analyzes source code complexity, dependency structure, and produces health recommendations — with full lifecycle event observability.

### Key Patterns

- **In-process MCP via `AbstractMcpServer`** with file-system analysis tools
- **`EventListener`** — logs every agent lifecycle event (LLM calls, tool calls, latency, token usage)
- **Custom tools** — `list_source_files`, `file_complexity`, `dependency_graph`
- Runs against the AgentIO repository itself

## Adversarial Agent (Designer ↔ Critic)

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

## Orchestration Agent (Orchestrator + Workers)

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

## Text2SQL Agent

An agent that converts natural language questions into valid DuckDB SQL. Two database environments demonstrate different data-loading strategies:

- **RetailDB** — schema and seed data defined as SQL statements in Kotlin (`DuckDbDatabaseEnvironment.fromStatements`)
- **EmployeeDB** — loaded from Parquet files on the classpath (`DuckDbDatabaseEnvironment.fromParquet`)

### Key Patterns

- **Correctness at construction** — `Output.init` validates SQL via DuckDB `EXPLAIN`; invalid SQL cannot be instantiated
- **Agent reinforcement loop** — `ExecuteSqlTool` validates and returns errors to the agent for self-correction
- **Two data-loading strategies** — SQL statements (RetailDB) vs Parquet files (EmployeeDB)
- **Domain-agnostic models** — `DataValue`, `Dataset`, `ExplainResult`, `ColumnType` in `model/` package are reusable across domains

### Architecture

```
┌─────────────────────────────────────────────┐
│         Text2SqlAgenticFunction             │
│  Input: natural language query              │
│  Output: validated SQL (init block proves)  │
└────────────────────┬────────────────────────┘
                     │ tools
        ┌────────────┼────────────┐
        ▼            ▼            ▼
  list_tables   get_tables   execute_sql
        │            │            │
        └────────────┴────────────┘
                     │
        ┌────────────┴────────────┐
  RetailDatabase          EmployeeDatabase
  (from SQL stmts)        (from Parquet)
```

## COMPASS

**C**onstraint **O**ptimization via **M**ulti-agent **P**roblem **A**nalysis and **S**olver **S**ynthesis.

A two-agent pipeline that turns an English problem statement, posed against
any tabular dataset, into a Z3-solvable SMTLIB2 formula, then enumerates
satisfying decision-variable assignments. The pipeline itself is
**domain-agnostic** — it discovers schema via tools, grounds analysis in SQL
over real data, and produces a formal constraint program. The sample run
shipped with this example targets a supply-chain dataset (sites, products,
inventory snapshots, forecasts, inbound / outbound orders) bundled as
Parquet files; swapping in a different dataset and a different set of
canonical `SMTLIB2Variable` kinds is enough to retarget COMPASS to
finance, scheduling, network planning, or any other domain that fits the
constraint-programming model.

### Pipeline

```
┌────────────────────────────────────────────────────────────────────┐
│ Stage 1 — AnalyzerAgenticFunction                                  │
│   Input:  English problem ("Overstock at site X for product P …")  │
│   Output: AnalysisResult — set of result items, each with a SQL    │
│           SELECT against real dataset tables that yields the value │
│   Tools:  list_tables, get_tables, execute_sql,                    │
│           analysis_result_validator (re-runs every SQL)            │
└─────────────────────────────────┬──────────────────────────────────┘
                                  │ AnalysisResult
                                  ▼
┌────────────────────────────────────────────────────────────────────┐
│ Stage 2 — ConstraintGeneratorAgenticFunction                       │
│   Input:  AnalysisResult                                           │
│   Output: SMTLIBv2Formula + Markdown explanation                   │
│   Tools:  list_tables, get_tables, execute_sql,                    │
│           smtlibv2_syntax_checker (Z3 parse + sanity solve)        │
└─────────────────────────────────┬──────────────────────────────────┘
                                  │ SMTLIBv2Formula
                                  ▼
┌────────────────────────────────────────────────────────────────────┐
│ Stage 3 — Z3SolverFacade.solve                                     │
│   Output: Set<SolverModel>  (up to N satisfying assignments)       │
└────────────────────────────────────────────────────────────────────┘
```

### Key Patterns

- **Grounded analysis** — every numeric value the analyzer reports carries
  the SQL that produces it. `AnalysisResultValidatorTool` re-executes
  each SQL and verifies the returned scalar matches the reported value
  (with Long↔Double numeric equivalence).
- **Correctness at construction (formula)** — `SMTLIBv2Formula(text)`
  parses through Z3 in its `init` block. The Constraint Generator's
  `Output` cannot be deserialized if the formula doesn't parse.
- **Canonical variable kinds** — `SMTLIB2Variable` is a sealed hierarchy
  of `data object` instances (e.g. `VariableModifyPurchaseOrderLineItemQuantity`).
  Each kind declares its `associatedDataTable`, `keyColumns`, and a
  `nameFormat` like `V_MODIFY_PO_LI_QTY:::<id>:::<order_id>:::<product_id>`,
  ensuring variable names are anchored to real entities in the dataset.
- **Two MCP servers, one DB env** — `AnalyzerMcpServer` and
  `ConstraintGeneratorMcpServer` share an active `DatabaseEnvironment`
  but expose different tool sets (analyzer gets the result-validator,
  constraint generator gets the SMTLIB2 syntax checker).

### Sample Run

```
$ ./gradlew :agentio-examples:RunCompassAgenticFunction

=== STAGE 1: Analyzer — 'Overstock at site DC-SEATTLE for product SSD-1TB-NVMe in the month of June 2025'
Analyzer produced 9 result items
   ↳ projected_inventory_quantity = 250    (via SELECT … FROM asc_insights_projected_inventory …)
   ↳ forecasted_demand_quantity   = 100
   ↳ overstock_quantity           = 150
   ↳ inventory_risk_type          = OVERSTOCK
   ↳ unit_cost                    = 95.0
   ↳ overstock_value_usd          = 14250.0
   …

=== STAGE 2: ConstraintGenerator
ConstraintGenerator produced SMTLIB2 formula:
  (set-logic QF_LIA)
  (declare-const |V_MODIFY_PO_LI_QTY:::IL-FR-SSD-001:::IO-FR-SAMSUNG-001:::SSD-1TB-NVMe| Int)
  (declare-const |V_MODIFY_PO_LI_QTY:::IL-LT-SSD-001:::IO-LT-SAMSUNG-001:::SSD-1TB-NVMe| Int)
  (assert (<= |…IL-FR…|  0))
  (assert (>= |…IL-FR…| (- 150)))
  (assert (<= (+ |…IL-FR…| |…IL-LT…|) (- 150)))
  (assert (>= (+ 250 |…IL-FR…| |…IL-LT…|) 100))

=== STAGE 3: Solver
Solver returned 5 model(s)
  Model #1: IL-FR=-150, IL-LT=0
  Model #2: IL-FR=0,    IL-LT=-150
  Model #3: IL-FR=-1,   IL-LT=-149
  …
```

### Sample Dataset

`agentio-examples/src/main/resources/supplychain_dataset_1/` contains 26
parquet tables (sites, products, inventory snapshots, forecasts, inbound /
outbound orders, sourcing rules, vendor lead times, …) plus a
`schemaMetadata.yml` derived from public-facing table descriptions. The
dataset has intentional dirty rows; `ScdlDatabase` loads it with
`ConstraintStrategies(... = IGNORE)` so FK violations are warned, not
thrown.

### Architecture

```
                    ┌──────────────────┐
                    │     Runner       │  runBlocking { ... }
                    └────────┬─────────┘
              ┌──────────────┼──────────────┐
              ▼              ▼              ▼
    ┌──────────────┐ ┌──────────────┐ ┌──────────────┐
    │   Analyzer   │ │  Constraint  │ │ Z3SolverFacade │
    │  (Bedrock)   │ │   Generator  │ │  (Z3 native) │
    │              │ │   (Bedrock)  │ │              │
    └──────┬───────┘ └──────┬───────┘ └──────────────┘
           │                │
           ▼                ▼
    AnalyzerMcpServer  ConstraintGeneratorMcpServer
        │                  │
        ├─ list_tables     ├─ list_tables
        ├─ get_tables      ├─ get_tables
        ├─ execute_sql     ├─ execute_sql
        └─ analysis_       └─ smtlibv2_
           result_            syntax_
           validator          checker
                              ↑
                         agentio-module-solver
                            (SMTLIBv2Formula
                             + Z3 turnkey)
```

## Package Structure

```
io.github.mbbhalla.agentio.examples/
├── hackernews/
│   ├── Runner.kt
│   ├── function/HackerNewsAgenticFunction.kt
│   └── server/server_hacker_news.sh
├── fetch/
│   ├── Runner.kt
│   ├── function/FetchAgenticFunction.kt
│   └── server/server_fetch.sh
├── gitanalyzer/
│   ├── Runner.kt
│   ├── function/GitAnalyzerAgenticFunction.kt
│   └── server/GitAnalyzerMcpServer.kt, GitTools.kt
├── codemetrics/
│   ├── Runner.kt
│   ├── function/CodeMetricsAgenticFunction.kt
│   └── server/CodeMetricsMcpServer.kt, CodeMetricsTools.kt
├── adversarial/
│   ├── Runner.kt
│   ├── function/AdversarialAgenticFunctions.kt
│   └── server/ApiDesignMcpServer.kt, ApiDesignTools.kt
├── orchestration/
│   ├── Runner.kt
│   ├── function/OrchestrationAgenticFunctions.kt
│   └── server/OrchestrationMcpServers.kt, OrchestrationTools.kt
├── text2sql/
│   ├── Runner.kt
│   └── data/RetailDatabase.kt, EmployeeDatabase.kt
└── compass/
    ├── Runner.kt
    ├── ScdlDatabase.kt
    ├── model/
    │   ├── AnalysisResult.kt          — grounded analysis (resultItems with SQL provenance)
    │   └── SMTLIB2Variable.kt         — sealed hierarchy of canonical variable kinds
    ├── function/
    │   ├── AnalyzerAgenticFunction.kt
    │   └── ConstraintGeneratorAgenticFunction.kt
    ├── tool/
    │   ├── CompassDbTools.kt          — list_tables, get_tables, execute_sql
    │   ├── AnalysisResultValidatorTool.kt
    │   └── SmtLibV2SyntaxCheckerTool.kt
    └── server/CompassMcpServers.kt    — AnalyzerMcpServer, ConstraintGeneratorMcpServer
```

## Dependencies

- `agentio-core`
- `agentio-module-text2sql`
- `agentio-module-data`
- `agentio-module-solver`
- AWS SDK for Kotlin (Bedrock Runtime)
- Model Context Protocol Kotlin SDK
- Kotlinx Serialization

## Build

```bash
./gradlew :agentio-examples:build
```
