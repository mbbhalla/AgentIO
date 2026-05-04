# AgentIO

A Kotlin SDK for building GenAI agents that provides a simple yet powerful abstraction for creating agentic systems on the JVM. **Features breakthrough in-process MCP integration via PipedStreams**, eliminating the complexity of distributed MCP architectures while maintaining full protocol compliance.

## Core Concept: AgenticFunction

AgentIO introduces the concept of **AgenticFunction** - an extension of traditional functions that maintains familiar input-output interfaces while replacing static business logic with intelligent reasoning capabilities powered by Large Language Models (LLMs).

Unlike conventional functions where business logic is explicitly implemented by developers, AgenticFunction delegates the reasoning process to an LLM. The LLM synthesizes appropriate logic dynamically based on provided contextual information and available tool sets, effectively replacing static code with adaptive behavior.

This approach preserves the ergonomic benefits of traditional function composition while introducing intelligent reasoning. AgenticFunction instances can be seamlessly integrated into existing codebases and composed hierarchically to create sophisticated multi-agent systems.

## Key Features

- **Generic & Model Agnostic**: Business domain problem agnostic and not coupled to any specific LLM model
- **Structured I/O**: Uses structured objects for representing data shapes passed to LLMs instead of raw text
- **MCP Protocol Support**: Connects to multiple MCP (Model Context Protocol) servers for tool access
- **Accuracy Improvements**: Built-in mechanisms for improving response accuracy through retries and consensus
- **Context Management**: Chainable Context Memory Managers with turn-aware execution, including adaptive and compacting CMM implementations
- **Memory Management**: Load and store context from/to backend storage via ContextProviders and ContextWriters
- **Thinking Mode**: Additional reasoning iterations for more accurate responses
- **Evaluation Framework**: Built-in evaluation tools for testing and refining agents

## Architecture

AgentIO provides three core abstractions:

1. **Agent**: Implementation of `AbstractAgenticFunction` - the reasoning component
2. **Tool**: Implementation of `AbstractMcpTool` - individual capabilities/actions  
3. **MCP Server**: Implementation of `AbstractMcpServer` - hosts and manages tools

### Key Innovation: In-Process MCP via PipedStreams

AgentIO enables **MCP protocol communication within a single JVM process** using PipedStreams. This solves the fundamental MCP integration problem:

- **Traditional MCP**: Requires separate processes/containers for agents and MCP servers, adding deployment complexity
- **AgentIO's Solution**: Agent and MCP servers run in the same JVM process, communicating via inter-thread PipedStreams while maintaining full MCP protocol compliance

This means your tools can still access any backend service (databases, APIs, external systems) while eliminating the operational overhead of managing distributed MCP components.

## Quick Start

### 1. Define Your Agent

Create a concrete agent by extending `AbstractAgenticFunction`:

```kotlin
internal class HackerNewsAgenticFunction(
    agentConfiguration: AgentConfiguration,
) : AbstractAgenticFunction<
    HackerNewsAgenticFunction.HackerNewsAgenticFunctionInput,
    HackerNewsAgenticFunction.HackerNewsAgenticFunctionOutput,
    >(
    agentConfiguration,
) {
    @Serializable
    data class HackerNewsAgenticFunctionInput(
        @field:Description("News topic")
        val value: String,
    ) : Instructible.WithInstruction {
        override fun instructionId() = "ExampleId"

        override fun instruction() = """
            Find the latest news on this topic: '$value'
        """.trimIndent()

        override fun systemInstruction(): String? = null
    }

    @Serializable
    data class HackerNewsAgenticFunctionOutput(
        @field:Description("News summaries")
        val summaries: Set<String>,
    )

    override fun getInputKClass() = HackerNewsAgenticFunctionInput::class
    override fun getOutputKClass() = HackerNewsAgenticFunctionOutput::class
}

```

### 2. Create Tools

Implement tools that your agent can use:

