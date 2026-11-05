package io.github.mbbhalla.agentio.data.env

import aws.sdk.kotlin.services.s3.S3Client
import aws.sdk.kotlin.services.s3.model.GetObjectRequest
import aws.sdk.kotlin.services.s3.model.ListObjectVersionsRequest
import aws.smithy.kotlin.runtime.content.toByteArray
import io.github.mbbhalla.agentio.core.common.generateList
import io.github.mbbhalla.agentio.data.model.ColumnInfo
import io.github.mbbhalla.agentio.data.model.ColumnName
import io.github.mbbhalla.agentio.data.model.ColumnType
import io.github.mbbhalla.agentio.data.model.ConstraintStrategies
import io.github.mbbhalla.agentio.data.model.DatabaseEnvironmentSnapshot
import io.github.mbbhalla.agentio.data.model.Dataset
import io.github.mbbhalla.agentio.data.model.ExplainResult
import io.github.mbbhalla.agentio.data.model.ForeignKeyRef
import io.github.mbbhalla.agentio.data.model.S3ObjectKey
import io.github.mbbhalla.agentio.data.model.S3Uri
import io.github.mbbhalla.agentio.data.model.SchemaMetadata
import io.github.mbbhalla.agentio.data.model.TableInfo
import io.github.mbbhalla.agentio.data.model.TableName
import io.github.mbbhalla.agentio.data.model.Version
import io.github.mbbhalla.agentio.data.model.VersionSet
import io.github.mbbhalla.agentio.data.model.ViolationBehavior
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.putJsonArray
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager

private val logger = LoggerFactory.getLogger(DuckDbDatabaseEnvironment::class.java)

/**
 * An in-process DuckDB database that agents and tools read, together with the table metadata they
 * discover it through. Create one with [fromStatements], [fromParquet] or [fromS3] and inject it where
 * it is used. Statements are validated by an environment and can only be executed on that same
 * environment, so any number of environments can coexist in one JVM.
 */
