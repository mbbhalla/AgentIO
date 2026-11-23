package io.github.mbbhalla.agentio.data.model

import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OpenDataContractTest {
    private val universityContract = Path.of("src/test/resources/domainmodel/university/university.odcs.yaml")

    /** The university model as one ODCS document; see `domainmodel/university/Manifest.md`. */
    @Nested
    inner class UniversityContract {
        private val model = DomainModel.fromOdcs(listOf(universityContract))
        private val section = model.tables.getValue(TableName("section"))
        private val student = model.tables.getValue(TableName("student"))

        private fun TableDefinition.column(name: String) = columns.first { it.name.value == name }

        @Test
        fun `every schema object is a table with a primary key`() {
            assertEquals(17, model.tables.size)
            assertTrue(model.tables.values.all { it.primaryKey.isNotEmpty() })
        }

        @Test
        fun `descriptions, nullability and types come through`() {
            assertEquals("One offering of a course in a term, taught by one instructor in one room", section.description)
            assertEquals("Enrollment cap", section.column("seats").description)
            assertEquals("", section.column("course_code").description)
            assertFalse(section.column("seats").nullable, "required: true")
            assertFalse(section.column("course_code").nullable, "a primary key property is NOT NULL without required: true")
            assertTrue(section.column("faculty_id").nullable)
            assertEquals(ColumnType.TIMESTAMP, section.column("first_meeting_at").type)
            assertEquals(ColumnType.BIGINT, student.column("student_number").type, "logicalType integer with format i64")
            assertEquals(ColumnType.DATE, student.column("birth_date").type)
        }

        @Test
        fun `primary key properties are ordered by primaryKeyPosition`() {
            assertEquals(listOf("course_code", "term_code", "section_number"), section.primaryKey.map { it.value })
            assertEquals(listOf("student_id"), student.primaryKey.map { it.value })
        }

        @Test
        fun `property-level relationships then object-level relationships are the foreign keys`() {
            assertEquals(listOf("course", "term", "faculty", "room"), section.foreignKeys.map { it.referencedTable.value })
            assertEquals(listOf(ColumnName("building_id"), ColumnName("room_number")), section.foreignKeys.last().columns)
            assertEquals(listOf(listOf(ColumnName("student_number")), listOf(ColumnName("email"))), student.uniqueKeys)
        }
    }

    @Nested
    inner class Documents {
        @TempDir
        lateinit var dir: Path

        private fun contract(
            name: String,
            schema: String,
            header: String = "apiVersion: v3.2.0\nkind: DataContract\n",
        ): Path =
            dir.resolve("$name.odcs.yaml").also { it.writeText(header + "schema:\n" + schema.trimIndent().prependIndent("  ") + "\n") }

        private fun table(path: Path) =
            DomainModel
                .fromOdcs(listOf(path))
                .tables.values
                .single()

        @Test
        fun `the full example from the standard parses up to the point our model is stricter than ODCS`() {
            // The ODCS full example (docs/examples/all/full-example.odcs.yaml, v3.2.0), minus sections that are
            // not schema. It is valid ODCS but not a valid domain model: `receivers` has a MAP property.
            val file =
                contract(
                    "full",
                    """
                    - id: tbl_obj
                      name: tbl
                      physicalName: tbl_1
                      physicalType: table
                      businessName: Core Payment Metrics
                      description: Provides core payment metrics
                      authoritativeDefinitions:
                        - url: https://catalog.data.gov/dataset/air-quality
                          type: businessDefinition
                      tags: [ 'finance', 'payments']
                      dataGranularityDescription: Aggregation on columns txn_ref_dt, pmt_txn_id
                      relationships:
                        - type: foreignKey
                          from: [tbl.rcvr_id, tbl.rcvr_cntry_code]
                          to: [receivers.id, receivers.country_code]
                          customProperties:
                            - property: cardinality
                              value: "many-to-one"
                      properties:
                        - id: txn_ref_dt_prop
                          name: transaction_reference_date
                          physicalName: txn_ref_dt
                          primaryKey: false
                          primaryKeyPosition: -1
                          businessName: transaction reference date
                          logicalType: date
                          physicalType: date
                          required: false
                          description: Reference date for transaction
                          partitioned: true
                          partitionKeyPosition: 1
                          criticalDataElement: false
                          tags: [ ]
                          classification: public
                          transformSourceObjects: [table_name_1]
                          transformLogic: sel t1.txn_dt as txn_ref_dt from table_name_1 as t1
                          transformDescription: defines the logic in business terms
                          examples: ["2022-10-03"]
                          customProperties:
                            - property: anonymizationStrategy
                              value: none
                        - id: rcvr_id_prop
                          name: rcvr_id
                          primaryKey: true
                          primaryKeyPosition: 1
                          logicalType: string
                          physicalType: varchar(18)
                          required: false
                          description: A description for column rcvr_id.
                          classification: restricted
                          relationships:
                            - to: receivers.id
                              type: foreignKey
                        - id: rcvr_cntry_code_prop
                          name: rcvr_cntry_code
                          primaryKey: false
                          logicalType: string
                          physicalType: varchar(2)
                          enum:
                            - value: US
                              label: United States
                            - value: CA
                          encryptedName: rcvr_cntry_code_encrypted
                          quality:
                            - metric: nullValues
                              mustBe: 0
                      quality:
                        - metric: rowCount
                          mustBeGreaterThan: 1000000
                      customProperties:
                        - property: business-key
                          value: [txn_ref_dt, rcvr_id]
                    - id: receivers_obj
                      name: receivers
                      physicalName: receivers_master
                      physicalType: table
                      properties:
                        - id: receiver_id_prop
                          name: id
                          primaryKey: true
                          primaryKeyPosition: 1
                          logicalType: string
                          physicalType: varchar(18)
                          required: true
                          unique: true
                        - id: country_code_prop
                          name: country_code
                          primaryKey: true
                          primaryKeyPosition: 2
                          logicalType: string
                          physicalType: varchar(2)
                          required: true
                        - id: receiver_attributes_prop
                          name: receiver_attributes
                          logicalType: map
                          physicalType: "MAP<STRING, STRING>"
                          map:
                            key: { logicalType: string }
                            value: { logicalType: string }
                    """,
                    header =
                        """
                        domain: seller
                        dataProduct: my quantum
                        version: 1.1.0
                        status: active
                        id: 53581432-6c55-4ba2-a65f-72344a91553a
                        description:
                          purpose: Views built on top of the seller tables.
                        kind: DataContract
                        apiVersion: v3.2.0
                        servers:
                          - server: my-postgres
                            type: postgres
                        price:
                          priceAmount: 9.95
                        tags: [transactions]
                        customProperties:
                          - property: refRulesetName
                            value: gcsc.ruleset.name

                        """.trimIndent(),
                )
            val e = assertThrows<IllegalArgumentException> { DomainModel.fromOdcs(listOf(file)) }
            assertEquals(
                "$file schema object 'receivers' property 'receiver_attributes': logicalType 'map' has no column mapping",
                e.message,
            )
        }

        @Test
        fun `the full example parses once the unmappable property is removed, with physical names and logical types`() {
            val file =
                contract(
                    "full",
                    """
                    - id: tbl_obj
                      name: tbl
                      physicalName: tbl_1
                      physicalType: table
                      relationships:
                        - type: foreignKey
                          from: [tbl.rcvr_id, tbl.rcvr_cntry_code]
                          to: [receivers.id, receivers.country_code]
                      properties:
                        - name: transaction_reference_date
                          physicalName: txn_ref_dt
                          logicalType: date
                          physicalType: date
                        - name: rcvr_id
                          primaryKey: true
                          logicalType: string
                          physicalType: varchar(18)
                          required: false
                          relationships:
                            - to: receivers.id
                        - name: rcvr_cntry_code
                          logicalType: string
                          physicalType: varchar(2)
                    - id: receivers_obj
                      name: receivers
                      physicalName: receivers_master
                      properties:
                        - name: id
                          primaryKey: true
                          primaryKeyPosition: 1
                          logicalType: string
                          physicalType: varchar(18)
                          unique: true
                        - name: country_code
                          primaryKey: true
                          primaryKeyPosition: 2
                          logicalType: string
                          physicalType: varchar(2)
                    """,
                )
            val model = DomainModel.fromOdcs(listOf(file))
            assertEquals(
                setOf("tbl_1", "receivers_master"),
                model.tables.keys
                    .map { it.value }
                    .toSet(),
                "physicalName names the table",
            )
            val tbl = model.tables.getValue(TableName("tbl_1"))
            assertEquals(
                listOf("txn_ref_dt", "rcvr_id", "rcvr_cntry_code"),
                tbl.columns.map { it.name.value },
                "physicalName names the column",
            )
            assertEquals(ColumnType.DATE, tbl.columns[0].type)
            assertEquals(ColumnType.VARCHAR, tbl.columns[1].type, "logicalType string; physicalType varchar(18) is not read")
            assertFalse(tbl.columns[1].nullable, "primary key overrides required: false")
            assertEquals(
                listOf(
                    ForeignKey(listOf(ColumnName("rcvr_id")), TableName("receivers_master"), listOf(ColumnName("id"))),
                    ForeignKey(
                        listOf(ColumnName("rcvr_id"), ColumnName("rcvr_cntry_code")),
                        TableName("receivers_master"),
                        listOf(ColumnName("id"), ColumnName("country_code")),
                    ),
                ),
                tbl.foreignKeys,
                "shorthand references resolve by name and map to physical names",
            )
        }

        @Test
        fun `fully qualified references resolve by id`() {
            val file =
                contract(
                    "ids",
                    """
                    - id: campus_tbl
                      name: Campus
                      properties:
                        - id: campus_pk
                          name: CampusId
                          logicalType: string
                          primaryKey: true
                    - id: building_tbl
                      name: Building
                      properties:
                        - id: building_pk
                          name: BuildingId
                          logicalType: string
                          primaryKey: true
                        - id: building_campus
                          name: CampusId
                          logicalType: string
                          relationships:
                            - to: schema/campus_tbl/properties/campus_pk
                    """,
                )
            val model = DomainModel.fromOdcs(listOf(file))
            assertEquals(
                setOf("campus", "building"),
                model.tables.keys
                    .map { it.value }
                    .toSet(),
                "names are lower-cased",
            )
            assertEquals(
                ForeignKey(listOf(ColumnName("campusid")), TableName("campus"), listOf(ColumnName("campusid"))),
                model.tables
                    .getValue(TableName("building"))
                    .foreignKeys
                    .single(),
            )
        }

        @Test
        fun `schema objects may be spread over several files and reference each other`() {
            val campus =
                contract(
                    "campus",
                    "- name: campus\n  properties:\n    - name: campus_id\n      logicalType: string\n      primaryKey: true",
                )
            val building =
                contract(
                    "building",
                    """
                    - name: building
                      properties:
                        - name: building_id
                          logicalType: string
                          primaryKey: true
                        - name: campus_id
                          logicalType: string
                          relationships:
                            - to: campus.campus_id
                    """,
                )
            assertEquals(listOf("campus", "building"), DomainModel.fromOdcs(listOf(building, campus)).creationOrder.map { it.name.value })
        }

        @Test
        fun `logical types map to column types, integer format selects BIGINT, and physicalType is not read`() {
            val file =
                contract(
                    "types",
                    """
                    - name: t
                      properties:
                        - { name: s, logicalType: string }
                        - { name: i, logicalType: integer }
                        - { name: i64, logicalType: integer, logicalTypeOptions: { format: i64 } }
                        - { name: u32, logicalType: integer, logicalTypeOptions: { format: u32 } }
                        - { name: i16, logicalType: integer, logicalTypeOptions: { format: i16, minimum: 0 } }
                        - { name: n, logicalType: number }
                        - { name: b, logicalType: boolean }
                        - { name: d, logicalType: date }
                        - { name: ts, logicalType: timestamp, logicalTypeOptions: { timezone: true } }
                        - { name: tm, logicalType: time, logicalTypeOptions: { format: "HH:mm:ss" } }
                        - { name: p, logicalType: string, physicalType: "DECIMAL(10, 2)" }
                        - { name: pg_serial, logicalType: integer, physicalType: serial }
                        - { name: pg_array, logicalType: string, physicalType: "varchar[50]" }
                        - { name: pg_domain, logicalType: boolean, physicalType: Flag }
                    """,
                )
            assertEquals(
                listOf(
                    ColumnType.VARCHAR,
                    ColumnType.INTEGER,
                    ColumnType.BIGINT,
                    ColumnType.BIGINT,
                    ColumnType.INTEGER,
                    ColumnType.DOUBLE,
                    ColumnType.BOOLEAN,
                    ColumnType.DATE,
                    ColumnType.TIMESTAMP,
                    ColumnType.TIME,
                    ColumnType.VARCHAR,
                    ColumnType.INTEGER,
                    ColumnType.VARCHAR,
                    ColumnType.BOOLEAN,
                ),
                table(file).columns.map { it.type },
            )
            assertTrue(table(file).columns.all { it.nullable }, "required defaults to false")
        }

        @Test
        fun `enum entries become a OneOf constraint, typed by the column, with their metadata ignored`() {
            val file =
                contract(
                    "enums",
                    """
                    - name: t
                      properties:
                        - name: status
                          logicalType: string
                          enum:
                            - value: OPEN
                              label: Open
                              description: Still being worked on
                              tags: [active]
                              id: open
                              customProperties: [{ property: colour, value: green }]
                            - value: CLOSED
                        - name: method
                          logicalType: string
                          enum: [{ value: CARD }, { value: CHEQUE }]
                        - name: level
                          logicalType: integer
                          enum: [{ value: 100 }, { value: 200 }]
                        - name: weight
                          logicalType: number
                          enum: [{ value: 0.5 }, { value: 1 }, { value: 1.5 }]
                        - name: flag
                          logicalType: boolean
                          enum: [{ value: true }]
                        - name: day
                          logicalType: date
                          enum: [{ value: "2026-01-01" }, { value: "2026-12-25" }]
                    """,
                )
            val columns = table(file).columns.associate { it.name.value to it.constraints.single() }
            assertEquals(ValueConstraint.OneOf(listOf(DataValue.StringValue("OPEN"), DataValue.StringValue("CLOSED"))), columns["status"])
            assertEquals(ValueConstraint.OneOf(listOf(DataValue.StringValue("CARD"), DataValue.StringValue("CHEQUE"))), columns["method"])
            assertEquals(ValueConstraint.OneOf(listOf(DataValue.LongValue(100), DataValue.LongValue(200))), columns["level"])
            assertEquals(
                ValueConstraint.OneOf(listOf(DataValue.DoubleValue(0.5), DataValue.DoubleValue(1.0), DataValue.DoubleValue(1.5))),
                columns["weight"],
                "a number column reads every value as a double",
            )
            assertEquals(ValueConstraint.OneOf(listOf(DataValue.BooleanValue(true))), columns["flag"])
            assertEquals(
                ValueConstraint.OneOf(
                    listOf(DataValue.TimestampValue("2026-01-01T00:00:00Z"), DataValue.TimestampValue("2026-12-25T00:00:00Z")),
                ),
                columns["day"],
            )
        }

        @Test
        fun `logicalTypeOptions bounds become Minimum, Maximum, MinLength, MaxLength and Pattern`() {
            val file =
                contract(
                    "bounds",
                    """
                    - name: t
                      properties:
                        - name: credits
                          logicalType: integer
                          logicalTypeOptions: { minimum: 1, maximum: 30, format: i32 }
                        - name: amount
                          logicalType: number
                          logicalTypeOptions: { exclusiveMinimum: 0, exclusiveMaximum: 1000000.5, multipleOf: 0.01 }
                        - name: code
                          logicalType: string
                          logicalTypeOptions: { minLength: 2, maxLength: 4, pattern: '^[A-Z]+$', format: custom }
                        - name: starts_on
                          logicalType: date
                          logicalTypeOptions: { minimum: "1990-01-01", format: "yyyy-MM-dd" }
                        - name: created_at
                          logicalType: timestamp
                          logicalTypeOptions: { maximum: "2100-01-01T00:00:00Z", timezone: true, defaultTimezone: UTC }
                        - name: local_at
                          logicalType: timestamp
                          logicalTypeOptions: { minimum: "2000-01-01T08:30:00" }
                        - name: offset_at
                          logicalType: timestamp
                          logicalTypeOptions: { minimum: "2000-01-01T08:30:00+02:00" }
                    """,
                )
            val columns = table(file).columns.associate { it.name.value to it.constraints }
            assertEquals(
                listOf(ValueConstraint.Minimum(DataValue.LongValue(1)), ValueConstraint.Maximum(DataValue.LongValue(30))),
                columns["credits"],
            )
            assertEquals(
                listOf(
                    ValueConstraint.Minimum(DataValue.DoubleValue(0.0), exclusive = true),
                    ValueConstraint.Maximum(DataValue.DoubleValue(1000000.5), exclusive = true),
                ),
                columns["amount"],
                "multipleOf is accepted and not enforced",
            )
            assertEquals(
                listOf(ValueConstraint.MinLength(2), ValueConstraint.MaxLength(4), ValueConstraint.Pattern("^[A-Z]+$")),
                columns["code"],
            )
            assertEquals(listOf(ValueConstraint.Minimum(DataValue.TimestampValue("1990-01-01T00:00:00Z"))), columns["starts_on"])
            assertEquals(listOf(ValueConstraint.Maximum(DataValue.TimestampValue("2100-01-01T00:00:00Z"))), columns["created_at"])
            assertEquals(
                listOf(ValueConstraint.Minimum(DataValue.TimestampValue("2000-01-01T08:30:00Z"))),
                columns["local_at"],
                "a local date-time is read as UTC",
            )
            assertEquals(
                listOf(ValueConstraint.Minimum(DataValue.TimestampValue("2000-01-01T06:30:00Z"))),
                columns["offset_at"],
                "an offset is honoured",
            )
        }

        @Test
        fun `unique on the only primary key property is accepted as the no-op it is, elsewhere it is a key`() {
            val file =
                contract(
                    "u",
                    """
                    - name: campus
                      properties:
                        - { name: campus_id, logicalType: string, primaryKey: true, unique: true }
                        - { name: code, logicalType: string, unique: true }
                    - name: receivers
                      properties:
                        - { name: id, logicalType: string, primaryKey: true, primaryKeyPosition: 1, unique: true }
                        - { name: country_code, logicalType: string, primaryKey: true, primaryKeyPosition: 2 }
                    """,
                )
            val model = DomainModel.fromOdcs(listOf(file))
            assertEquals(listOf(listOf(ColumnName("code"))), model.tables.getValue(TableName("campus")).uniqueKeys)
            assertEquals(
                listOf(listOf(ColumnName("id"))),
                model.tables.getValue(TableName("receivers")).uniqueKeys,
                "stronger than the composite key, as in the standard's example",
            )
        }

        @Test
        fun `an explicit null is the same as an absent key`() {
            val file =
                contract(
                    "nulls",
                    "- name: t\n  description: null\n  properties:\n    - name: a\n      logicalType: string\n      required: null\n      description: null",
                )
            val t = table(file)
            assertEquals("", t.description)
            assertTrue(t.columns.single().nullable)
        }
    }

    @Nested
    inner class Rejections {
        @TempDir
        lateinit var dir: Path

        private fun contract(
            name: String,
            schema: String,
            header: String = "apiVersion: v3.2.0\nkind: DataContract\n",
        ): Path =
            dir.resolve("$name.odcs.yaml").also { it.writeText(header + "schema:\n" + schema.trimIndent().prependIndent("  ") + "\n") }

        private fun message(path: Path): String =
            assertThrows<IllegalArgumentException> { DomainModel.fromOdcs(listOf(path)) }.message.orEmpty()

        private val oneTable = "- name: t\n  properties:\n    - name: id\n      logicalType: string\n      primaryKey: true"

        @Test
        fun `no files, and files without schema objects`() {
            assertEquals("No ODCS files given", assertThrows<IllegalArgumentException> { DomainModel.fromOdcs(emptyList()) }.message)
            val empty = contract("empty", "")
            assertEquals("No schema objects found in ODCS files: [$empty]", message(empty))
        }

        @Test
        fun `malformed YAML names the file and position`() {
            val file = dir.resolve("bad.yaml").also { it.writeText("schema:\n  - name: t\n   properties: [\n") }
            assertTrue(message(file).startsWith("Invalid YAML in $file at line "), message(file))
        }

        @Test
        fun `a document without a schema section, the wrong kind, or an old apiVersion`() {
            val noSchema = dir.resolve("noschema.yaml").also { it.writeText("kind: DataContract\napiVersion: v3.2.0\nname: x\n") }
            assertEquals("$noSchema: an ODCS document needs a 'schema' section", message(noSchema))
            val wrongKind = contract("kind", oneTable, header = "kind: DataProduct\n")
            assertEquals("$wrongKind: kind must be DataContract, found 'DataProduct'", message(wrongKind))
            val v2 = contract("v2", oneTable, header = "apiVersion: v2.2.2\n")
            assertEquals("$v2: apiVersion must be v3.x, found 'v2.2.2'", message(v2))
        }

        @Test
        fun `an unknown key on an object or property is an error, so a misspelt key cannot drop a constraint`() {
            val obj = contract("obj", "- name: t\n  descripton: typo\n  properties:\n    - name: id\n      logicalType: string")
            assertTrue(message(obj).startsWith("$obj schema object 't': unknown key [descripton]; ODCS allows ["), message(obj))
            val prop = contract("prop", "- name: t\n  properties:\n    - name: id\n      logicalType: string\n      primarykey: true")
            assertTrue(message(prop).startsWith("$prop schema object 't' property 'id': unknown key [primarykey]"), message(prop))
            val rel =
                contract(
                    "rel",
                    "- name: t\n  properties:\n    - name: id\n      logicalType: string\n      relationships:\n        - to: t.id\n          cardinality: one",
                )
            assertTrue(message(rel).contains("relationships[0]: unknown key [cardinality]"), message(rel))
        }

        @Test
        fun `a property needs a type, and unmappable or unknown types are named`() {
            assertEquals(
                "${dir.resolve("none.odcs.yaml")} schema object 't' property 'a': a property needs logicalType; " +
                    "physicalType is the source system's type and does not decide the column type",
                message(contract("none", "- name: t\n  properties:\n    - name: a")),
            )
            assertTrue(
                message(
                    contract(
                        "arr",
                        "- name: t\n  properties:\n    - name: a\n      logicalType: array\n      items: { logicalType: string }",
                    ),
                ).endsWith("property 'a': logicalType 'array' has no column mapping"),
            )
            assertTrue(
                message(
                    contract(
                        "timebound",
                        "- name: t\n  properties:\n    - name: a\n      logicalType: time\n      enum: [{ value: \"07:00:00\" }]",
                    ),
                ).contains("Column 'a' of type TIME cannot have the constraint OneOf"),
                "a time of day takes no value constraint",
            )
            assertTrue(
                message(
                    contract("unk", "- name: t\n  properties:\n    - name: a\n      logicalType: text"),
                ).contains(
                    "unknown logicalType 'text'; ODCS logical types are [array, boolean, date, integer, map, number, object, string, time, timestamp, vector]",
                ),
            )
            assertTrue(
                message(
                    contract(
                        "fmt",
                        "- name: t\n  properties:\n    - name: a\n      logicalType: integer\n      logicalTypeOptions: { format: i128 }",
                    ),
                ).contains("integer format 'i128' has no column mapping"),
            )
            assertTrue(
                message(
                    contract("phys", "- name: t\n  properties:\n    - name: a\n      physicalType: INTEGER"),
                ).contains("property 'a': a property needs logicalType"),
                "a physicalType alone does not type a column, even when it names a DuckDB type",
            )
        }

        @Test
        fun `enum and bound values must fit the column type, with the location named`() {
            val prop = "- name: t\n  properties:\n    - name: a\n      logicalType: %s\n      %s"
            // logicalType, property text, expected message (a suffix, or a substring when it starts with "~")
            val cases =
                listOf(
                    Triple(
                        "integer",
                        "enum: [{ value: 100 }, { value: two }]",
                        "property 'a' enum[1] value: expected an integer for a INTEGER column, found 'two'",
                    ),
                    Triple("string", "enum: [CARD, CHEQUE]", "~enum[0]: an enum entry is an object with 'value', found a scalar at line"),
                    Triple(
                        "boolean",
                        "enum: [{ value: yes }]",
                        "property 'a' enum[0] value: expected true or false for a BOOLEAN column, found 'yes'",
                    ),
                    Triple(
                        "date",
                        "enum: [{ value: tomorrow }]",
                        "property 'a' enum[0] value: expected an ISO-8601 date or timestamp for a DATE column, found 'tomorrow'",
                    ),
                    Triple("string", "enum: [{ value: A }, { value: A }]", "property 'a' enum: OneOf values must be distinct: ['A', 'A']"),
                    Triple("string", "enum: []", "property 'a' enum: OneOf needs at least one value"),
                    Triple("string", "enum: [{ label: no value }]", "property 'a' enum[0]: an enum entry needs 'value'"),
                    Triple("string", "enum: [{ value: A, colour: red }]", "~enum[0]: unknown key [colour]"),
                    Triple(
                        "number",
                        "logicalTypeOptions: { minimum: low }",
                        "logicalTypeOptions minimum: expected a number for a DOUBLE column, found 'low'",
                    ),
                    Triple(
                        "string",
                        "logicalTypeOptions: { minimum: 1 }",
                        "~Column 'a' of type VARCHAR cannot have the constraint Minimum",
                    ),
                    Triple(
                        "integer",
                        "logicalTypeOptions: { pattern: '^x$' }",
                        "~Column 'a' of type INTEGER cannot have the constraint Pattern",
                    ),
                    Triple("integer", "logicalTypeOptions: { minimum: 5, maximum: 4 }", "~Column 'a' has a minimum above its maximum"),
                    Triple("string", "logicalTypeOptions: { pattern: 'a(?=b)' }", "~is not a valid RE2 regular expression"),
                    Triple("string", "logicalTypeOptions: { minLength: -1 }", "logicalTypeOptions: MinLength cannot be negative: -1"),
                    Triple(
                        "string",
                        "logicalTypeOptions: { maxItems: 3 }",
                        "logicalTypeOptions: [maxItems] apply to array, object or vector properties, which have no column mapping",
                    ),
                    Triple("string", "logicalTypeOptions: { minimun: 1 }", "~logicalTypeOptions: unknown key [minimun]"),
                )
            cases.forEachIndexed { i, (logicalType, text, expected) ->
                val actual = message(contract("case$i", prop.format(logicalType, text)))
                val ok = if (expected.startsWith("~")) actual.contains(expected.drop(1)) else actual.endsWith(expected)
                assertTrue(ok, "case $i ($text): expected '$expected' in: $actual")
            }
        }

        @Test
        fun `composite primary keys need distinct positions`() {
            val noPositions =
                contract(
                    "nopos",
                    "- name: t\n  properties:\n    - { name: a, logicalType: string, primaryKey: true }\n    - { name: b, logicalType: string, primaryKey: true }",
                )
            assertTrue(
                message(
                    noPositions,
                ).endsWith(
                    "schema object 't': a composite primary key needs a primaryKeyPosition on every key property; missing on [a, b]",
                ),
            )
            val samePosition =
                contract(
                    "same",
                    "- name: t\n  properties:\n    - { name: a, logicalType: string, primaryKey: true, primaryKeyPosition: 1 }\n    - { name: b, logicalType: string, primaryKey: true, primaryKeyPosition: 1 }",
                )
            assertTrue(message(samePosition).endsWith("schema object 't': primary key properties share a primaryKeyPosition: {1=[a, b]}"))
            val notKey = contract("notkey", "- name: t\n  properties:\n    - { name: a, logicalType: string, primaryKeyPosition: 1 }")
            assertTrue(message(notKey).endsWith("property 'a': primaryKeyPosition is set but primaryKey is not true"))
            val zero =
                contract(
                    "zero",
                    "- name: t\n  properties:\n    - { name: a, logicalType: string, primaryKey: true, primaryKeyPosition: 0 }",
                )
            assertTrue(message(zero).endsWith("property 'a': primaryKeyPosition must be 1 or more, found 0"))
        }

        @Test
        fun `relationships are checked for shape`() {
            val base =
                "- name: t\n  properties:\n    - name: id\n      logicalType: string\n      primaryKey: true\n" +
                    "    - name: p\n      logicalType: string\n"
            assertTrue(
                message(
                    contract("from", "$base      relationships:\n        - from: t.p\n          to: t.id"),
                ).endsWith("'from' is implicit at property level and must not be given"),
            )
            assertTrue(
                message(contract("noto", "$base      relationships:\n        - type: foreignKey")).endsWith("a relationship needs 'to'"),
            )
            assertTrue(
                message(
                    contract("list", "$base      relationships:\n        - to: [t.id]"),
                ).endsWith("'to' must be a single reference at property level; composite keys are object-level relationships"),
            )
            assertTrue(
                message(
                    contract("type", "$base      relationships:\n        - to: t.id\n          type: lineage"),
                ).endsWith("relationship type must be foreignKey, found 'lineage'"),
            )
            assertTrue(
                message(
                    contract("nofrom", "$base  relationships:\n    - to: t.id"),
                ).endsWith("an object-level relationship needs 'from'"),
            )
            assertTrue(
                message(
                    contract("shape", "$base  relationships:\n    - from: t.p\n      to: [t.id]"),
                ).endsWith("'from' and 'to' must both be single references or both be lists"),
            )
            assertTrue(
                message(
                    contract("len", "$base  relationships:\n    - from: [t.p, t.id]\n      to: [t.id]"),
                ).endsWith("'from' has 2 references but 'to' has 1"),
            )
            assertTrue(
                message(
                    contract("ext", "$base      relationships:\n        - to: other.yaml#/schema/x/properties/y"),
                ).contains("points into another contract, which is not supported"),
            )
            assertTrue(
                message(
                    contract("nested", "$base      relationships:\n        - to: t.address.street"),
                ).contains("is neither shorthand 'object.property' nor fully qualified"),
            )
            assertTrue(
                message(
                    contract("noobj", "$base      relationships:\n        - to: nope.id"),
                ).endsWith("reference 'nope.id' names no schema object 'nope'"),
            )
            assertTrue(
                message(
                    contract("noprop", "$base      relationships:\n        - to: t.nope"),
                ).endsWith("reference 't.nope' names no property 'nope' in 't'"),
            )
            assertTrue(
                message(
                    contract("noid", "$base      relationships:\n        - to: schema/t_id/properties/x"),
                ).endsWith("names no schema object with id 't_id'"),
            )
        }

        @Test
        fun `an object-level relationship must start at the object's own properties and end in one object`() {
            val two =
                contract(
                    "two",
                    """
                    - name: a
                      properties:
                        - { name: id, logicalType: string, primaryKey: true }
                    - name: b
                      properties:
                        - { name: id, logicalType: string, primaryKey: true }
                        - { name: x, logicalType: string }
                        - { name: y, logicalType: string }
                      relationships:
                        - from: [b.x, b.y]
                          to: [a.id, b.id]
                    """,
                )
            assertTrue(message(two).endsWith("'to' must name properties of one schema object, found [a, b]"), message(two))
            val foreignFrom =
                contract(
                    "ff",
                    """
                    - name: a
                      properties:
                        - { name: id, logicalType: string, primaryKey: true }
                    - name: b
                      properties:
                        - { name: id, logicalType: string, primaryKey: true }
                      relationships:
                        - from: a.id
                          to: b.id
                    """,
                )
            assertTrue(message(foreignFrom).endsWith("'from' must name properties of 'b', found 'a.id'"), message(foreignFrom))
        }

        @Test
        fun `a relationship declared twice, or from a property to itself, is rejected with the model's reason`() {
            val twice =
                contract(
                    "twice",
                    """
                    - name: a
                      properties:
                        - { name: id, logicalType: string, primaryKey: true }
                    - name: b
                      properties:
                        - { name: id, logicalType: string, primaryKey: true }
                        - { name: a_id, logicalType: string, relationships: [{ to: a.id }, { to: a.id }] }
                    """,
                )
            assertTrue(
                message(twice).endsWith("schema object 'b': Table 'b' declares the same foreign key twice: [(a_id) -> a (id)]"),
                message(twice),
            )
            val self =
                contract(
                    "self",
                    "- name: t\n  properties:\n    - { name: id, logicalType: string, primaryKey: true, relationships: [{ to: t.id }] }",
                )
            assertTrue(
                message(self).endsWith("schema object 't': Table 't' has a foreign key (id) that references its own columns"),
                message(self),
            )
        }

        @Test
        fun `ambiguous names or ids are rejected before they can be referenced`() {
            val objects =
                contract(
                    "objs",
                    "- name: t\n  properties: [{ name: id, logicalType: string }]\n- name: t\n  properties: [{ name: id, logicalType: string }]",
                )
            assertEquals("more than one schema object has the name [t]", message(objects))
            val ids =
                contract(
                    "ids",
                    "- id: same\n  name: a\n  properties: [{ name: id, logicalType: string }]\n- id: same\n  name: b\n  properties: [{ name: id, logicalType: string }]",
                )
            assertEquals("more than one schema object has the id [same]", message(ids))
            val props =
                contract(
                    "props",
                    "- name: t\n  properties:\n    - { id: p, name: a, logicalType: string }\n    - { id: p, name: b, logicalType: string }",
                )
            assertTrue(message(props).endsWith("schema object 't': more than one property has the id [p]"))
        }

        @Test
        fun `model rules apply with the contract's location prefixed`() {
            val notAKey =
                contract(
                    "nk",
                    """
                    - name: room
                      properties:
                        - { name: building_id, logicalType: string, primaryKey: true, primaryKeyPosition: 1 }
                        - { name: room_number, logicalType: string, primaryKey: true, primaryKeyPosition: 2 }
                    - name: section
                      properties:
                        - { name: id, logicalType: string, primaryKey: true }
                        - name: building_id
                          logicalType: string
                          relationships: [{ to: room.building_id }]
                    """,
                )
            assertEquals(
                "Table 'section' has a foreign key to 'room' (building_id), " +
                    "which is not its primary key or a unique key in that column order; " +
                    "its keys are [(building_id, room_number)]",
                message(notAKey),
            )
            val badName = contract("name", "- name: 1st\n  properties: [{ name: id, logicalType: string }]")
            assertTrue(message(badName).endsWith("schema object '1st': Invalid table name: '1st'"))
            val dup =
                contract(
                    "dup",
                    "- name: t\n  properties:\n    - { name: Id, logicalType: string }\n    - { name: id, logicalType: string }",
                )
            assertTrue(message(dup).endsWith("schema object 't': Table 't' has duplicate columns: [id]"))
        }
    }
}
