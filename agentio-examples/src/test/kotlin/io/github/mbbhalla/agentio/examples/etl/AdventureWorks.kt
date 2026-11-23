package io.github.mbbhalla.agentio.examples.etl

import io.github.mbbhalla.agentio.data.model.DomainModel
import java.nio.file.Path
import kotlin.io.path.readText

/** The example's contract, sources and hand-written reference load, as the tests use them. */
internal object AdventureWorks {
    private val contract: Path = Path.of(requireNotNull(System.getProperty("etl.contract")) { "etl.contract is set by Gradle" })
    val sources: Path = Path.of(requireNotNull(System.getProperty("etl.sources")) { "etl.sources is set by Gradle" })
    private val model: DomainModel by lazy { DomainModel.fromOdcs(listOf(contract)) }

    /** A fresh target database; each test gets its own. */
    fun workspace(): EtlWorkspace = EtlWorkspace(model, SourceFiles.of(sources))

    /** Rows each table holds after the reference load. */
    val expectedRows: Map<String, Int> =
        linkedMapOf(
            "department" to 16,
            "shift" to 3,
            "phonenumbertype" to 3,
            "businessentity" to 16,
            "person" to 16,
            "employee" to 16,
            "emailaddress" to 16,
            "personphone" to 23,
            "employeedepartmenthistory" to 19,
            "employeepayhistory" to 20,
        )

    /** Table name to SELECT, in load order, from `reference-etl.sql` with the source directory filled in. */
    val referenceSql: Map<String, String> by lazy {
        val text =
            Path
                .of(requireNotNull(javaClass.getResource("/etl/adventureworks/reference-etl.sql")).toURI())
                .readText()
                .replace("\${SOURCE}", SourceFiles.of(sources).directory.toString())
        BLOCK
            .findAll(text)
            .associate { match ->
                match.groupValues[1] to
                    match.groupValues[2]
                        .lines()
                        .filterNot { it.startsWith("--") }
                        .joinToString("\n")
                        .trim()
            }.also { check(it.keys.toList() == expectedRows.keys.toList()) { "reference-etl.sql tables: ${it.keys}" } }
    }

    private val BLOCK = Regex("""^-- table: (\w+)\n(.*?)(?=^-- table: |\z)""", setOf(RegexOption.MULTILINE, RegexOption.DOT_MATCHES_ALL))

    val references: List<Reference> =
        listOf(
            Reference("person", "businessentityid", "businessentity", "businessentityid"),
            Reference("employee", "businessentityid", "person", "businessentityid"),
            Reference("emailaddress", "businessentityid", "person", "businessentityid"),
            Reference("personphone", "businessentityid", "person", "businessentityid"),
            Reference("personphone", "phonenumbertypeid", "phonenumbertype", "phonenumbertypeid"),
            Reference("employeedepartmenthistory", "businessentityid", "employee", "businessentityid"),
            Reference("employeedepartmenthistory", "departmentid", "department", "departmentid"),
            Reference("employeedepartmenthistory", "shiftid", "shift", "shiftid"),
            Reference("employeepayhistory", "businessentityid", "employee", "businessentityid"),
        )

    private val sourcesOf: Map<String, List<String>> =
        mapOf(
            "department" to listOf("cost_centers.csv"),
            "shift" to listOf("shift_patterns.csv"),
            "employeedepartmenthistory" to listOf("org_assignment_events.csv"),
            "employeepayhistory" to listOf("payroll_register.csv"),
        )

    /** The plan the reference load implements. */
    val referencePlan: LoadPlan =
        LoadPlan(
            steps =
                expectedRows.keys.map { table ->
                    LoadStep(table, sourcesOf[table] ?: listOf("hris_roster.csv"), "As in reference-etl.sql")
                },
            references = references,
        )
}
