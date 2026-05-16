![Build](https://github.com/mbbhalla/AgentIO/actions/workflows/build.yml/badge.svg)


# AgentIO

A Kotlin SDK for building GenAI agents that provides a simple yet powerful abstraction for creating agentic systems on the JVM. **Features breakthrough in-process MCP integration via PipedStreams**, eliminating the complexity of distributed MCP architectures while maintaining full protocol compliance.

## Core Concept: AgenticFunction

AgentIO introduces the concept of **AgenticFunction** — an extension of traditional functions that maintains familiar input-output interfaces while replacing static business logic with intelligent reasoning capabilities powered by Large Language Models (LLMs).

Unlike conventional functions where business logic is explicitly implemented by developers, AgenticFunction delegates the reasoning process to an LLM. The LLM synthesizes appropriate logic dynamically based on provided contextual information and available tool sets, effectively replacing static code with adaptive behavior.

This approach preserves the ergonomic benefits of traditional function composition while introducing intelligent reasoning. AgenticFunction instances can be seamlessly integrated into existing codebases and composed hierarchically to create sophisticated multi-agent systems.

## Project Structure

```
AgentIO/
├── agentio-core/                  Core SDK — agents, tools, MCP, context management interfaces
├── agentio-cmm-impl/              Context Memory Manager implementations (adaptive, compacting)
├── agentio-eventlistener-impl/    Event listener implementations (checkpointing)
├── agentio-examples/              Example agents (Hacker News)
└── agentio-experiments/           Research experiments (Needle-in-a-Haystack, Distributed Counting)
```

### Maven Coordinates

```
io.github.mbbhalla:agentio-core
io.github.mbbhalla:agentio-cmm-impl
io.github.mbbhalla:agentio-eventlistener-impl
io.github.mbbhalla:agentio-examples
io.github.mbbhalla:agentio-experiments
```

### Dependency Graph

```
agentio-examples ──────────────→ agentio-core
agentio-experiments ───────────→ agentio-core + agentio-cmm-impl
agentio-cmm-impl ─────────────→ agentio-core
agentio-eventlistener-impl ───→ agentio-core
```

## Key Features

- **Generic & Model Agnostic**: Business domain problem agnostic and not coupled to any specific LLM model
- **Structured I/O**: Uses structured objects for representing data shapes passed to LLMs instead of raw text
- **MCP Protocol Support**: Connects to multiple MCP (Model Context Protocol) servers for tool access
- **Accuracy Improvements**: Built-in mechanisms for improving response accuracy through retries and consensus
- **Context Management**: Chainable Context Memory Managers with turn-aware execution
- **Memory Management**: Load and store context from/to backend storage via ContextProviders and ContextWriters
- **Thinking Mode**: Additional reasoning iterations for more accurate responses
- **Evaluation Framework**: Built-in evaluation tools for testing and refining agents

## Architecture

AgentIO provides three core abstractions:

1. **Agent**: Implementation of `AbstractAgenticFunction` — the reasoning component
2. **Tool**: Implementation of `AbstractMcpTool` — individual capabilities/actions
3. **MCP Server**: Implementation of `AbstractMcpServer` — hosts and manages tools

### Key Innovation: In-Process MCP via PipedStreams

AgentIO enables **MCP protocol communication within a single JVM process** using PipedStreams:

- **Traditional MCP**: Requires separate processes/containers for agents and MCP servers
- **AgentIO's Solution**: Agent and MCP servers run in the same JVM process, communicating via inter-thread PipedStreams while maintaining full MCP protocol compliance

Your tools can still access any backend service (databases, APIs, external systems) while eliminating the operational overhead of managing distributed MCP components.

## Quick Start

### 1. Define Your Agent

```kotlin
class MyAgent(
    agentConfiguration: AgentConfiguration,
) : AbstractAgenticFunction<MyAgent.Input, MyAgent.Output>(agentConfiguration) {

    @Serializable
    data class Input(
        @field:Description("The question to answer")
        val question: String,
    ) : Instructible.WithInstruction {
        override fun instructionId() = "my-agent"
        override fun instruction() = "Answer this question: '$question'"
        override fun systemInstruction(): String? = null
    }

    @Serializable
    data class Output(
        @field:Description("The answer")
        val answer: String,
    )

    override fun getInputKClass() = Input::class
    override fun getOutputKClass() = Output::class
}
```

### 2. Configure and Run

```kotlin
fun main() = runBlocking {
    val agentConfiguration = AgentConfiguration(
        agentId = "my-agent",
        languageModelParameters = LanguageModelParameters(
            llm = LLM.ANTHROPIC_CLAUDE_OPUS_4_6_V1_CROSS_REGION_INFERENCE,
            temperature = Temperature(0.5f),
            topP = TopP(0.9f),
        ),
        bedrockRuntimeClient = BedrockRuntimeClient { region = "us-west-2" },
        toolsProvider = McpClients(set = setOf(/* your MCP clients */)),
    )

    val agent = MyAgent(agentConfiguration)

    val result = agent.invoke(MyAgent.Input(question = "What is AgentIO?"))

    result.onSuccess { agentOutput ->
        println("Answer: ${agentOutput.output.answer}")
    }.onFailure { error ->
        println("Error: ${error.message}")
    }
}
```

### 3. Add Context Memory Management

```kotlin
val agentConfiguration = AgentConfiguration(
    // ...
    contextMemoryManagers = ContextMemoryManagers(
        value = listOf(
            CompactingContextMemoryManager(compactionConfig),  // summarize old turns
            AdaptiveContextMemoryManager(),                     // reshuffle for attention
        ),
    ),
)
```

## Advanced Features

### Consensus Mechanisms

```kotlin
val trialsAgent = AgenticFunctionTrials(
    agenticFunction = agent,
    numberOfTrials = 5,
    agentOutputSelector = MajorityOccurredAgentOutputSelector(/* ... */)
)
```

### Evaluation Framework

```kotlin
val evaluator = AgenticFunctionEvaluator(
    EvaluationInput.withFunction(
        agenticFunction = agent,
        input = myInput,
        numIterations = 100,
        maxParallelism = 5,
        outputMatcher = { output -> output.answer.contains("expected") },
    )
)
val results = evaluator.evaluate()
```

### Context Providers and Writers

```kotlin
val agentConfiguration = AgentConfiguration(
    // ...
    contextProviders = ContextProviders(value = listOf(myDatabaseProvider)),
    contextWriters = ContextWriters(value = setOf(myStorageWriter)),
)
```

## Building and Testing

```bash
# Build all subprojects
./gradlew build

# Build a specific subproject
./gradlew :agentio-core:build

# Run all tests
./gradlew test

# Run specific tests
./gradlew :agentio-core:test
./gradlew :agentio-cmm-impl:test
```

## Running Examples

```bash
./gradlew :agentio-examples:RunHackerNewsAgenticFunction
```

## Running Experiments

```bash
./gradlew :agentio-experiments:RunAdaptiveExperiment
```

## Architecture Benefits

### In-Process MCP Integration

| Traditional MCP | AgentIO |
|----------------|---------|
| Separate processes for agents and MCP servers | Single JVM deployment |
| Network communication overhead | Inter-thread PipedStreams |
| Complex deployment and orchestration | Single artifact |
| Difficult cross-process debugging | Standard JVM debugging |

### Structured Data Flow

- **Type Safety**: Compile-time verification of data shapes via `@Serializable` data classes
- **Better LLM Understanding**: JSON schemas improve reasoning accuracy
- **Clear Contracts**: Well-defined interfaces between components

## Contributing

1. Follow the existing code style and patterns
2. Add tests for new functionality
3. Update documentation for API changes
4. Ensure all builds pass: `./gradlew build`

## License

This project is licensed under the Apache License 2.0. See the [LICENSE](LICENSE) file for details.
