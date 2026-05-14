# agentio-examples

Example agents built with AgentIO, demonstrating how to use the SDK for real-world tasks.

## Hacker News Agent

An agent that searches and summarizes Hacker News content using an external MCP server for tool access.

### Package Structure

```
io.github.mbbhalla.agentio.examples/
└── hackernews/
    ├── Runner.kt                          Entry point
    ├── function/
    │   └── HackerNewsAgenticFunction.kt   Agent definition + provider
    └── server/
        └── server_hacker_news.sh          MCP server launch script
```

### How It Works

1. **HackerNewsAgenticFunction** extends `AbstractAgenticFunction` with:
   - Input: a news topic string
   - Output: a set of news summaries
   - Instruction: "Find the latest news on this topic"

2. **HackerNewsAgenticFunctionProvider** wires up:
   - Bedrock client (Claude Opus 4.5, us-west-2)
   - MCP client connected to an external Hacker News MCP server via stdio
   - Agent configuration with system prompt including current UTC time

3. **Runner** invokes the agent with a sample topic and prints results.

### Running

```bash
./gradlew :agentio-examples:RunHackerNewsAgenticFunction
```

This launches the MCP server script and runs the agent against it.

### Key Patterns Demonstrated

- Connecting to an external MCP server via `StdioClientTransport`
- Using `McpClients` as the `ToolsProvider`
- Structured input/output with `@Serializable` data classes
- System prompt with dynamic content (current timestamp)

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
