# agentio-module-text2sql

Natural-language-to-SQL agentic function, its in-process MCP tool server, and **SQL authorization** —
row-level security applied by rewriting SQL rather than by trusting the query author.

```
io.github.mbbhalla:agentio-module-text2sql
```

## SQL authorization

`SqlAuthorizer` takes a `SelectSqlStatement` written by anyone (an LLM, a user, another service) and an
`AuthorizationContext` describing which rows the caller may see in each table. It returns a query in
which every base-table reference has been replaced by a common table expression that applies the
caller's filter.

```kotlin
val context = AuthorizationContext(
    policies = mapOf(
        TableName("inventory") to
            (ColumnName("site") isIn listOf(DataValue.StringValue("TX"), DataValue.StringValue("CA"))),
        TableName("site") to Criteria.AllRows,
    ),
    unlistedTablePolicy = UnlistedTablePolicy.DENY,      // fail closed (default)
)

val sql: SelectSqlStatement = env.selectStatement("SELECT * FROM Inventory").getOrThrow()
val result: Result<AuthorizedSql> = SqlAuthorizer.authorize(sql, context)
result.getOrThrow().sql.value
// WITH authz_inventory AS (SELECT * FROM inventory WHERE (site IN ('TX', 'CA')))
// SELECT * FROM authz_inventory AS Inventory
```

DuckDB does the parsing: the query is parsed with `json_serialize_sql`, the parse tree is rewritten, and
`json_deserialize_sql` renders it back to SQL, all on the environment that validated the input
statement. The tables the rewrite filters are therefore exactly the tables DuckDB reads. The output is
DuckDB's normalized SQL; comments and syntactic sugar such as `FROM x SELECT ...` are not preserved,
but the meaning is.

### Guarantees

- **Output is a valid `SELECT` on the same environment.** `AuthorizedSql.sql` is a `SelectSqlStatement`
  created by the environment that validated the input, so DuckDB has bound it (`EXPLAIN`) there, it
  can be passed straight to that environment's `executeQuery`, and any other environment rejects it.
- **Output honours the policy.** Filters are applied at the base-table boundary, before any join,
  aggregation, set operation or window function, so no later clause can widen what a table yields.
- **Fail closed.** A table with no policy fails the request unless the context explicitly opts into
  `UnlistedTablePolicy.ALLOW_ALL_ROWS`. Sources that read data without naming a table are always
  rejected: table functions (`query_table`, `query`, `read_csv`, ...), `SUMMARIZE` / `DESCRIBE`, and
  file paths (`FROM 'data.csv'`). Data-modifying CTEs never reach the authorizer, because
  `selectStatement` rejects them.
- **Scope-correct CTE resolution.** CTE names are resolved per scope the way DuckDB resolves them
  (later siblings, nested bodies, and a recursive CTE's own name only in its recursive term), so an
  inner `WITH inventory AS (...)` cannot shadow the `inventory` base table to escape its policy.
- **Injection-safe predicates.** Criteria are built from `ColumnName` and `DataValue` — never raw SQL
  — and rendered with quoted identifiers and escaped literals.
- **Only the authorizer creates `AuthorizedSql`**; its constructor is internal.

Tables are identified by `TableName`, the same type `DatabaseEnvironment.listTables()` returns. A
reference such as `"Main"."Inventory"` or `memory.main.inventory` resolves to `TableName("inventory")`.
Aliases are preserved; when a table had none, its written name is installed as the alias so that
qualified column references (`inventory.site`) keep resolving.

### Criteria

`Criteria` is a sealed, composable predicate tree:

| Node | SQL |
|------|-----|
| `AllRows` / `NoRows` | `(1 = 1)` / `(1 = 0)` |
| `Compare(column, op, value)` | `("col" = 'v')`, `<>`, `<`, `<=`, `>`, `>=`, `LIKE` |
| `In(column, values, negated)` | `("col" [NOT] IN (...))` |
| `IsNull(column, negated)` | `("col" IS [NOT] NULL)` |
| `And` / `Or` / `Not` | combinators |

A small infix DSL is provided: `ColumnName("site") eq StringValue("TX")`, `isIn`, `notIn`, `gte`,
`like`, `isNull()`, etc. Values reuse `DataValue` from `agentio-module-data`.

## Text2SQL agentic function

`Text2SqlAgenticFunction.create(agentId, env)` builds an agent that converts natural language into a
DuckDB `SELECT` using the `list_tables` / `get_tables` / `execute_sql` tools exposed by
`Text2SqlMcpServer` over in-process piped streams. The tools and the final answer all use `env`: a
successful invocation always returns SQL that is a valid `SELECT` on `env` (checked by
`validateOutput`), and a trial that returns anything else fails, so it is excluded from an
`AgenticFunctionEvaluator` vote. See the top-level README for the agentic function model.

## Testing

```bash
./gradlew :agentio-module-text2sql:test
```

Authorization tests run every case on an in-memory DuckDB and compare the authorized rows with the
same query filtered by hand: nested `WITH`, derived tables, joins, unions, recursive CTEs,
quoted/mixed-case identifiers, name collisions, two 200–300-line production queries, and the rejected
sources and error paths above.
