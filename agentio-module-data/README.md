# agentio-module-data

Data environment module for AgentIO. Provides typed data models, SQL statement validation, and database environment abstraction backed by DuckDB.

## Core Principle: Correctness at Construction

Every data type enforces validity in its `init` block. If an object exists, it is correct. Invalid state cannot propagate.

| Type | Guarantee |
|------|-----------|
| `TableName` | Matches `[a-z][a-z0-9_]*` |
| `ColumnName` | Matches `[a-z][a-z0-9_]*` |
| `ColumnType` | Enum — only valid DB types |
| `S3ObjectKey` | Non-blank, no leading `/` |
| `S3Uri` | Valid `s3://bucket/key` format |
| `MVELExpression` | Compiles via `MVEL.compileExpression()` — syntactically valid |
| `SelectSqlStatement` | EXPLAIN passes + plan is SELECT |
| `InsertSqlStatement` | EXPLAIN passes + plan is INSERT |
| `UpdateSqlStatement` | EXPLAIN passes + plan is UPDATE |
| `DeleteSqlStatement` | EXPLAIN passes + plan is DELETE |
| `DuckDbDatabaseEnvironment` | DDL/DML executed successfully at construction |

## Package Structure

```
io.github.mbbhalla.agentio.data/
├── model/
│   ├── DataModels.kt       — TableName, ColumnName, ColumnType, TableInfo, ColumnInfo, ForeignKeyRef, Dataset, DataValue
│   ├── S3Models.kt         — S3ObjectKey, S3Uri, Version, VersionSet, DatabaseEnvironmentSnapshot
│   ├── ExplainResult.kt    — sealed: Success | Failure
│   └── MVELExpression.kt   — validated MVEL expression (compiled at construction)
└── env/
    ├── DatabaseEnvironment.kt        — abstract class with snapshot, companion holds active env
    ├── DuckDbDatabaseEnvironment.kt  — fromStatements() + fromParquet() + fromS3(timestamp)
    └── SqlStatements.kt              — SelectSqlStatement, InsertSqlStatement, UpdateSqlStatement, DeleteSqlStatement
```

## Usage

### Create from DDL + seed data

```kotlin
val env = DuckDbDatabaseEnvironment.fromStatements(
    ddl = listOf("CREATE TABLE orders (id VARCHAR PRIMARY KEY, amount DOUBLE)"),
    dml = listOf("INSERT INTO orders VALUES ('O-1', 42.0)"),
    tableMetadata = setOf(
        TableInfo(
            name = TableName("orders"),
            description = "Customer orders",
            columns = listOf(
                ColumnInfo(ColumnName("id"), ColumnType.VARCHAR, false, true, null, "Order ID"),
                ColumnInfo(ColumnName("amount"), ColumnType.DOUBLE, false, false, null, "Amount"),
            ),
        ),
    ),
)
```

### Create from Parquet files (local)

```kotlin
val env = DuckDbDatabaseEnvironment.fromParquet(Path("/data/2025-05-26/"))
// Tables inferred from filenames: orders.parquet → table "orders"
// Schema inferred from Parquet metadata
```

### Create from Parquet files (S3 — point-in-time snapshot)

```kotlin
val s3Client = S3Client { region = "us-west-2" }
val env = DuckDbDatabaseEnvironment.fromS3(
    timestamp = Instant.parse("2026-05-28T10:00:00Z"),
    s3Uri = S3Uri("s3://my-data-lake/retail/daily/2025-05-26"),
    s3Client = s3Client,
)
// Resolves each .parquet file to its version at-or-before timestamp
// Downloads with explicit versionId for reproducibility
// env.snapshot contains fully-resolved DatabaseEnvironmentSnapshot (timestamp + VersionSet)
```

### Typed SQL — cannot construct invalid SQL

```kotlin
// Valid — object constructed
val stmt = SelectSqlStatement("SELECT * FROM orders WHERE amount > 10")
val dataset = env.executeQuery(stmt)

// Invalid — throws IllegalArgumentException at construction
SelectSqlStatement("SELECT * FROM nonexistent_table")  // fails: EXPLAIN rejects
InsertSqlStatement("not sql at all")                   // fails: EXPLAIN rejects
SelectSqlStatement("INSERT INTO orders VALUES (1)")    // fails: plan type is INSERT, not SELECT
```

### Access query results with typed columns

```kotlin
val dataset = env.executeQuery(SelectSqlStatement("SELECT id, amount FROM orders"))
val firstRecord = dataset.records[0]
val id = firstRecord[ColumnName("id")]         // DataValue.StringValue("O-1")
val amount = firstRecord[ColumnName("amount")] // DataValue.DoubleValue(42.0)
```

### Evaluate MVEL expressions over a Dataset

SQL produces a `Dataset`, MVEL evaluates over it to a boolean. The `Dataset` is bound as `this` in the MVEL context.

```kotlin
val dataset = env.executeQuery(
    SelectSqlStatement("SELECT * FROM orders WHERE amount < 10")
)

// Default: non-empty result = true
val hasLowOrders = dataset.evaluate(MVELExpression("!this.records.empty"))

// Size-based
val tooMany = dataset.evaluate(MVELExpression("this.records.size() > 100"))

// Scalar access
val belowThreshold = dataset.evaluate(
    MVELExpression("""((io.github.mbbhalla.agentio.data.model.DataValue${'$'}LongValue) this.records[0].values["amount"]).value < 500""")
)

// Use the default constant (equivalent to "!this.records.empty")
val breach = dataset.evaluate(MVELExpression(MVELExpression.DEFAULT))
```

## Design Decisions

- **DuckDB is the engine.** Customer data arrives as Parquet (local or S3) or DDL+INSERT. DuckDB validates and executes. No JDBC driver matrix.
- **`DatabaseEnvironment.current`** — singleton reference used by typed SQL statements for validation. Set by factory methods via `activate()`.
- **`DatabaseEnvironment.snapshot`** — optional provenance metadata. Non-null for S3-loaded environments (fully constructed with timestamp + resolved VersionSet). Null for filesystem/DDL-based environments.
- **`fromS3` is `suspend`** — uses AWS S3 Kotlin SDK (suspend functions). Blocking I/O (`Files.createTempDirectory`, `Files.write`) wrapped in `withContext(Dispatchers.IO)`. Consistent with `agentio-core` concurrency pattern.
- **Point-in-time S3 resolution** — `fromS3` uses `listObjectVersions` to resolve each parquet file to its version at-or-before the given timestamp. Downloads with explicit `versionId` for reproducibility. Pre-versioning objects (versionId="null") are included best-effort with `versionId = null` in the snapshot.
- **`fromStatements` and `fromParquet` are non-suspend** — blocking JDBC. Callers wrap in `runBlocking` or `withContext(Dispatchers.IO)` if needed.
- **Statement type detection** — uses DuckDB EXPLAIN plan's root operator node. Tested against DuckDB 1.5.2.0; version upgrades that change plan format will fail tests immediately.
- **No additional SQL parser dependency** — DuckDB itself is both parser and validator. EXPLAIN proves correctness.
- **S3 pagination** — uses `generateList` (from `agentio-core`) with suspend lambda. Handles any number of pages transparently.

## Dependencies

- `agentio-core` (for `generateList` utility)
- DuckDB JDBC 1.5.2.0
- MVEL2 2.5.2.Final
- AWS SDK Kotlin S3 1.6.68
- Kotlinx Serialization
- Kotlinx Coroutines

## Build & Test

```bash
./gradlew :agentio-module-data:build
./gradlew :agentio-module-data:test
```
