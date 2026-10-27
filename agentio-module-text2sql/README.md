# agentio-module-text2sql

Natural-language-to-SQL agentic function, its in-process MCP tool server, and **SQL authorization** —
row-level security applied by rewriting SQL rather than by trusting the query author.

```
io.github.mbbhalla:agentio-module-text2sql
```

## SQL authorization

`SqlAuthorizer` takes a `SELECT` written by anyone (an LLM, a user, another service) and an
`AuthorizationContext` describing which rows the caller may see in each table. It returns a
semantically equivalent query in which every base-table reference has been replaced by a common
table expression that applies the caller's filter.

```kotlin
val authorizer = SqlAuthorizer()

val context = AuthorizationContext(
    dialect = RedshiftSqlDialect,
    policies = mapOf(
        RedshiftSqlDialect.tableReference(name = "inventory", schema = "supply") to
            (ColumnName("site") isIn listOf(DataValue.StringValue("TX"), DataValue.StringValue("CA"))),
        RedshiftSqlDialect.tableReference(name = "site", schema = "supply") to Criteria.AllRows,
    ),
    defaultSchema = "supply",                              // resolves unqualified names like Redshift search_path
    unlistedTablePolicy = UnlistedTablePolicy.DENY,      // fail closed (default)
)

val result: Result<AuthorizedSql> = authorizer.authorize("SELECT * FROM Inventory", context)
result.getOrThrow().sql
// WITH authz_supply_inventory AS (SELECT * FROM Inventory WHERE ("site" IN ('TX', 'CA')))
// SELECT * FROM authz_supply_inventory AS Inventory
```

### Guarantees

Given a syntactically valid input:

- **Output is syntactically valid.** The rewrite is performed on the AST (JSqlParser 5.x) and the
  rendered SQL is re-parsed before being returned. An optional `SqlValidator` (e.g. JDBC `EXPLAIN`)
  adds a database-exact check.
- **Output honours the policy.** Filters are applied at the base-table boundary, before any join,
  aggregation, set operation or window function, so no later clause can widen the result. For inner
  semantics the authorized rows are a subset of the original rows.
- **Fail closed.** A base table with no policy fails the request unless the context explicitly opts
  into `UnlistedTablePolicy.ALLOW_ALL_ROWS`. Only single `SELECT` statements are accepted;
  `SELECT INTO`, data-modifying CTEs and multi-statement input are rejected.
- **Scope-correct CTE resolution.** CTE names are resolved per scope following SQL visibility rules
  (later siblings, nested bodies, `RECURSIVE` self-reference), so an inner `WITH site AS (...)` cannot
  shadow the `site` base table in an outer scope to escape its policy.
- **Injection-safe predicates.** Criteria are built from `ColumnName` and `DataValue` — never raw SQL
  — and rendered with dialect-specific identifier quoting and literal escaping.
- **Correct by construction.** `AuthorizedSql` can only be created by the authorizer and re-parses
  its SQL on construction; `TableReference` can only be created through `SqlDialect.tableReference`,
  so it is always canonical; `AuthorizationContext` carries its dialect and rejects keys that are
  not canonical for it. The illegal states — unparsed "authorized" SQL, a mis-cased policy key that
  silently never matches, an authorizer paired with a context for another database — are not
  representable.

Aliases are preserved; when a table had none, its original name is installed as the alias so that
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

### Policy lookup

`TableReference` holds canonicalised `database?`, `schema?`, `name` and is obtained only via
`dialect.tableReference(name, schema, database)`. Lookup is most-specific-first:
`db.schema.name` → `schema.name` → `name`. A schema-less policy therefore applies to a table of that
name in any schema.

### Dialects

Parsing is shared; a `SqlDialect` supplies only identifier canonicalisation, identifier quoting and
literal rendering. Adding a database is a small object:

| Dialect | Identifier folding | String literal escaping |
|---------|--------------------|-------------------------|
| `RedshiftSqlDialect` | lower-case, quoted or not | `'` → `''`, `\` → `\\` |
| `DuckDbSqlDialect` | lower-case (case-insensitive match) | `'` → `''` |

JSqlParser accepts a superset of each dialect, so a database-specific construct it does not know
would be rejected at parse time rather than passed through. For exact validation, supply a
`SqlValidator` that runs `EXPLAIN` on the target database:

```kotlin
val authorizer = SqlAuthorizer { sql ->
    runCatching { connection.createStatement().use { it.execute("EXPLAIN $sql") } }
}
```

## Text2SQL agentic function

`Text2SqlAgenticFunction.create(agentId, env)` builds an agent that converts natural language into a
DuckDB `SELECT` using the `list_tables` / `get_tables` / `execute_sql` tools exposed by
`Text2SqlMcpServer` over in-process piped streams. See the top-level README for the agentic function
model.

## Testing

```bash
./gradlew :agentio-module-text2sql:test
```

Authorization tests cover structural rewriting (nested `WITH`, derived tables, joins, unions,
recursive CTEs, quoted/unquoted identifiers, name collisions, a 300-line production Redshift query),
error paths, and **semantic** checks that execute original and authorized SQL against in-memory
DuckDB and compare row sets.
