# agentio-core

Core SDK module providing the foundational abstractions for building GenAI agents on the JVM.

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
    ├── server/          AbstractMcpServer (in-process MCP via PipedStreams)
    └── tool/            ToolsProvider, AbstractMcpTool, McpClients
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

## Dependencies

This module has no internal project dependencies. It depends on:
- AWS SDK for Kotlin (Bedrock Runtime)
- Model Context Protocol Kotlin SDK
- Kotlinx Serialization
- Jackson (JSON schema generation/validation)
- Vavr (functional Try type)
- SLF4J + Logback

## Build

```bash
./gradlew :agentio-core:build
./gradlew :agentio-core:test
```
