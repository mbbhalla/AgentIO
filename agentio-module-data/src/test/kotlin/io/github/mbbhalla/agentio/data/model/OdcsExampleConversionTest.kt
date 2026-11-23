package io.github.mbbhalla.agentio.data.model

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.networknt.schema.InputFormat
import com.networknt.schema.JsonSchemaFactory
import com.networknt.schema.SpecVersion
import io.github.mbbhalla.agentio.data.env.DuckDbDatabaseEnvironment
import org.junit.jupiter.api.assertThrows
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `odcs_example.postgres.yaml` is the ODCS project's PostgreSQL AdventureWorks contract, unchanged.
 * `odcs_example.generic.yaml` is its engine-neutral conversion. These tests pin that the original
 * cannot be a domain model, that the conversion can, and that the conversion changed nothing but the
 * types, the composite key positions and the API version, each exactly as its header describes.
 */
class OdcsExampleConversionTest {
    private val original = Path.of("src/test/resources/odcs/odcs_example.postgres.yaml")
    private val generic = Path.of("src/test/resources/odcs/odcs_example.generic.yaml")
    private val yaml = ObjectMapper(YAMLFactory())
    private val json = ObjectMapper()

    private fun read(path: Path): JsonNode = yaml.readTree(path.readText())

    private fun JsonNode.objects(): List<JsonNode> = get("schema").toList()

    private fun JsonNode.contractProperties(): List<JsonNode> = get("properties").toList()

    @Test
    fun `both contracts are valid ODCS`() {
        val schema =
            JsonSchemaFactory
                .getInstance(SpecVersion.VersionFlag.V201909)
                .getSchema(Path.of("src/test/resources/odcs/odcs-json-schema-latest.json").readText())
        listOf(original, generic).forEach { contract ->
            val violations = schema.validate(json.writeValueAsString(read(contract)), InputFormat.JSON)
            assertEquals(emptySet(), violations, "$contract")
        }
    }

    @Test
    fun `the PostgreSQL contract is not a domain model, because its logical types do not say what the columns hold`() {
        val e = assertThrows<IllegalArgumentException> { DomainModel.fromOdcs(listOf(original)) }
        assertEquals(
            "$original schema object 'department' property 'name': logicalType 'object' has no column mapping",
            e.message,
        )
    }

    @Test
    fun `the generic contract builds every table in DuckDB, typed by its logical types`() {
        val env = DuckDbDatabaseEnvironment.fromDomainModel(DomainModel.fromOdcs(listOf(generic)))
        assertEquals(68, env.listTables().size)

        fun type(
            table: String,
            column: String,
        ) = env
            .getTableInfo(TableName(table))
            .columns
            .first { it.name.value == column }
            .type
        assertEquals(ColumnType.INTEGER, type("department", "departmentid"), "serial")
        assertEquals(ColumnType.VARCHAR, type("department", "name"), "Name domain")
        assertEquals(ColumnType.TIMESTAMP, type("department", "modifieddate"), "timestamp, which v3.0.0 called date")
        assertEquals(ColumnType.TIME, type("shift", "starttime"), "time, which v3.0.0 called date")
        assertEquals(ColumnType.DATE, type("employee", "birthdate"))
        assertEquals(ColumnType.BOOLEAN, type("employee", "salariedflag"), "Flag domain")
        assertEquals(ColumnType.INTEGER, type("employee", "vacationhours"), "int2")
        assertEquals(ColumnType.DOUBLE, type("employeepayhistory", "rate"), "numeric")
        val keys =
            env.executeQuery(
                env
                    .selectStatement(
                        "SELECT count(*) FILTER (WHERE len(constraint_column_names) > 1) AS composite, count(*) AS all_keys " +
                            "FROM duckdb_constraints() WHERE constraint_type = 'PRIMARY KEY'",
                    ).getOrThrow(),
            )
        assertEquals(DataValue.LongValue(23), keys.records.single()[ColumnName("composite")])
        assertEquals(DataValue.LongValue(68), keys.records.single()[ColumnName("all_keys")])
    }

    @Test
    fun `the contract outside the schema section is unchanged but for apiVersion`() {
        val before = read(original) as ObjectNode
        val after = read(generic) as ObjectNode
        assertEquals("v3.0.0", before.get("apiVersion").asText())
        assertEquals("v3.1.0", after.get("apiVersion").asText(), "the first version with the timestamp and time logical types")
        listOf(before, after).forEach { it.remove(listOf("apiVersion", "schema")) }
        assertEquals(before, after)
    }