class DuckDbDatabaseEnvironment private constructor(
    private val connection: Connection,
    private val tableMetadata: Map<TableName, TableInfo>,
    val snapshot: DatabaseEnvironmentSnapshot?,
) {
    fun listTables(): Set<TableName> = tableMetadata.keys

    fun getTableInfo(tableName: TableName): TableInfo =
        tableMetadata[tableName]
            ?: throw IllegalArgumentException("Table '${tableName.value}' not found")

    /**
     * The only way to obtain a [SelectSqlStatement]: [sql] must pass `EXPLAIN` on this environment and
     * be exactly one read-only `SELECT`. Fails with [IllegalArgumentException] otherwise.
     */
    fun selectStatement(sql: String): Result<SelectSqlStatement> = validate(sql, StatementType.SELECT).map { SelectSqlStatement(it, this) }

    /** The only way to obtain an [InsertSqlStatement]; see [selectStatement]. */
    fun insertStatement(sql: String): Result<InsertSqlStatement> = validate(sql, StatementType.INSERT).map { InsertSqlStatement(it, this) }

    /** The only way to obtain an [UpdateSqlStatement]; see [selectStatement]. */
    fun updateStatement(sql: String): Result<UpdateSqlStatement> = validate(sql, StatementType.UPDATE).map { UpdateSqlStatement(it, this) }

    /** The only way to obtain a [DeleteSqlStatement]; see [selectStatement]. */
    fun deleteStatement(sql: String): Result<DeleteSqlStatement> = validate(sql, StatementType.DELETE).map { DeleteSqlStatement(it, this) }

    /**
     * Runs [sql] on this environment.
     *
     * @throws IllegalArgumentException if [sql] was validated by a different environment
     */
    fun executeQuery(sql: SelectSqlStatement): Dataset {
        require(sql.environment === this) { "SelectSqlStatement was validated by a different environment" }
        return connection.createStatement().use { stmt ->
            Dataset.from(stmt.executeQuery(sql.value))
        }
    }

    private fun validate(
        sql: String,
        expected: StatementType,
    ): Result<String> {
        val explained = explain(sql)
        return when {
            explained is ExplainResult.Failure ->
                Result.failure(IllegalArgumentException("Invalid ${expected.name} SQL: ${explained.error}"))
            statementType(sql) != expected ->
                Result.failure(IllegalArgumentException("Not ${expected.withArticle()} statement"))
            else -> Result.success(sql)
        }
    }

    private fun StatementType.withArticle(): String =
        when (this) {
            StatementType.INSERT, StatementType.UPDATE, StatementType.UNKNOWN -> "an $name"
            StatementType.SELECT, StatementType.DELETE -> "a $name"
        }

    internal fun explain(sql: String): ExplainResult {
        if (!isSingleStatement(sql)) return ExplainResult.Failure(MULTIPLE_STATEMENTS_ERROR)
        return try {
            connection.createStatement().use { stmt ->
                stmt.executeQuery("EXPLAIN $sql").close()
            }
            ExplainResult.Success
        } catch (e: Exception) {
            ExplainResult.Failure(e.message ?: "Unknown SQL error")
        }
    }

    /**
     * Classifies [sql] fail-closed: anything that is not provably a single SELECT, INSERT, UPDATE or
     * DELETE (DDL, COPY, MERGE/upsert, multi-statement input, invalid SQL) is [StatementType.UNKNOWN].
     *
     * SELECT is decided by DuckDB's parser via [parseSelect], so it does not depend on the plan format
     * and a SELECT that writes through a CTE is not a SELECT. DML kinds are read from the structured
     * JSON plan, scanning the whole operator tree because DuckDB may wrap the write (e.g.
     * `DELETE ... RETURNING` has a PROJECTION root).
     */
    internal fun statementType(sql: String): StatementType =
        if (!isSingleStatement(sql)) {
            StatementType.UNKNOWN
        } else {
            classifySingleStatement(sql)
        }

    private fun classifySingleStatement(sql: String): StatementType =
        try {
            if (isSingleSelect(sql)) StatementType.SELECT else dmlStatementType(jsonPlanOperatorNames(sql))
        } catch (e: java.sql.SQLException) {
            logger.debug("Statement classification failed; treating as UNKNOWN: {}", e.message)
            StatementType.UNKNOWN
        } catch (e: kotlinx.serialization.SerializationException) {
            logger.warn("Unparseable DuckDB JSON plan; treating statement as UNKNOWN", e)
            StatementType.UNKNOWN
        }

    private fun isSingleSelect(sql: String): Boolean =
        try {
            parseSelect(sql)
            true
        } catch (_: IllegalArgumentException) {
            false
        }

    /**
     * Parses [sql] with DuckDB's own parser (`json_serialize_sql`) and returns the parse tree of its
     * single read-only `SELECT`. Nothing is bound or executed, so the tables it names need not exist.
     *
     * @throws IllegalArgumentException if [sql] is not exactly one `SELECT`, or if it contains a
     *   data-modifying query such as `WITH d AS (DELETE FROM t RETURNING *) SELECT * FROM d`
     */
    internal fun parseSelect(sql: String): JsonObject {
        val parsed = Json.parseToJsonElement(queryString("SELECT json_serialize_sql(?::VARCHAR)", sql)).jsonObject
        val statements =
            requireNotNull(parsed["statements"]?.jsonArray) {
                "Invalid SELECT SQL: ${parsed["error_message"]?.jsonPrimitive?.content}"
            }
        require(statements.size == 1) { "Exactly one statement is required, found ${statements.size}" }
        val statement = statements.single().jsonObject
        val query = statement.getValue("node").jsonObject
        val writes = queryNodeTypes(query).filterNot { it in READ_ONLY_QUERY_NODES }.toSet()
        require(writes.isEmpty()) { "Data-modifying queries are not permitted: $writes" }
        return query
    }

    /** Renders a parse tree in the form returned by [parseSelect] back to SQL (`json_deserialize_sql`). */
    internal fun renderSelect(query: JsonObject): String =
        queryString(
            "SELECT json_deserialize_sql(?::JSON)",
            buildJsonObject { putJsonArray("statements") { addJsonObject { put("node", query) } } }.toString(),
        )

    private fun queryString(
        sql: String,
        argument: String,
    ): String =
        connection.prepareStatement(sql).use { stmt ->
            stmt.setString(1, argument)
            stmt.executeQuery().use { rs ->
                rs.next()
                rs.getString(1)
            }
        }

    private fun jsonPlanOperatorNames(sql: String): Set<String> =
        connection.createStatement().use { stmt ->
            stmt.executeQuery("EXPLAIN (FORMAT JSON) $sql").use { rs ->
                generateSequence { if (rs.next()) rs else null }
                    .filter { it.getString("explain_key") == PHYSICAL_PLAN_KEY }
                    .map { it.getString("explain_value") }
                    .flatMap { collectOperatorNames(Json.parseToJsonElement(it)) }
                    .toSet()
            }
        }

    companion object {
        fun fromStatements(
            ddl: List<String>,
            dml: List<String>,
            tableMetadata: Set<TableInfo>,
        ): DuckDbDatabaseEnvironment {
            Class.forName("org.duckdb.DuckDBDriver")
            val connection = DriverManager.getConnection("jdbc:duckdb:")
            connection.createStatement().use { stmt ->
                ddl.forEach { stmt.execute(it) }
                dml.forEach { stmt.execute(it) }
            }
            return DuckDbDatabaseEnvironment(connection, tableMetadata.associateBy { it.name }, snapshot = null)
        }

        fun fromParquet(
            directory: Path,
            constraintStrategies: ConstraintStrategies = ConstraintStrategies(),
        ): DuckDbDatabaseEnvironment {
            val parquetFiles =
                directory.toFile().listFiles { f -> f.extension == "parquet" }
                    ?: throw IllegalArgumentException("Directory does not exist: $directory")
            require(parquetFiles.isNotEmpty()) { "No .parquet files found in: $directory" }

            val metadataFile = directory.resolve(SchemaMetadata.FILENAME).toFile()
            val schemaMetadata = if (metadataFile.exists()) SchemaMetadata.fromFile(metadataFile) else null

            return loadParquetFiles(
                parquetFiles.toList(),
                snapshot = null,
                schemaMetadata = schemaMetadata,
                constraintStrategies = constraintStrategies,
            )
        }

        suspend fun fromS3(
            timestamp: java.time.Instant,
            s3Uri: S3Uri,
            s3Client: S3Client,
            constraintStrategies: ConstraintStrategies = ConstraintStrategies(),
        ): DuckDbDatabaseEnvironment {
            val tempDir =
                withContext(Dispatchers.IO) {
                    Files.createTempDirectory("agentio-parquet-${java.util.UUID.randomUUID()}")
                }

            val resolvedVersions = resolveVersionsAtTimestamp(timestamp, s3Uri, s3Client)
            require(resolvedVersions.isNotEmpty()) {
                "No .parquet files found at ${s3Uri.value} as of $timestamp"
            }

            resolvedVersions.forEach { version ->
                val fileName = version.fileReference.value.substringAfterLast('/')
                val localFile = tempDir.resolve(fileName)
                s3Client.getObject(
                    GetObjectRequest {
                        bucket = s3Uri.bucket
                        key = version.fileReference.value
                        versionId = version.versionId
                    },
                ) { getResponse ->
                    val bytes = getResponse.body?.toByteArray() ?: ByteArray(0)
                    withContext(Dispatchers.IO) { Files.write(localFile, bytes) }
                }
            }

            val schemaMetadata = downloadSchemaMetadata(s3Uri, s3Client, tempDir)

            val snapshot =
                DatabaseEnvironmentSnapshot(
                    timestamp = timestamp,
                    versionSet = VersionSet(versions = resolvedVersions),
                )

            val parquetFiles =
                tempDir.toFile().listFiles { f -> f.extension == "parquet" }?.toList()
                    ?: emptyList()

            val env =
                loadParquetFiles(
                    parquetFiles,
                    snapshot = snapshot,
                    schemaMetadata = schemaMetadata,
                    constraintStrategies = constraintStrategies,
                )
            withContext(Dispatchers.IO) { tempDir.toFile().deleteRecursively() }
            return env
        }

        private fun loadParquetFiles(
            parquetFiles: List<java.io.File>,
            snapshot: DatabaseEnvironmentSnapshot?,
            schemaMetadata: SchemaMetadata? = null,
            constraintStrategies: ConstraintStrategies = ConstraintStrategies(),
        ): DuckDbDatabaseEnvironment {
            Class.forName("org.duckdb.DuckDBDriver")
            val connection = DriverManager.getConnection("jdbc:duckdb:")
            val tableMetadata = mutableMapOf<TableName, TableInfo>()

            connection.createStatement().use { stmt ->
                parquetFiles.forEach { file ->
                    val tableName = TableName(file.nameWithoutExtension)
                    stmt.execute(
                        "CREATE TABLE ${tableName.value} AS SELECT * FROM read_parquet('${file.absolutePath}')",
                    )
                    val columns =
                        inferColumns(connection, tableName).map { col ->
                            val meta = schemaMetadata?.columnConstraints(tableName, col.name)
                            val desc = meta?.description?.ifBlank { null }
                            val fk = meta?.foreignKey?.let { parseForeignKey(it) }
                            col.copy(
                                description = desc ?: col.description,
                                primaryKey = meta?.primaryKey ?: col.primaryKey,
                                foreignKey = fk ?: col.foreignKey,
                                nullable = if (meta?.notNull == true) false else col.nullable,
                            )
                        }
                    val tableDescription =
                        schemaMetadata?.tableDescription(tableName) ?: "Table loaded from ${file.name}"
                    tableMetadata[tableName] =
                        TableInfo(
                            name = tableName,
                            description = tableDescription,
                            columns = columns,
                        )

                    applyComments(stmt, tableName, tableDescription, columns)
                    applyConstraints(connection, tableName, columns, schemaMetadata, constraintStrategies)
                }
            }

            if (schemaMetadata != null) {
                connection.createStatement().use { stmt ->
                    validateForeignKeys(stmt, tableMetadata, schemaMetadata, constraintStrategies)
                }
            }

            return DuckDbDatabaseEnvironment(connection, tableMetadata, snapshot = snapshot)
        }

        private fun applyComments(
            stmt: java.sql.Statement,
            tableName: TableName,
            tableDescription: String,
            columns: List<ColumnInfo>,
        ) {
            stmt.execute("COMMENT ON TABLE ${tableName.value} IS '${tableDescription.replace("'", "''")}'")
            columns.filter { it.description.isNotBlank() }.forEach { col ->
                stmt.execute(
                    "COMMENT ON COLUMN ${tableName.value}.${col.name.value} IS '${col.description.replace("'", "''")}'",
                )
            }
        }

        private fun applyConstraints(
            connection: Connection,
            tableName: TableName,
            columns: List<ColumnInfo>,
            schemaMetadata: SchemaMetadata?,
            strategies: ConstraintStrategies,
        ) {
            if (schemaMetadata == null) return

            // NOT NULL first — ALTER TABLE fails if indexes/PKs already exist on the table
            columns.forEach { col ->
                val meta = schemaMetadata.columnConstraints(tableName, col.name) ?: return@forEach
                if (meta.notNull) {
                    executeConstraint(
                        connection,
                        "ALTER TABLE ${tableName.value} ALTER COLUMN ${col.name.value} SET NOT NULL",
                        strategies.onNotNullViolation,
                        "NOT NULL on ${tableName.value}.${col.name.value}",
                    )
                }
            }

            // PRIMARY KEY
            val pkColumns =
                columns.filter { col ->
                    schemaMetadata.columnConstraints(tableName, col.name)?.primaryKey == true
                }
            if (pkColumns.isNotEmpty()) {
                val pkColNames = pkColumns.joinToString(", ") { it.name.value }
                executeConstraint(
                    connection,
                    "ALTER TABLE ${tableName.value} ADD PRIMARY KEY ($pkColNames)",
                    strategies.onPrimaryKeyViolation,
                    "PRIMARY KEY on ${tableName.value}($pkColNames)",
                )
            }

            // UNIQUE (via index — must come after ALTER TABLE operations)
            columns.forEach { col ->
                val meta = schemaMetadata.columnConstraints(tableName, col.name) ?: return@forEach
                if (meta.unique && !meta.primaryKey) {
                    val indexName = "uq_${tableName.value}_${col.name.value}"
                    executeConstraint(
                        connection,
                        "CREATE UNIQUE INDEX $indexName ON ${tableName.value} (${col.name.value})",
                        strategies.onUniqueViolation,
                        "UNIQUE on ${tableName.value}.${col.name.value}",
                    )
                }
            }
        }

        private fun validateForeignKeys(
            stmt: java.sql.Statement,
            tableMetadata: Map<TableName, TableInfo>,
            schemaMetadata: SchemaMetadata,
            strategies: ConstraintStrategies,
        ) {
            schemaMetadata.tables.forEach { tableEntry ->
                val tableName = TableName(tableEntry.name)
                if (tableName !in tableMetadata) return@forEach

                tableEntry.columns.filter { it.foreignKey != null }.forEach { colEntry ->
                    val fk = parseForeignKey(colEntry.foreignKey!!) ?: return@forEach
                    if (fk.table !in tableMetadata) return@forEach

                    val sql =
                        "SELECT COUNT(*) FROM ${tableName.value} " +
                            "WHERE ${colEntry.name} IS NOT NULL " +
                            "AND ${colEntry.name} NOT IN (SELECT ${fk.column.value} FROM ${fk.table.value})"
                    try {
                        val rs = stmt.executeQuery(sql)
                        rs.next()
                        val orphanCount = rs.getLong(1)
                        rs.close()
                        if (orphanCount > 0) {
                            val msg =
                                "FOREIGN KEY violation: ${tableName.value}.${colEntry.name} " +
                                    "has $orphanCount orphaned rows referencing ${fk.table.value}.${fk.column.value}"
                            when (strategies.onForeignKeyViolation) {
                                ViolationBehavior.THROW -> throw IllegalStateException(msg)
                                ViolationBehavior.IGNORE -> logger.warn(msg)
                            }
                        }
                    } catch (e: IllegalStateException) {
                        throw e
                    } catch (e: Exception) {
                        logger.warn("Failed to validate FK ${tableName.value}.${colEntry.name}: ${e.message}")
                    }
                }
            }
        }

        private fun executeConstraint(
            connection: Connection,
            sql: String,
            behavior: ViolationBehavior,
            description: String,
        ) {
            try {
                connection.createStatement().use { it.execute(sql) }
            } catch (e: Exception) {
                when (behavior) {
                    ViolationBehavior.THROW -> throw IllegalStateException(
                        "Constraint violation ($description): ${e.message}",
                        e,
                    )
                    ViolationBehavior.IGNORE ->
                        logger.warn(
                            "Constraint violation ignored ($description): ${e.message}",
                        )
                }
            }
        }

        private fun parseForeignKey(fk: String): ForeignKeyRef? {
            val parts = fk.split(".")
            if (parts.size != 2) return null
            return try {
                ForeignKeyRef(table = TableName(parts[0]), column = ColumnName(parts[1]))
            } catch (_: IllegalArgumentException) {
                null
            }
        }

        private suspend fun downloadSchemaMetadata(
            s3Uri: S3Uri,
            s3Client: S3Client,
            tempDir: Path,
        ): SchemaMetadata? =
            try {
                val metadataKey = "${s3Uri.key.value.trimEnd('/')}/${SchemaMetadata.FILENAME}"
                var bytes: ByteArray? = null
                s3Client.getObject(
                    GetObjectRequest {
                        bucket = s3Uri.bucket
                        key = metadataKey
                    },
                ) { getResponse ->
                    bytes = getResponse.body?.toByteArray()
                }
                bytes?.let { raw ->
                    val localFile = tempDir.resolve(SchemaMetadata.FILENAME)
                    withContext(Dispatchers.IO) { Files.write(localFile, raw) }
                    SchemaMetadata.fromFile(localFile.toFile())
                }
            } catch (_: Exception) {
                null
            }

        private suspend fun resolveVersionsAtTimestamp(
            timestamp: java.time.Instant,
            s3Uri: S3Uri,
            s3Client: S3Client,
        ): Set<Version> {
            val timestampEpoch = timestamp.epochSecond

            val allVersionResponses =
                generateList(
                    seed =
                        s3Client.listObjectVersions(
                            ListObjectVersionsRequest {
                                bucket = s3Uri.bucket
                                prefix = s3Uri.key.value
                            },
                        ),
                ) { prev ->
                    if (prev.isTruncated == true) {
                        s3Client.listObjectVersions(
                            ListObjectVersionsRequest {
                                bucket = s3Uri.bucket
                                prefix = s3Uri.key.value
                                keyMarker = prev.nextKeyMarker
                                versionIdMarker = prev.nextVersionIdMarker
                            },
                        )
                    } else {
                        null
                    }
                }

            val epoch0 =
                aws.smithy.kotlin.runtime.time.Instant
                    .fromEpochSeconds(0)

            val deleteMarkerKeys =
                allVersionResponses
                    .flatMap { it.deleteMarkers ?: emptyList() }
                    .filter { marker ->
                        marker.key?.endsWith(".parquet") == true &&
                            marker.lastModified?.let { it.epochSeconds <= timestampEpoch } == true
                    }.groupBy { it.key }
                    .mapValues { (_, markers) ->
                        markers.maxByOrNull { it.lastModified ?: epoch0 }
                    }

            val candidateVersions =
                allVersionResponses
                    .asSequence()
                    .flatMap { it.versions ?: emptyList() }
                    .filter { obj ->
                        obj.key?.endsWith(".parquet") == true &&
                            obj.lastModified?.let { it.epochSeconds <= timestampEpoch } == true
                    }.groupBy { it.key }
                    .mapNotNull { (key, versions) ->
                        val latest =
                            versions.maxByOrNull {
                                it.lastModified ?: epoch0
                            } ?: return@mapNotNull null

                        val deleteMarker = deleteMarkerKeys[key]
                        if (deleteMarker != null) {
                            val deleteTime = deleteMarker.lastModified
                            val versionTime = latest.lastModified
                            if (deleteTime != null && versionTime != null && deleteTime > versionTime) {
                                return@mapNotNull null
                            }
                        }

                        Version(
                            fileReference = S3ObjectKey(key ?: return@mapNotNull null),
                            versionId = latest.versionId?.takeIf { it != "null" },
                        )
                    }.toSet()

            return candidateVersions
        }

        private fun inferColumns(
            connection: Connection,
            tableName: TableName,
        ): List<ColumnInfo> {
            val columns = mutableListOf<ColumnInfo>()
            val rs = connection.metaData.getColumns(null, null, tableName.value, null)
            while (rs.next()) {
                columns.add(
                    ColumnInfo(
                        name = ColumnName(rs.getString("COLUMN_NAME").lowercase()),
                        type = ColumnType.fromTypeName(rs.getString("TYPE_NAME")),
                        nullable = rs.getInt("NULLABLE") != 0,
                        primaryKey = false,
                        foreignKey = null,
                        description = "",
                    ),
                )
            }
            rs.close()

            val pkRs = connection.metaData.getPrimaryKeys(null, null, tableName.value)
            val pkColumns = mutableSetOf<String>()
            while (pkRs.next()) {
                pkColumns.add(pkRs.getString("COLUMN_NAME").lowercase())
            }
            pkRs.close()

            return columns.map { col ->
                if (col.name.value in pkColumns) col.copy(primaryKey = true) else col
            }
        }

        internal const val MULTIPLE_STATEMENTS_ERROR = "Multiple SQL statements are not allowed"

        /**
         * The DuckDB JDBC driver executes every statement in a multi-statement string — even via
         * `prepareStatement` or behind an `EXPLAIN` prefix — so `EXPLAIN SELECT 1; DROP TABLE t` really drops
         * `t`. DuckDB exposes no parse-only splitter over JDBC, so untrusted SQL is rejected unless its only
         * `;` is trailing. `;` is DuckDB's sole statement separator, making this unbypassable; the cost is
         * that a `;` inside a string literal or comment is also rejected (fail-closed).
         */
        internal fun isSingleStatement(sql: String): Boolean = ';' !in sql.trimEnd().removeSuffix(";")

        /**
         * Query nodes that only read. Every other kind writes: DuckDB parses `INSERT`, `UPDATE` and
         * `DELETE` inside a CTE as `INSERT_QUERY_NODE`, `UPDATE_QUERY_NODE` and `DELETE_QUERY_NODE`.
         */
        private val READ_ONLY_QUERY_NODES = setOf("SELECT_NODE", "SET_OPERATION_NODE", "RECURSIVE_CTE_NODE")

        /** The `type` of every query node in [tree]; query nodes are the objects that carry a `cte_map`. */
        private fun queryNodeTypes(tree: JsonElement): Sequence<String> =
            when (tree) {
                is JsonArray -> tree.asSequence().flatMap { queryNodeTypes(it) }
                is JsonObject ->
                    sequence {
                        if ("cte_map" in tree) yield(tree.getValue("type").jsonPrimitive.content)
                        tree.values.forEach { yieldAll(queryNodeTypes(it)) }
                    }
                else -> emptySequence()
            }

        /** `EXPLAIN` row holding the physical plan (other rows, if any, are ignored). */
        private const val PHYSICAL_PLAN_KEY = "physical_plan"

        /**
         * DuckDB physical write operators → statement kind. Operator names vary across DuckDB versions
         * (e.g. INSERT became BATCH_INSERT in 2.0), so all known spellings are listed. Unlisted write
         * operators (MERGE_INTO, DROP, CREATE_*, COPY_TO_FILE, …) intentionally map to nothing → UNKNOWN.
         */
        private val DML_OPERATORS: Map<String, StatementType> =
            mapOf(
                "INSERT" to StatementType.INSERT,
                "BATCH_INSERT" to StatementType.INSERT,
                "UPDATE" to StatementType.UPDATE,
                "DELETE" to StatementType.DELETE,
            )

        private fun collectOperatorNames(node: JsonElement): Sequence<String> =
            when (node) {
                is JsonArray -> node.asSequence().flatMap { collectOperatorNames(it) }
                is JsonObject ->
                    sequence {
                        (node["name"] as? JsonPrimitive)?.takeIf { it.isString }?.let { yield(it.content) }
                        node["children"]?.let { yieldAll(collectOperatorNames(it)) }
                    }
                else -> emptySequence()
            }

        /** Exactly one DML kind in the plan → that kind; none or several → UNKNOWN. */
        private fun dmlStatementType(operatorNames: Set<String>): StatementType =
            operatorNames.mapNotNull { DML_OPERATORS[it] }.toSet().singleOrNull() ?: StatementType.UNKNOWN
    }
}
