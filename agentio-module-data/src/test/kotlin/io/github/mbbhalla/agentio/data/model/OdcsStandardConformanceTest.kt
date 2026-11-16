package io.github.mbbhalla.agentio.data.model

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.networknt.schema.InputFormat
import com.networknt.schema.JsonSchema
import com.networknt.schema.JsonSchemaFactory
import com.networknt.schema.SpecVersion
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import java.nio.file.Path
import kotlin.io.path.isDirectory
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.io.path.walk
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The fixtures are valid Open Data Contract Standard documents by the standard's own definition, not
 * only by what [OpenDataContract] happens to read. Each contract is validated against the JSON Schema
 * the Bitol project publishes (`src/test/resources/odcs/odcs-json-schema-latest.json`, from
 * `github.com/bitol-io/open-data-contract-standard/schema/`), after conversion from YAML to JSON.
 *
 * The schema file is a pinned copy; refresh it when moving to a newer ODCS version.
 */
class OdcsStandardConformanceTest {
    private val schemaFile = Path.of("src/test/resources/odcs/odcs-json-schema-latest.json")
    private val university = Path.of("src/test/resources/domainmodel/university/university.odcs.yaml")
    private val privateRoot = Path.of("src/test/resources/domainmodel/private")

    private val schema: JsonSchema by lazy {
        JsonSchemaFactory
            .getInstance(
                SpecVersion.VersionFlag.V201909,
            ).getSchema(schemaFile.readText())
    }
    private val yaml = ObjectMapper(YAMLFactory())
    private val json = ObjectMapper()

    private fun violations(contract: Path): List<String> =
        schema.validate(json.writeValueAsString(yaml.readTree(contract.readText())), InputFormat.JSON).map {
            "${it.instanceLocation}: ${it.message}"
        }

    @Test
    fun `the pinned schema is the ODCS schema and covers the version the fixtures declare`() {
        val root = json.readTree(schemaFile.readText())
        assertEquals("https://json-schema.org/draft/2019-09/schema", root[$$"$schema"].asText())
        assertEquals("Open Data Contract Standard (ODCS)", root["title"].asText())
        val versions = root["properties"]["apiVersion"]["enum"].map { it.asText() }
        assertTrue("v3.2.0" in versions, "schema accepts apiVersion v3.2.0: $versions")
    }

    @Test
    fun `the university contract is valid ODCS`() {
        assertEquals(emptyList(), violations(university))
    }

    @Test
    fun `the validator rejects what the standard forbids, so a passing fixture means something`() {
        val bareEnum = university.readText().replace("          - value: CARD\n", "          - CARD\n")
        val invalid = schema.validate(json.writeValueAsString(yaml.readTree(bareEnum)), InputFormat.JSON)
        assertTrue(
            invalid.any {
                it.message.contains("object expected")
            },
            "an enum entry that is not an object is rejected: ${invalid.map { it.message }.take(3)}",
        )

        val unknownKey = university.readText().replace("    physicalType: table\n", "    physicalType: table\n    colour: blue\n")
        val unknown = schema.validate(json.writeValueAsString(yaml.readTree(unknownKey)), InputFormat.JSON)
        assertTrue(
            unknown.any {
                it.message.contains("colour")
            },
            "an unknown key on a schema object is rejected: ${unknown.map { it.message }.take(3)}",
        )
    }

    /** Private contracts (git-ignored, see `PrivateContractsTest`) must be valid ODCS too; none present means no test. */
    @OptIn(kotlin.io.path.ExperimentalPathApi::class)
    @TestFactory
    fun `every private contract is valid ODCS`(): List<DynamicTest> =
        (
            if (privateRoot.isDirectory()) {
                privateRoot
                    .walk()
                    .filter { it.name.endsWith(".odcs.yaml") }
                    .sorted()
                    .toList()
            } else {
                emptyList()
            }
        ).map { contract ->
            DynamicTest.dynamicTest(privateRoot.relativize(contract).toString()) { assertEquals(emptyList(), violations(contract)) }
        }
}
