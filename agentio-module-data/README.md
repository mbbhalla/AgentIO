# agentio-module-data

Data environment module for AgentIO. Provides typed data models, SQL statement validation, and database environment abstraction backed by DuckDB.

## Core Principle: Correctness at Construction

Every data type enforces validity in its `init` block. If an object exists, it is correct. Invalid state cannot propagate.

| Type | Guarantee |
|------|-----------|
| `TableName` | Matches `[a-z][a-z0-9_]*` |
| `ColumnName` | Matches `[a-z][a-z0-9_]*` |
| `ColumnType` | Enum — only valid DB types |
| `SchemaMetadata` | Deserialized from valid YAML matching expected structure |
| `ConstraintStrategies` | Non-nullable with safe defaults (all THROW) |
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
│   ├── SchemaMetadata.kt   — SchemaMetadata (parsed from schemaMetadata.yml, enriches descriptions + constraints)
│   ├── ConstraintStrategies.kt — ViolationBehavior enum + ConstraintStrategies (THROW/IGNORE per constraint type)
│   ├── S3Models.kt         — S3ObjectKey, S3Uri, Version, VersionSet, DatabaseEnvironmentSnapshot
│   ├── ExplainResult.kt    — sealed: Success | Failure
│   └── MVELExpression.kt   — validated MVEL expression (compiled at construction)
└── env/
    ├── DatabaseEnvironment.kt        — abstract class with snapshot, companion holds active env
    ├── DuckDbDatabaseEnvironment.kt  — fromStatements() + fromParquet(dir, constraintStrategies) + fromS3(timestamp, s3Uri, s3Client, constraintStrategies)
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
// If schemaMetadata.yml exists in directory, table/column descriptions are applied
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
// Also downloads schemaMetadata.yml if present at the same prefix
// env.snapshot contains fully-resolved DatabaseEnvironmentSnapshot (timestamp + VersionSet)
```

### Schema Metadata (optional)

Place a `schemaMetadata.yml` alongside your parquet files to enrich tables and columns with descriptions and constraints. When present, descriptions are applied as DuckDB `COMMENT ON` statements, constraints are enforced via `ALTER TABLE`, and metadata is populated into `TableInfo`/`ColumnInfo`.

```yaml
# schemaMetadata.yml
tables:
  - name: orders
    description: "All completed customer orders"
    columns:
      - name: order_id
        description: "Unique order identifier"
        primaryKey: true
      - name: customer_id
        description: "FK to customers table"
        notNull: true
        foreignKey: "customer.customer_id"
      - name: email
        description: "Contact email"
        unique: true
      - name: status
        description: "One of: pending, shipped, delivered"
        notNull: true
```

**Supported constraints:**

| Field | Effect |
|-------|--------|
| `primaryKey: true` | `ALTER TABLE ADD PRIMARY KEY` — rejects duplicate values |
| `unique: true` | `CREATE UNIQUE INDEX` — rejects duplicate values |
| `notNull: true` | `ALTER TABLE ALTER COLUMN SET NOT NULL` — rejects null values |
| `foreignKey: "table.column"` | Validates referential integrity via query — rejects orphaned rows |

Constraints are applied in order: NOT NULL → PRIMARY KEY → UNIQUE INDEX. This ordering is required because DuckDB cannot `ALTER TABLE` after an index exists on the table.

Without `schemaMetadata.yml`, tables default to `"Table loaded from <filename>"` and columns have empty descriptions with no constraints.

### Constraint Strategies

`ConstraintStrategies` controls what happens when loaded data violates a declared constraint. It is an optional parameter on `fromParquet` and `fromS3` — non-nullable with defaults that enforce all constraints.

```kotlin
data class ConstraintStrategies(
    val onPrimaryKeyViolation: ViolationBehavior = ViolationBehavior.THROW,
    val onUniqueViolation: ViolationBehavior = ViolationBehavior.THROW,
    val onNotNullViolation: ViolationBehavior = ViolationBehavior.THROW,
    val onForeignKeyViolation: ViolationBehavior = ViolationBehavior.THROW,
)

enum class ViolationBehavior { THROW, IGNORE }
```

**Behavior per mode:**

| Mode | On violation |
|------|-------------|
| `THROW` | Throws `IllegalStateException` — environment creation fails |
| `IGNORE` | Logs warning via SLF4J, skips the constraint, continues loading |

In both modes, `ColumnInfo` metadata (primaryKey, foreignKey, nullable) is still populated from `schemaMetadata.yml` — the strategy only affects enforcement against actual data.

**Usage:**

```kotlin
// Default: all constraints enforced (safe for production pipelines)
val env = DuckDbDatabaseEnvironment.fromParquet(Path("/data/"))

// Relax specific constraints (useful for exploratory/dirty data)
val env = DuckDbDatabaseEnvironment.fromParquet(
    directory = Path("/data/"),
    constraintStrategies = ConstraintStrategies(
        onNotNullViolation = ViolationBehavior.IGNORE,
        onForeignKeyViolation = ViolationBehavior.IGNORE,
    ),
)

// Same parameter available on fromS3
val env = DuckDbDatabaseEnvironment.fromS3(
    timestamp = Instant.parse("2026-05-28T10:00:00Z"),
    s3Uri = S3Uri("s3://bucket/prefix"),
    s3Client = s3Client,
    constraintStrategies = ConstraintStrategies(
        onPrimaryKeyViolation = ViolationBehavior.IGNORE,
    ),
)
```

**When no `schemaMetadata.yml` is present**, no constraints are declared and `ConstraintStrategies` has no effect — data loads unconditionally.

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
- **`schemaMetadata.yml` — dbt-inspired sidecar metadata.** Parquet carries data + physical schema but no descriptions, constraints, or relationships. A YAML sidecar file (optional, loaded from local directory or S3) enriches `TableInfo`/`ColumnInfo` with descriptions and declares PK/UNIQUE/NOT NULL/FK constraints. Format follows dbt `schema.yml` conventions for familiarity.
- **Constraints are enforced at load time.** Data that violates declared constraints is invalid state. By default, `fromParquet`/`fromS3` throw `IllegalStateException` on violation. `ConstraintStrategies` allows callers to downgrade specific constraint types to IGNORE (log + continue) for exploratory workflows.
- **UNIQUE via `CREATE UNIQUE INDEX`** — DuckDB does not support `ALTER TABLE ADD UNIQUE`. Indexes create dependencies that prevent further `ALTER TABLE`, so constraint application order is: NOT NULL → PRIMARY KEY → UNIQUE INDEX.
- **FK validated post-load via query** — DuckDB FK constraints are informational only (not enforced at runtime). Referential integrity is validated by querying for orphaned rows after all tables are loaded. Null FK values are allowed (standard SQL semantics).
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
- kaml 0.104.0 (YAML parsing via kotlinx-serialization)
- Kotlinx Coroutines

## Build & Test

```bash
./gradlew :agentio-module-data:build
./gradlew :agentio-module-data:test
```
