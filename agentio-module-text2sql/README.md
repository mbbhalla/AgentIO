# agentio-module-text2sql

Natural-language-to-SQL agentic function and its in-process MCP tool server.

```
io.github.mbbhalla:agentio-module-text2sql
```

Row-level SQL authorization (`SqlAuthorizer`) is in
[`agentio-module-data`](../agentio-module-data/README.md#sql-authorization).

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