```kotlin
data object HackerNewsSearchTool : AbstractMcpTool<
    HackerNewsSearchTool.HackerNewsSearchInput,
    HackerNewsSearchTool.HackerNewsSearchOutput
>() {

    @field:Title("Tool input")
    data class HackerNewsSearchInput(
        @field:Description("Search query")
        val query: String,
        @field:Description("Maximum number of results")
        val limit: Int = 10
    )

    @field:Title("Tool output")
    data class HackerNewsSearchOutput(
        @field:Description("Search results")
        val results: List<SearchResult>
    )

    data class SearchResult(
        val id: Long,
        val title: String,
        val url: String?,
        val score: Int
    )

    override fun name() = "hackernews_tool"
    override fun description() = "Get info from HN"

    override fun buildInput(
        callToolRequest: CallToolRequest,
    ): HackerNewsSearchInput {
        // Derive input from  CallToolRequest
    }

    override fun getToolConfig(): ToolConfig = ToolConfig(
        emitSchemaAndRequiredAttributesForAllToolCalls = true / false,
    )

    override fun invoke(input: HackerNewsSearchInput): HackerNewsSearchOutput {
        // Derive output from input
    }
    
    override fun getInputKClass() = HackerNewsSearchInput::class
    override fun getOutputKClass() = HackerNewsSearchOutput::class
}
```

### 3. Set Up MCP Server

Create an MCP server to host your tools:

```kotlin
class HackerNewsServer : AbstractMcpServer() {
    override fun getTools() = listOf(
        HackerNewsSearchTool()
    )
}
```

### 4. Configure and Run

Configure your agent and execute:

```kotlin
fun main() = runBlocking {
    val agentConfiguration = AgentConfiguration(
        // supply agent config params like language model etc.
    )

    val agent = HackerNewsAgenticFunction(agentConfiguration)
    
    val result = agent.invoke(
        HackerNewsAgenticFunction.HackerNewsAgenticFunctionInput(
            value = "Latest in Agentic AI Agent collaborations"
        )
    )

    result.onSuccess { agentOutput ->
        println("Results: ${agentOutput.output}")
        println("Tokens used: ${agentOutput.conversation.totalTokenUsage}")
    }.onFailure { error ->
        println("Error: ${error.message}")
    }
}
```

## Advanced Features

### Consensus Mechanisms

Improve accuracy with built-in consensus strategies:

```kotlin
// Multiple trials with majority consensus
val trialsAgent = AgenticFunctionTrials(
    agenticFunction = agent,
    trials = 5,
    selector = MajorityOccurredAgentOutputSelector()
)
```

### Context Management

AgentIO provides a layered context management architecture under `lib/ctx/`:

```
ctx/
├── cmm/                          # Context Memory Managers
│   ├── ContextMemoryManager.kt   # CMM interface + NoOp impl
│   ├── ContextMemoryManagers.kt  # Ordered chain of CMMs
│   ├── adaptive/                 # Adaptive attention-based CMM
│   └── compacting/               # Compacting summarization CMM
├── provider/                     # Read context from external sources
│   └── ContextProvider.kt
└── writer/                       # Write context to external sinks
    └── ContextWriter.kt
```

#### CMM Chain

`ContextMemoryManagers` is an ordered list of `ContextMemoryManager` implementations executed in sequence each turn. Each CMM decides whether to run on a given turn via `shouldExecuteOnTurn(turnNumber)`, enabling frequency-based strategies (e.g., reshuffle every 3rd turn).

```kotlin
val agentConfiguration = AgentConfiguration(
    contextMemoryManagers = ContextMemoryManagers(
        value = listOf(
            myCompactionCmm,                // runs every turn
            AdaptiveContextMemoryManager(),  // runs based on its own frequency
        ),
    ),
    contextProviders = ContextProviders(value = listOf(myProvider)),
    contextWriters = ContextWriters(value = setOf(myWriter)),
)
```

