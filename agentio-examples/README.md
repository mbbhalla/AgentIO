# agentio-examples

Example agents built with AgentIO, demonstrating how to use the SDK for real-world tasks.

## Examples

| Example | MCP Type | Gradle Task | Demonstrates |
|---------|----------|-------------|--------------|
| Hacker News | External (uvx) | `RunHackerNewsAgenticFunction` | External MCP server via `StdioClientTransport` |
| Fetch | External (uvx) | `RunFetchAgenticFunction` | Technical content analysis from URLs |
| Git Analyzer | In-process | `RunGitAnalyzerAgenticFunction` | `AbstractMcpServer` + `PipedStreamsExchange` + custom tools |
| Code Metrics | In-process | `RunCodeMetricsAgenticFunction` | `AbstractMcpServer` + `EventListener` for observability |

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
└── codemetrics/
    ├── Runner.kt
    ├── function/CodeMetricsAgenticFunction.kt
    └── server/CodeMetricsMcpServer.kt, CodeMetricsTools.kt
```

## Dependencies

- `agentio-core`
- AWS SDK for Kotlin (Bedrock Runtime)
- Model Context Protocol Kotlin SDK
- Vavr
- Kotlinx Serialization

## Build

```bash
./gradlew :agentio-examples:build
```
