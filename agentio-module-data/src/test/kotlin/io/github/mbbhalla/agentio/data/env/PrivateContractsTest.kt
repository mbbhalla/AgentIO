package io.github.mbbhalla.agentio.data.env

import io.github.mbbhalla.agentio.data.model.DataValue
import io.github.mbbhalla.agentio.data.model.DomainModel
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isDirectory
import kotlin.io.path.name
import kotlin.io.path.walk
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * An extra validation layer over models that cannot be committed (proprietary, large, or messy). Every
 * `*.yaml` / `*.yml` under `src/test/resources/domainmodel/private/`, a git-ignored directory, is
 * parsed as an ODCS document, built in DuckDB, and read back from the catalog; a model that parses but
 * cannot be built would fail here. The directory is usually absent, and then no test is generated.
 *
 * To run locally: drop a contract under `private/<anything>/` and run this class.
 */
class PrivateContractsTest {
    private val privateRoot = Path.of("src/test/resources/domainmodel/private")

    private fun DuckDbDatabaseEnvironment.scalar(sql: String): DataValue =
        executeQuery(selectStatement(sql).getOrThrow())
            .records
            .single()
            .values.values
            .single()

    @OptIn(kotlin.io.path.ExperimentalPathApi::class)
    private fun contracts(): List<Path> =
        if (privateRoot.isDirectory()) {
            privateRoot
                .walk()
                .filter {
                    it.name.endsWith(".yaml") || it.name.endsWith(".yml")
                }.sorted()
                .toList()
        } else {
            emptyList()
        }

    @TestFactory
    fun `every private contract parses, builds in DuckDB and round-trips through the catalog`(): List<DynamicTest> =
        contracts().map { contract ->
            DynamicTest.dynamicTest(privateRoot.relativize(contract).toString()) {
                val model = DomainModel.fromOdcs(listOf(contract))
                val env = DuckDbDatabaseEnvironment.fromDomainModel(model)

                assertEquals(model.tables.keys, env.listTables(), "every table was created")
                model.tables.values.forEach { table -> assertEquals(table.toTableInfo(), env.getTableInfo(table.name), table.name.value) }

                val constraints = env.scalar("SELECT count(*) AS n FROM duckdb_constraints()") as DataValue.LongValue
                val declared =
                    model.tables.values.sumOf { t ->
                        (if (t.primaryKey.isEmpty()) 0 else 1) + t.uniqueKeys.size + t.foreignKeys.size +
                            t.columns.count { !it.nullable } + t.columns.sumOf { it.constraints.size }
                    }
                assertEquals(declared.toLong(), constraints.value, "DuckDB holds exactly the constraints the model declares")

                val comments = env.scalar("SELECT count(*) AS n FROM duckdb_columns() WHERE comment IS NOT NULL") as DataValue.LongValue
                assertEquals(
                    model.tables.values
                        .sumOf { t ->
                            t.columns.count { it.description.isNotBlank() }
                        }.toLong(),
                    comments.value,
                    "every column description is a comment",
                )

                println(
                    "private contract ${contract.name}: ${model.tables.size} tables, ${constraints.value} constraints, ${comments.value} column comments",
                )
                assertTrue(Files.size(contract) > 0)
            }
        }

    /** Keeps the class discoverable with one always-present test, so an empty `private/` is visible as a skip rather than silence. */
    @TestFactory
    fun `private directory status`(): List<DynamicTest> =
        listOf(
            DynamicTest.dynamicTest(
                if (privateRoot.isDirectory()) "present: ${contracts().size} contract(s)" else "absent: no private contracts to validate",
            ) {
                if (privateRoot.isDirectory()) println("private contracts: ${contracts().map { privateRoot.relativize(it) }}")
            },
        )
}
