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
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager

private val logger = LoggerFactory.getLogger(DuckDbDatabaseEnvironment::class.java)

class DuckDbDatabaseEnvironment private constructor(
    private val connection: Connection,
    private val tableMetadata: Map<TableName, TableInfo>,
    override val snapshot: DatabaseEnvironmentSnapshot?,
) : DatabaseEnvironment() {
    override fun listTables(): Set<TableName> = tableMetadata.keys

    override fun getTableInfo(tableName: TableName): TableInfo =
        tableMetadata[tableName]
            ?: throw IllegalArgumentException("Table '${tableName.value}' not found")

    override fun explain(sql: String): ExplainResult =
        try {
            connection.createStatement().use { stmt ->
                stmt.executeQuery("EXPLAIN $sql").close()
            }
            ExplainResult.Success
        } catch (e: Exception) {
            ExplainResult.Failure(e.message ?: "Unknown SQL error")
        }

    override fun statementType(sql: String): StatementType =
        try {
            connection.createStatement().use { stmt ->
                val rs = stmt.executeQuery("EXPLAIN $sql")
                val plan =
                    buildString {
                        while (rs.next()) {
                            append(rs.getString("explain_value"))
                        }
                    }
                rs.close()
                extractStatementType(plan)
            }
        } catch (_: Exception) {
            StatementType.UNKNOWN
        }

    override fun executeQuery(sql: SelectSqlStatement): Dataset =
        connection.createStatement().use { stmt ->
            Dataset.from(stmt.executeQuery(sql.value))
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
            val env = DuckDbDatabaseEnvironment(connection, tableMetadata.associateBy { it.name }, snapshot = null)
            activate(env)
            return env
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

            val env = DuckDbDatabaseEnvironment(connection, tableMetadata, snapshot = snapshot)
            activate(env)
            return env
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

        private fun extractStatementType(plan: String): StatementType {
            val firstBoxContent =
                plan
                    .lines()
                    .map {
                        it
                            .trim()
                            .removePrefix("│")
                            .removeSuffix("│")
                            .trim()
                    }.firstOrNull { it.isNotBlank() && !it.startsWith("┌") && !it.startsWith("└") && !it.startsWith("─") }
                    ?: return StatementType.UNKNOWN

            return when (firstBoxContent) {
                "INSERT" -> StatementType.INSERT
                "UPDATE" -> StatementType.UPDATE
                "DELETE" -> StatementType.DELETE
                else -> StatementType.SELECT
            }
        }
    }
}
