# agentio-examples

Example agents built with AgentIO, demonstrating how to use the SDK for real-world tasks.

## Examples

| Example | MCP Type | Gradle Task | Demonstrates |
|---------|----------|-------------|--------------|
| Git Analyzer | In-process | `RunGitAnalyzerAgenticFunction` | `AbstractMcpServer` + `PipedStreamsExchange` + custom tools |
| Text2SQL (Retail) | In-process | `RunText2SqlAgenticFunction-RetailDB` | DuckDB from SQL statements + correctness-at-construction |
| Text2SQL (Employee) | In-process | `RunText2SqlAgenticFunction-EmployeeDB` | DuckDB from Parquet files + correctness-at-construction |
| COMPASS | In-process | `RunCompassAgenticFunction` | Multi-agent, domain-agnostic: NL problem → grounded analysis → SMTLIB2 formula → Z3 solver models (sample run uses supply-chain data) |
| ODCS ETL | In-process | `RunEtlAgenticFunction-AdventureWorks` | Multi-agent ETL with a human in the loop: messy CSVs → load plan → per-table SQL proven against an ODCS-defined DuckDB target → reviewer answers and sign-off → standalone `etl.sql` |

All examples are self-sufficient — no API keys or tokens required.

## Running

```bash
# Git Analyzer — analyze this repo's git history using in-process MCP tools
./gradlew :agentio-examples:RunGitAnalyzerAgenticFunction

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

# ODCS ETL — CSV files → DuckDB database defined by an Open Data Contract (needs Bedrock credentials)
# Each run is one review round: answer build/etl-adventureworks/SYSTEM_INPUT.md, then run again
./gradlew :agentio-examples:RunEtlAgenticFunction-AdventureWorks
./gradlew :agentio-examples:RunEtlAgenticFunction-AdventureWorks -Preview=console   # answer in the terminal instead
./gradlew :agentio-examples:RunEtlAgenticFunction-AdventureWorks \
    -Pcontract=/path/to/contract.odcs.yaml -PsourceDir=/path/to/csvs -PoutputDir=/tmp/etl
```

## Git Analyzer Agent

An agent that analyzes a git repository's development activity using custom in-process MCP tools that wrap git commands.

### Key Patterns

- **In-process MCP via `AbstractMcpServer`** — no external process needed
- **`PipedStreamsExchange`** — agent and MCP server communicate within the same JVM
- **Custom `AbstractMcpTool` implementations** — `git_log`, `git_diff_stat`, `git_file_authors`
- Runs against the AgentIO repository itself

## Text2SQL Agent

An agent that converts natural language questions into valid DuckDB SQL. Two database environments demonstrate different data-loading strategies:

- **RetailDB** — schema and seed data defined as SQL statements in Kotlin (`DuckDbDatabaseEnvironment.fromStatements`)
- **EmployeeDB** — loaded from Parquet files on the classpath (`DuckDbDatabaseEnvironment.fromParquet`)

### Key Patterns