#### IndexedConversation

The agent loop tracks turns via `IndexedConversation` — an immutable pairing of a `Conversation` with its turn number in the sequence. This replaces mutable turn counters with a functional approach where the turn index is carried as a `val` alongside the conversation.

```kotlin
val seed = IndexedConversation(turnNumber = 0, conversation = initialConversation)
val next = seed.next(IndexedConversation(turnNumber = 0, conversation = nextConversation))
// next.turnNumber == 1, next.conversation == nextConversation
```

#### Adaptive Context Memory Manager

A novel CMM that empirically measures LLM attention distribution across the context window and reshuffles content to maximize alignment between segment importance and observed attention. It works by embedding probe tokens, measuring which ones the model recalls, and moving important content to high-attention positions.

For full details on the algorithm, configuration, and usage, see the [Adaptive CMM README](lib/src/main/kotlin/com/amazon/agentio/lib/ctx/cmm/adaptive/README.md).

#### Compacting Context Memory Manager

A CMM that monitors context window usage and, when it crosses a configurable threshold, uses an LLM to summarize older conversation turns while preserving the anchor (initial instruction) and recent turns verbatim. This prevents context overflow in long-running agent sessions.

For full details on the architecture, configuration, and usage, see the [Compacting CMM README](lib/src/main/kotlin/com/amazon/agentio/lib/ctx/cmm/compacting/README.md).

### Evaluation Framework

Evaluate agent by running N iterations and matching output to a given criteria:

```kotlin
val evaluator = AgenticFunctionEvaluator(
    agenticFunction = // agenticFunction To Evaluate,
    input = // input to agenticFunction
    numIterations = 100
)

val results = evaluator.evaluate()
println("Success rate: ${results.successRate}")
```

## Examples

### Hacker News Agent (`/example/hackernews`)  
An agent that searches and analyzes Hacker News content.

**Run with:**
```bash
./gradlew RunHackerNewsAgenticFunction
```

## Building and Testing

### Build the project:
```bash
./gradlew build
```

### Run tests:
```bash
./gradlew test
```

### Check code coverage:
Coverage reports are generated at: `build/brazil-documentation/coverage/index.html`

## Architecture Benefits

### In-Process MCP Integration
AgentIO's PipedStreams innovation fundamentally solves MCP integration challenges:

**The Problem**: Traditional MCP implementations require complex distributed architectures:
- Separate processes for agents and MCP servers
- Network communication overhead and failure points
- Complex deployment and orchestration
- Difficult debugging across process boundaries

**AgentIO's Solution**: In-process MCP via PipedStreams:
- **Single JVM Deployment**: Agent + MCP servers + tools in one artifact
- **Full Backend Access**: Tools can still call any external APIs, databases, or services
- **Zero Network Overhead**: Inter-thread communication instead of network calls
- **Simplified Operations**: No need to manage multiple containers or processes
- **Standard Debugging**: Use familiar JVM debugging tools across the entire stack

### Additional Benefits

**Simplified Deployment**: 
- Single artifact deployment to any JVM environment (Dev, Lambda, ECS, AgentCore, Kubernetes, etc.)
- No container orchestration needed for basic use cases

**Enhanced Developer Experience**:
- Standard JVM debugging tools work seamlessly across agents and tools
- Unit test entire agent + tool combinations in isolation
- Rapid iteration: change code, build, run, repeat

**Structured Data Flow**:
- **Type Safety**: Compile-time verification of data shapes
- **Better LLM Understanding**: Structured schemas improve reasoning accuracy  
- **Clear Contracts**: Well-defined interfaces between components

## Contributing

1. Follow the existing code style and patterns
2. Add tests for new functionality
3. Update documentation for API changes
4. Ensure all builds pass: `./gradlew build`

## License

This project is licensed under the Apache License 2.0. See the [LICENSE](LICENSE) file for details.
