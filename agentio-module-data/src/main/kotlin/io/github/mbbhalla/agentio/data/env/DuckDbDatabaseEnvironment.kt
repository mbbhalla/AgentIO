package io.github.mbbhalla.agentio.data.env

import aws.sdk.kotlin.services.s3.S3Client
import aws.sdk.kotlin.services.s3.model.GetObjectRequest
import aws.sdk.kotlin.services.s3.model.ListObjectVersionsRequest
import aws.smithy.kotlin.runtime.content.toByteArray
import io.github.mbbhalla.agentio.core.common.generateList
import io.github.mbbhalla.agentio.data.model.ColumnInfo
import io.github.mbbhalla.agentio.data.model.ColumnName
import io.github.mbbhalla.agentio.data.model.ColumnType
import io.github.mbbhalla.agentio.data.model.DatabaseEnvironmentSnapshot
import io.github.mbbhalla.agentio.data.model.Dataset
import io.github.mbbhalla.agentio.data.model.ExplainResult
import io.github.mbbhalla.agentio.data.model.S3ObjectKey
import io.github.mbbhalla.agentio.data.model.S3Uri
import io.github.mbbhalla.agentio.data.model.TableInfo
import io.github.mbbhalla.agentio.data.model.TableName
import io.github.mbbhalla.agentio.data.model.Version
import io.github.mbbhalla.agentio.data.model.VersionSet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager

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

        fun fromParquet(directory: Path): DuckDbDatabaseEnvironment {
            val parquetFiles =
                directory.toFile().listFiles { f -> f.extension == "parquet" }
                    ?: throw IllegalArgumentException("Directory does not exist: $directory")
            require(parquetFiles.isNotEmpty()) { "No .parquet files found in: $directory" }

            return loadParquetFiles(parquetFiles.toList(), snapshot = null)
        }

        suspend fun fromS3(
            snapshot: DatabaseEnvironmentSnapshot,
            s3Uri: S3Uri,
            s3Client: S3Client,
        ): DuckDbDatabaseEnvironment {
            val tempDir =
                withContext(Dispatchers.IO) {
                    Files.createTempDirectory("agentio-parquet-${java.util.UUID.randomUUID()}")
                }

            val resolvedVersions = resolveVersionsAtTimestamp(snapshot.timestamp, s3Uri, s3Client)
            require(resolvedVersions.isNotEmpty()) {
                "No .parquet files found at ${s3Uri.value} as of ${snapshot.timestamp}"
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

            val versionSet = VersionSet(versions = resolvedVersions)
            val resolvedSnapshot = snapshot.copy(versionSet = versionSet)

            val parquetFiles =
                tempDir.toFile().listFiles { f -> f.extension == "parquet" }?.toList()
                    ?: emptyList()

            return loadParquetFiles(parquetFiles, snapshot = resolvedSnapshot)
        }

        private fun loadParquetFiles(
            parquetFiles: List<java.io.File>,
            snapshot: DatabaseEnvironmentSnapshot?,
        ): DuckDbDatabaseEnvironment {
            Class.forName("org.duckdb.DuckDBDriver")
            val connection = DriverManager.getConnection("jdbc:duckdb:")
            val tableMetadata = mutableMapOf<TableName, TableInfo>()

            connection.createStatement().use { stmt ->
                parquetFiles.forEach { file ->
                    val tableName = TableName(file.nameWithoutExtension)
                    stmt.execute(
                        "CREATE VIEW ${tableName.value} AS SELECT * FROM read_parquet('${file.absolutePath}')",
                    )
                    val columns = inferColumns(connection, tableName)
                    tableMetadata[tableName] =
                        TableInfo(
                            name = tableName,
                            description = "Table loaded from ${file.name}",
                            columns = columns,
                        )
                }
            }

            val env = DuckDbDatabaseEnvironment(connection, tableMetadata, snapshot = snapshot)
            activate(env)
            return env
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