    @Test
    fun `objects and properties keep their names, order, descriptions, keys and metadata`() {
        val before = read(original).objects()
        val after = read(generic).objects()
        assertEquals(before.map { it.get("name") }, after.map { it.get("name") })
        before.zip(after).forEach { (o, g) ->
            assertEquals(o.withoutProperties(), g.withoutProperties(), "object ${o.get("name")}")
            assertEquals(o.contractProperties().map { it.get("name") }, g.contractProperties().map { it.get("name") })
            o.contractProperties().zip(g.contractProperties()).forEach { (op, gp) ->
                assertEquals(
                    op.without(TYPE_KEYS),
                    gp.without(TYPE_KEYS + "logicalTypeOptions" + "primaryKeyPosition"),
                    "${o.get("name").asText()}.${op.get("name").asText()}",
                )
            }
        }
    }

    @Test
    fun `every PostgreSQL physical type becomes the logical type it implies`() {
        val pairs: List<Pair<JsonNode, JsonNode>> =
            read(original).objects().zip(read(generic).objects()).flatMap { (o, g) -> o.contractProperties().zip(g.contractProperties()) }
        assertEquals(456, pairs.size)
        pairs.forEach { (before: JsonNode, after: JsonNode) ->
            val where = before.get("name").asText()
            val physical = before.get("physicalType").asText()
            val (logicalType: String, options: Map<String, String>) = expectedLogicalType(physical)
            assertEquals(null, after.get("physicalType"), "$where: physicalType removed")
            assertEquals(logicalType, after.get("logicalType").asText(), "$where: $physical")
            assertEquals(options, after.optionsAsText(), "$where: $physical logicalTypeOptions")
        }
    }

    @Test
    fun `composite primary keys are numbered in declaration order and single keys are left alone`() {
        read(generic).objects().forEach { obj ->
            val keyProperties = obj.contractProperties().filter { it.get("primaryKey").asBoolean() }
            val positions = keyProperties.map { it.get("primaryKeyPosition")?.asInt() }
            val expected = if (keyProperties.size > 1) (1..keyProperties.size).toList() else listOf(null)
            assertEquals(expected, positions, "${obj.get("name")}")
            assertTrue(obj.contractProperties().none { !it.get("primaryKey").asBoolean() && it.has("primaryKeyPosition") })
        }
    }

    private fun JsonNode.optionsAsText(): Map<String, String> {
        val options: JsonNode = get("logicalTypeOptions") ?: return emptyMap()
        return options.properties().associate { (key: String, value: JsonNode) -> key to value.asText() }
    }

    private fun JsonNode.withoutProperties(): JsonNode = without(setOf("properties"))

    private fun JsonNode.without(keys: Set<String>): JsonNode = (deepCopy<JsonNode>() as ObjectNode).apply { remove(keys) }

    /** The conversion, stated once: PostgreSQL type, or AdventureWorks domain, to ODCS logicalType and options. */
    private fun expectedLogicalType(physical: String): Pair<String, Map<String, String>> {
        VARCHAR.matchEntire(physical)?.let { match ->
            val length = match.groupValues[1]
            return "string" to if (length == UNBOUNDED_VARCHAR) emptyMap() else mapOf("maxLength" to length)
        }
        return requireNotNull(CONVERSION[physical]) { "physicalType '$physical' has no conversion" }
    }

    companion object {
        private val TYPE_KEYS = setOf("logicalType", "physicalType")
        private val VARCHAR = Regex("""varchar\[(\d+)]""")
        private const val UNBOUNDED_VARCHAR = "2147483647"
        private val CONVERSION: Map<String, Pair<String, Map<String, String>>> =
            mapOf(
                "serial" to ("integer" to emptyMap()),
                "int4" to ("integer" to emptyMap()),
                "int2" to ("integer" to mapOf("format" to "i16")),
                "numeric" to ("number" to emptyMap()),
                "timestamp" to ("timestamp" to emptyMap()),
                "date" to ("date" to emptyMap()),
                "time" to ("time" to emptyMap()),
                "text" to ("string" to emptyMap()),
                "bpchar" to ("string" to emptyMap()),
                "xml" to ("string" to emptyMap()),
                "uuid" to ("string" to mapOf("format" to "uuid")),
                "bytea" to ("string" to mapOf("format" to "binary")),
                "Name" to ("string" to mapOf("maxLength" to "50")),
                "Phone" to ("string" to mapOf("maxLength" to "25")),
                "OrderNumber" to ("string" to mapOf("maxLength" to "25")),
                "AccountNumber" to ("string" to mapOf("maxLength" to "15")),
                "Flag" to ("boolean" to emptyMap()),
                "NameStyle" to ("boolean" to emptyMap()),
            )
    }
}
