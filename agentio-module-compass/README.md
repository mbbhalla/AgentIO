# agentio-module-compass

**C**onstraint **O**ptimization via **M**ulti-agent **P**roblem **A**nalysis and **S**olver **S**ynthesis.

A reusable, **domain-agnostic** library for translating an English problem
statement, posed against any tabular dataset, into a Z3-solvable SMTLIB2
formula. Concrete decision-variable kinds and the dataset itself are
plugged in by the consuming application; this module owns the agentic
functions, MCP servers, and tools that drive the pipeline.

## Pipeline

```
English problem statement
        │
        ▼
┌───────────────────────────────────┐
│  AnalyzerAgenticFunction           │
│  (Bedrock + MCP tools)             │
│  Produces a grounded AnalysisResult│
│  — every result item has SQL       │
│  provenance against real data.     │
└───────────────┬───────────────────┘
                │ AnalysisResult
                ▼
┌───────────────────────────────────┐
│  ConstraintGeneratorAgenticFunction│
│  (Bedrock + MCP tools)             │
│  Emits SMTLIBv2Formula + Markdown  │
│  explanation. Variable kinds are   │
│  injected by the caller.           │
└───────────────┬───────────────────┘
                │ SMTLIBv2Formula
                ▼
        Z3SolverFacade.solve
        (agentio-module-solver)
```

## Core Principle: Correctness at Construction

| Type | Guarantee |
|------|-----------|
| `AnalysisResult.ResultItem` | `@Serializable` data class — invalid JSON cannot deserialize. SQL provenance is enforced by `AnalysisResultValidatorTool` re-executing every `sql` and matching its scalar against `value`. |
| `SMTLIB2Variable.nameFormat(env)` | Throws if any of the variable's `keyColumns` is missing from `associatedDataTable` — name-format derivation is total once construction succeeds. |
| `ConstraintGeneratorAgenticFunction.create` | Requires a non-empty `Set<SMTLIB2Variable>` — the agent never receives a prompt with zero variable kinds. |
| `SMTLIBv2Formula` (from `agentio-module-solver`) | Parses through Z3 at construction. The constraint generator's `Output.smtlibv2Formula` cannot be deserialized if the formula doesn't parse. |

## Domain Extension

`SMTLIB2Variable` is an `abstract class` (not sealed — sealed hierarchies
cannot be extended across modules). Consumers define concrete variable
kinds as `data object` singletons in their own module, then pass them in
at create time:

```kotlin
data object MyDecisionVariable : SMTLIB2Variable() {
    override val variableNamePrefix = "V_DECISION"
    override val type = VariableType.LONG
    override val description = "Quantity to allocate to bucket X."
    override val associatedDataTable = TableName("allocations")
    override val keyColumns = listOf(ColumnName("bucket_id"), ColumnName("period"))
}

val variables: Set<SMTLIB2Variable> = setOf(MyDecisionVariable, /* … */)

val gen = ConstraintGeneratorAgenticFunction.create(
    env = myDatabaseEnvironment,
    variables = variables,
    problemDomain = "Allocation Optimization",
)
```

The variable's `nameFormat(env)` produces a templated name like
`V_DECISION:::<bucket_id>:::<period>` that the LLM fills in with concrete
key values when emitting the formula (e.g.
`V_DECISION:::B17:::2026-Q3`).

`agentio-examples/compass` shows a complete working extension —
`SupplyChainVariables.kt` defines five PO-related variable kinds against a
supply-chain dataset.

## API

### `AnalyzerAgenticFunction`

```kotlin
val analyzer = AnalyzerAgenticFunction.create(
    env = databaseEnvironment,
    problemDomain = "Allocation Optimization",  // displayed in prompt
)

val result: Try<AgentOutput<AnalyzerAgenticFunction.Output>> =
    analyzer.invoke(
        AnalyzerAgenticFunction.Input(
            objective = "Find buckets where allocation exceeds capacity in Q3 2026",
            datasetName = "my_dataset",
        ),
    )

val analysisResult: AnalysisResult = result.get().output.analysisResult
```

The analyzer is given four MCP tools by `AnalyzerMcpServer`:
`list_tables`, `get_tables`, `execute_sql`,
`analysis_result_validator`. The validator re-executes every reported
SQL and asserts the returned scalar matches the reported value (Long↔Double
numerically equivalent). The agent self-corrects against validator
failures.

### `ConstraintGeneratorAgenticFunction`

```kotlin
val generator = ConstraintGeneratorAgenticFunction.create(
    env = databaseEnvironment,
    variables = ALL_MY_VARIABLES,
    problemDomain = "Allocation Optimization (SMTLIB2 / Z3)",
)

val result: Try<AgentOutput<ConstraintGeneratorAgenticFunction.Output>> =
    generator.invoke(
        ConstraintGeneratorAgenticFunction.Input(
            analysisResult = analysisResult,
            datasetName = "my_dataset",
        ),
    )

val formula: SMTLIBv2Formula = result.get().output.smtlibv2Formula
val explanationMd: String = result.get().output.explanation
```

The generator's MCP tools are `list_tables`, `get_tables`, `execute_sql`,
`smtlibv2_syntax_checker`. The syntax checker invokes
`Z3SolverFacade.solve(... , limit = 1)` so it catches both syntax errors
and semantic issues (e.g. unknown functions that parse but fail at solve
time) before the agent emits the final answer.

The injected variable kinds appear in the agent's *system prompt* as JSON
descriptors:

```
{
  "type": "LONG",
  "description": "Quantity to allocate to bucket X.",
  "nameFormat": "V_DECISION:::<bucket_id>:::<period>"
}
```

The agent fills the `<…>` placeholders with values surfaced in the
analysis result.

## Package Structure

```
io.github.mbbhalla.agentio.module.compass/
├── model/
│   ├── AnalysisResult.kt        — grounded analysis (resultItems with SQL provenance)
│   └── SMTLIB2Variable.kt       — abstract base + VariableType enum
├── function/
│   ├── AnalyzerAgenticFunction.kt
│   └── ConstraintGeneratorAgenticFunction.kt
├── tool/
│   ├── AnalysisResultValidatorTool.kt    — re-runs every reported SQL
│   └── SmtLibV2SyntaxCheckerTool.kt      — Z3 parse + sanity solve
└── server/
    └── CompassMcpServers.kt     — AnalyzerMcpServer, ConstraintGeneratorMcpServer
```

DB tools (`list_tables`, `get_tables`, `execute_sql`) live in
`agentio-module-data` and are reused as-is — they have no domain knowledge.

## Dependencies

- `agentio-core` (re-exports MCP SDK + Vavr `Try`)
- `agentio-module-data` (`DatabaseEnvironment`, `DataValue`, db tools)
- `agentio-module-solver` (`SMTLIBv2Formula`, `Z3SolverFacade`)
- AWS SDK for Kotlin (Bedrock Runtime)
- Kotlinx Serialization
- SLF4J (API only)

## Build

```bash
./gradlew :agentio-module-compass:build
./gradlew :agentio-module-compass:test
```

## See Also

- `agentio-examples/compass` — concrete supply-chain consumer
  (`SupplyChainDatabase`, `SupplyChainVariables`, `Runner` driving all
  three stages end-to-end)
- `agentio-module-solver/README.md` — Z3 facade details
