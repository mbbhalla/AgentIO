# agentio-module-solver

SMTLIB2 + Z3 solver facade for AgentIO. Provides a typed `SMTLIBv2Formula`
constructor that validates syntax at construction, and a `Z3SolverFacade`
that enumerates satisfying models with values projected to AgentIO's
`DataValue` type from `agentio-module-data`.

## Core Principle: Correctness at Construction

| Type | Guarantee |
|------|-----------|
| `SMTLIBv2Formula` | Non-blank text and parses through Z3 (`parseSMTLIB2String`) at construction. An invalid formula cannot be instantiated. |
| `Logic` | Enum — `QF_LIA` or `ALL`. No string-typed logic configuration. |
| `SolverModel.variableValues` | Every entry is a typed `DataValue` (`LongValue` / `DoubleValue` / `BooleanValue` / `StringValue`). No `Any` leaks to callers. |

Invalid formulas fail at the boundary (`SMTLIBv2Formula(...)` throws); the
solver's input type guarantees parseability so `Z3SolverFacade.solve` can
focus purely on enumeration.

## Z3 Native Binaries

Z3's native libraries are bundled via the `tools.aqua:z3-turnkey` Maven
artifact. No system install of Z3 is required. The turnkey jar contains
native libraries for Linux, macOS (Intel + Apple Silicon), and Windows.

## Dependencies

- `agentio-core` (`Description`, `JsonSchemaUtil`)
- `agentio-module-data` (`DataValue` for projected solver-model values)
- `tools.aqua:z3-turnkey:4.14.0` — Z3 with bundled native libs
- Kotlinx Serialization
- SLF4J (API only — consumers provide a backend)

## Package Structure

```
io.github.mbbhalla.agentio.module.solver/
├── SMTLIBv2Formula.kt   — value type wrapping a syntax-validated SMTLIB2 formula
└── Z3SolverFacade.kt    — Logic enum, SolverModel data class, Z3SolverFacade.solve()
```

## API

### `SMTLIBv2Formula`

```kotlin
val formula = SMTLIBv2Formula(
    smtlibv2Formula = """
        (declare-const x Int)
        (assert (>= x 0))
    """.trimIndent(),
)
```

Construction throws `IllegalArgumentException` for blank or unparseable
input. `SMTLIBv2Formula.syntaxOk(text)` is a pure boolean check that does
not throw — useful when you want to test before wrapping.

### `Z3SolverFacade.solve`

```kotlin
val models: Set<SolverModel> = Z3SolverFacade.solve(
    argSmtlibv2Formula = formula,
    limit = 5,                      // max distinct models to enumerate
    variableNameFilter = { true },  // restrict which decl names appear in output
    logic = Logic.QF_LIA,           // injected if the formula has no (set-logic ...)
)
models.forEach { model ->
    model.variableValues.forEach { (name, value) ->
        // value: DataValue.LongValue | DoubleValue | BooleanValue | StringValue
    }
}
```

Internally, the solver enumerates models by repeatedly adding a blocking
clause that excludes the previous model, then re-checking. Each model is
projected through `toDataValue` — `IntNum` → `LongValue`, `RatNum` →
`DoubleValue`, `BoolExpr` → `BooleanValue`, anything else falls through to
`StringValue` with a `NON_HANDLED_VALUE: <expr>` prefix.

## Use With An Agent

The COMPASS example in `agentio-examples` wraps this module's
`SMTLIBv2Formula` validation inside an MCP tool
(`smtlibv2_syntax_checker`) so an LLM can self-correct invalid formulas
before returning. See `agentio-examples` README for the full pattern.

## Build

```bash
./gradlew :agentio-module-solver:build
./gradlew :agentio-module-solver:test
```