- **Validated output** — `validateOutput` checks the final SQL with `env.selectStatement` (DuckDB `EXPLAIN` on the agent's own environment); an invocation that returns invalid SQL fails, so it never wins the majority vote
- **Agent reinforcement loop** — `ExecuteSqlTool` validates and returns errors to the agent for self-correction
- **Two data-loading strategies** — SQL statements (RetailDB) vs Parquet files (EmployeeDB)
- **Domain-agnostic models** — `DataValue`, `Dataset`, `ExplainResult`, `ColumnType` in `model/` package are reusable across domains

### Architecture

```
┌─────────────────────────────────────────────┐
│         Text2SqlAgenticFunction             │
│  Input: natural language query              │
│  Output: SQL valid on env (validateOutput)  │
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
  `ConstraintGeneratorMcpServer` are given the same `DuckDbDatabaseEnvironment`
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

## ODCS ETL

Loads CSV exports from other systems into a DuckDB database whose schema is an
[Open Data Contract Standard](https://bitol-io.github.io/open-data-contract-standard/) document. The target
exists before any data does; agents write the code that fills it, DuckDB checks every row against the contract, and a
person decides what neither can.

### Target

The contract is the ODCS project's AdventureWorks example (68 tables). The original,
`agentio-module-data/src/test/resources/odcs/odcs_example.postgres.yaml`, describes a PostgreSQL database: its
`physicalType`s are PostgreSQL types (`serial`, `int2`, `varchar[50]`) and AdventureWorks domains (`Name`, `Flag`).
`odcs_example.generic.yaml` next to it is its engine-neutral conversion, with the logical types those imply, and is
what the example loads. `OdcsExampleConversionTest` checks that the conversion changed only types, composite key
positions and `apiVersion`.

```kotlin
val model = DomainModel.fromOdcs(listOf(Path.of("odcs_example.generic.yaml")))
val workspace = EtlWorkspace(model, SourceFiles.of(sourceDir))   // empty DuckDB with every key and CHECK
```

### Sources

`src/main/resources/etl/adventureworks/source/` holds five HR exports. None maps one-to-one to a target table:

| File | What makes it hard |
|------|--------------------|
| `cost_centers.csv` | Two report lines before the header, `;`-delimited, `dd.mm.yyyy` dates; departments and their groups mixed in one hierarchy |
| `shift_patterns.csv` | Shifts as `7:00 AM - 3:00 PM` ranges, no ids; the target wants `TIME` columns |
| `hris_roster.csv` | One wide row per employee that feeds six tables: `Last, First Middle, Suffix` names, phones and email packed in one column, words for codes (`Female`, `Exempt`), two hire-date formats, national IDs with leading zeros, a manager column for the org hierarchy |
| `org_assignment_events.csv` | Unsorted hire / transfer / shift-change / termination events keyed by login in mixed case, with department aliases (`R&D`); they become dated intervals. Two events belong to a login that is not in the roster |
| `payroll_register.csv` | One row per pay period keyed by SSN, annual or hourly amounts as `"$90,000.00"`, one line repeated; 61 periods become 20 rate changes |

### Pipeline

```
┌──────────────────────────────────────────────────────────────────────┐
│ EtlPlannerAgenticFunction                                            │
│   Tools:  list_target_tables, describe_target_table, profile_source, │
│           run_query                                                  │
│   Output: LoadPlan — target tables in load order, each with its      │
│           sources and mapping, plus references the descriptions state│
│   Check:  LoadPlanCheck — names exist, every source used, referenced │
│           tables loaded first                                        │
└──────────────────────────────────┬───────────────────────────────────┘
                                   │ one step at a time
                                   ▼
┌──────────────────────────────────────────────────────────────────────┐
│ EtlSqlWriterAgenticFunction  (per table)                             │
│   Tools:  describe_target_table, profile_source, run_query, try_load │
│   Output: one SELECT whose columns are target columns                │
│   Check:  try_load again after the conversation: rows inserted under │
│           every constraint, no orphaned references                   │
│   Then:   loaded for real, so later steps can join it                │
└──────────────────────────────────┬───────────────────────────────────┘
                                   ▼
               etl.sql — CREATE TABLEs + INSERT ... BY NAME SELECTs,
               replayed into a fresh DuckDB to prove it runs alone
                                   │ + the agents' questions
                                   ▼
┌──────────────────────────────────────────────────────────────────────┐
│ HumanInTheLoop (agentio-module-patterns)                             │
│   Open questions → reviewer answers → the agents rerun with them     │
│   None open      → reviewer approves, or sends feedback for a round  │
│   Gate:   SYSTEM_INPUT.md (default) or the console                   │
└──────────────────────────────────────────────────────────────────────┘
```

A rejected plan or SELECT is retried with its error. A table that still fails stops the round, since later tables
may depend on it, and becomes a question that shows every attempt's error.

### Human in the loop

Passing every constraint does not make a mapping correct. `maritalstatus` is a plain `VARCHAR` described as
"M = Married, S = Single": DuckDB accepts `S`, `M` or NULL for a divorced employee alike, and only the target's owner
knows which is meant. So the agents raise a question whenever a choice changes the loaded data and neither the
sources nor the descriptions settle it, and whenever they cannot make a table load. Each question has options, the
evidence (source values with counts, the column's description, any DuckDB error) and the option the agents assumed
meanwhile, so one round still produces a complete, constraint-clean mapping to review.

A run is one round. It writes the round's `etl.sql` and `load-plan.json`, then `SYSTEM_INPUT.md`:

```markdown
### [Q3] employee: How should employee.maritalstatus store the roster values Widowed and Divorced, given the
description defines only M = Married and S = Single?

> Roster Marital values: Married, Single, Widowed (1 row, E-1010) and Divorced (1 row, E-1004).

- [ ] 1. Leave them NULL _(the agent's assumption)_
- [ ] 2. Map them to 'S' (not married)
- [ ] 3. Store new codes 'W' and 'D'

Answer:
```

Tick a box or write an answer, then run the task again: the agents rerun from an empty target with every answer as a
binding decision. When no question is open, the file asks for sign-off of the whole mapping, with the decisions and
the proposal below it. Approving writes the final `etl.sql` from the approved SQL, without running the agents again,
replays it, and turns `SYSTEM_INPUT.md` into the record of every decision; the script's header lists them too.
`SYSTEM_INPUT.state.json` beside it carries the round between runs. To start over, delete both files.

### Key Patterns

- **DuckDB is the validator.** `try_load` inserts the agent's SELECT as `INSERT INTO <table> BY NAME`, so DuckDB
  enforces types, primary keys, `NOT NULL` and the contract's `CHECK`s, then reports NULL counts, orphaned references
  and sample rows, and deletes the rows again. The agent fixes its SQL from DuckDB's own error messages.
- **The output is code, not data.** The run writes `load-plan.json` and `etl.sql`; the script loads an empty
  database with `duckdb target.duckdb < etl.sql`, with no AgentIO or LLM involved.
- **Agent SQL reads only the sources.** CSV content is untrusted input, and DuckDB can read any file the process
  can. `SourceAccessGuard` walks DuckDB's own parse tree (`json_serialize_sql`) and allows `read_csv` only on the
  run's source files by absolute literal path, plus target tables; other table functions, file scans, globs and
  computed paths are rejected before the SQL is bound.
- **Constraint-clean is not correct.** Choices no constraint can settle go to a person as multiple-choice questions;
  approval is only possible once none is open.
- **Deterministic parts are tested without an LLM.** `EtlPipelineTest` and `EtlReviewTest` drive the pipeline and
  the review loop with a hand-written reference load (`src/test/resources/etl/adventureworks/reference-etl.sql`): row
  counts, references, retries, the script replay, questions, a table blocked until it is answered, resuming from an
  answered `SYSTEM_INPUT.md`, and sign-off.

### Sample Run

```
$ ./gradlew :agentio-examples:RunEtlAgenticFunction-AdventureWorks

Target: 68 tables from odcs_example.generic.yaml; sources: [cost_centers.csv, hris_roster.csv, ...]
=== Planning, attempt 1/2
Plan: businessentity -> person -> employee -> phonenumbertype -> personphone -> emailaddress -> department -> shift -> employeedepartmenthistory -> employeepayhistory
=== Loading businessentity from [hris_roster.csv]
...
[HITL] Round 1: proposal with 8 open question(s)
=== Result
Round 1 awaits the reviewer: 8 open question(s).
  [Q1] plan: How should businessentityid (...) be derived from hris_roster "Emp ID"?
  [Q3] plan: How should hris_roster Marital values Divorced and Widowed load into employee.maritalstatus?
  [Q4] plan: How should hris_roster Status 'On Leave' load into employee.currentflag?
  [Q5] plan: How should ANNUAL salary amounts in payroll_register be converted to employeepayhistory.rate?
  [Q7] plan: Should org_assignment_events rows for logins missing from hris_roster be loaded into employeedepartmenthistory?
  ...
Answer .../build/etl-adventureworks/SYSTEM_INPUT.md and run the task again.
```

A round takes about six minutes on Claude Opus 5.5. In the recorded first round every table loaded on the first
attempt, with no orphaned references, and the agents asked eight questions: the two key schemes, Divorced and Widowed,
On Leave, the annual-to-hourly divisor, how a transfer ends the previous interval, the orphan login's events, and the
hierarchy path.

## Package Structure

```
io.github.mbbhalla.agentio.examples/
├── gitanalyzer/
│   ├── Runner.kt
│   ├── function/GitAnalyzerAgenticFunction.kt
│   └── server/GitAnalyzerMcpServer.kt, GitTools.kt
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
└── etl/
    ├── Runner.kt                      — contract → workspace → pipeline → load-plan.json + etl.sql + replay
    ├── EtlAgenticFunctions.kt         — EtlPlannerAgenticFunction, EtlSqlWriterAgenticFunction
    ├── EtlPipeline.kt                 — plan, then write, prove and load one table at a time, with retries
    ├── EtlWorkspace.kt                — target DuckDB + sources: profile, query, try_load, load, orphan checks
    ├── EtlTools.kt                    — MCP tools over the workspace, EtlMcpServer
    ├── SourceAccessGuard.kt           — limits agent SQL to the source CSVs and target tables
    ├── LoadPlanCheck.kt               — validates a LoadPlan before any SQL is written
    ├── EtlReview.kt                   — EtlProposer (one review round), questions, review Markdown, artifacts
    ├── EtlScript.kt                   — renders and replays etl.sql
    ├── SourceFiles.kt, EtlModels.kt
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
