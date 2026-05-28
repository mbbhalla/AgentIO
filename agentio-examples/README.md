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
└── text2sql/
    ├── Runner.kt
    └── data/RetailDatabase.kt, EmployeeDatabase.kt
```

## Dependencies

- `agentio-core`
- `agentio-module-text2sql`
- `agentio-module-data`
- AWS SDK for Kotlin (Bedrock Runtime)
- Model Context Protocol Kotlin SDK
- Vavr
- Kotlinx Serialization

## Build

```bash
./gradlew :agentio-examples:build
```
