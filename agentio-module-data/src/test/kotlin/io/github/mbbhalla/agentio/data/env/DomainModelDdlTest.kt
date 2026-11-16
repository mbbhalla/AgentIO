package io.github.mbbhalla.agentio.data.env

import io.github.mbbhalla.agentio.data.model.ColumnDefinition
import io.github.mbbhalla.agentio.data.model.ColumnName
import io.github.mbbhalla.agentio.data.model.ColumnType
import io.github.mbbhalla.agentio.data.model.DataValue
import io.github.mbbhalla.agentio.data.model.ForeignKey
import io.github.mbbhalla.agentio.data.model.TableDefinition
import io.github.mbbhalla.agentio.data.model.TableName
import io.github.mbbhalla.agentio.data.model.ValueConstraint
import kotlin.test.Test
import kotlin.test.assertEquals

class DomainModelDdlTest {
    private fun column(
        name: String,
        type: ColumnType = ColumnType.VARCHAR,
        nullable: Boolean = false,
        description: String = "",
    ) = ColumnDefinition(ColumnName(name), type, nullable, description)

    private val section =
        TableDefinition(
            name = TableName("section"),
            description = "One offering of a course; it's taught in one room",
            columns =
                listOf(
                    column("id", description = "Identity"),
                    column("course_code"),
                    column("order", ColumnType.INTEGER, nullable = true, description = "Sort order"),
                    column("line", ColumnType.INTEGER, nullable = true),
                    column("first_meeting", ColumnType.DATE, nullable = true),
                ),
            primaryKey = listOf(ColumnName("id")),
            uniqueKeys = listOf(listOf(ColumnName("order"), ColumnName("line"))),
            foreignKeys = listOf(ForeignKey(listOf(ColumnName("course_code")), TableName("course"), listOf(ColumnName("course_code")))),
        )

    @Test
    fun `create table declares columns, nullability and every key inline with quoted identifiers`() {
        assertEquals(
            """
            CREATE TABLE "section" (
              "id" VARCHAR NOT NULL,
              "course_code" VARCHAR NOT NULL,
              "order" INTEGER,
              "line" INTEGER,
              "first_meeting" DATE,
              PRIMARY KEY ("id"),
              UNIQUE ("order", "line"),
              FOREIGN KEY ("course_code") REFERENCES "course" ("course_code")
            )
            """.trimIndent(),
            DomainModelDdl.createTable(section),
        )
    }

    @Test
    fun `every column type renders as its DuckDB type name`() {
        val table = TableDefinition(TableName("types"), "", ColumnType.entries.map { column(it.name.lowercase(), it, nullable = true) })
        val expected = ColumnType.entries.joinToString(",\n  ") { "\"${it.name.lowercase()}\" ${it.name}" }
        assertEquals("CREATE TABLE \"types\" (\n  $expected\n)", DomainModelDdl.createTable(table))
    }

    @Test
    fun `composite, self-referencing and repeated keys render in declaration order`() {
        val enrollment =
            TableDefinition(
                name = TableName("enrollment"),
                description = "",
                columns =
                    listOf(
                        column("student_id"),
                        column("course_code"),
                        column("term_code"),
                        column("section_number", ColumnType.INTEGER),
                        column("parent_id", nullable = true),
                        column("tag", nullable = true),
                    ),
                primaryKey =
                    listOf(
                        ColumnName("student_id"),
                        ColumnName("course_code"),
                        ColumnName("term_code"),
                        ColumnName("section_number"),
                    ),
                uniqueKeys = listOf(listOf(ColumnName("tag")), listOf(ColumnName("student_id"), ColumnName("parent_id"))),
                foreignKeys =
                    listOf(
                        ForeignKey(listOf(ColumnName("student_id")), TableName("student"), listOf(ColumnName("student_id"))),
                        ForeignKey(
                            listOf(ColumnName("course_code"), ColumnName("term_code"), ColumnName("section_number")),
                            TableName("section"),
                            listOf(ColumnName("course_code"), ColumnName("term_code"), ColumnName("section_number")),
                        ),
                        ForeignKey(listOf(ColumnName("parent_id")), TableName("enrollment"), listOf(ColumnName("student_id"))),
                    ),
            )
        val ddl = DomainModelDdl.createTable(enrollment)
        val clauses =
            ddl
                .lines()
                .drop(1)
                .dropLast(1)
                .map { it.trim().removeSuffix(",") }
        assertEquals(
            listOf(
                "\"student_id\" VARCHAR NOT NULL",
                "\"course_code\" VARCHAR NOT NULL",
                "\"term_code\" VARCHAR NOT NULL",
                "\"section_number\" INTEGER NOT NULL",
                "\"parent_id\" VARCHAR",
                "\"tag\" VARCHAR",
                "PRIMARY KEY (\"student_id\", \"course_code\", \"term_code\", \"section_number\")",
                "UNIQUE (\"tag\")",
                "UNIQUE (\"student_id\", \"parent_id\")",
                "FOREIGN KEY (\"student_id\") REFERENCES \"student\" (\"student_id\")",
                "FOREIGN KEY (\"course_code\", \"term_code\", \"section_number\") REFERENCES \"section\" (\"course_code\", \"term_code\", \"section_number\")",
                "FOREIGN KEY (\"parent_id\") REFERENCES \"enrollment\" (\"student_id\")",
            ),
            clauses,
        )
    }

    @Test
    fun `value constraints render as CHECK clauses after the keys, one per constraint, in column order`() {
        val table =
            TableDefinition(
                name = TableName("t"),
                description = "",
                columns =
                    listOf(
                        column("id"),
                        ColumnDefinition(
                            ColumnName("code"),
                            ColumnType.VARCHAR,
                            nullable = true,
                            description = "",
                            constraints =
                                listOf(
                                    ValueConstraint.OneOf(listOf(DataValue.StringValue("IT'S"), DataValue.StringValue("A\\B"))),
                                    ValueConstraint.MinLength(2),
                                    ValueConstraint.MaxLength(4),
                                    ValueConstraint.Pattern("^[A-Z'\\\\]+$"),
                                ),
                        ),
                        ColumnDefinition(
                            ColumnName("score"),
                            ColumnType.DOUBLE,
                            nullable = true,
                            description = "",
                            constraints =
                                listOf(
                                    ValueConstraint.Minimum(DataValue.LongValue(0)),
                                    ValueConstraint.Maximum(DataValue.DoubleValue(4.0), exclusive = true),
                                ),
                        ),
                        ColumnDefinition(
                            ColumnName("day"),
                            ColumnType.DATE,
                            nullable = true,
                            description = "",
                            constraints =
                                listOf(
                                    ValueConstraint.Minimum(DataValue.TimestampValue("1990-01-01T00:00:00Z"), exclusive = true),
                                ),
                        ),
                        ColumnDefinition(
                            ColumnName("at"),
                            ColumnType.TIMESTAMP,
                            nullable = true,
                            description = "",
                            constraints = listOf(ValueConstraint.Maximum(DataValue.TimestampValue("2100-01-01T00:00:00Z"))),
                        ),
                        ColumnDefinition(
                            ColumnName("flag"),
                            ColumnType.BOOLEAN,
                            nullable = true,
                            description = "",
                            constraints = listOf(ValueConstraint.OneOf(listOf(DataValue.BooleanValue(true)))),
                        ),
                        ColumnDefinition(
                            ColumnName("level"),
                            ColumnType.INTEGER,
                            nullable = true,
                            description = "",
                            constraints = listOf(ValueConstraint.OneOf(listOf(DataValue.LongValue(100), DataValue.LongValue(200)))),
                        ),
                    ),
                primaryKey = listOf(ColumnName("id")),
            )
        val clauses =
            DomainModelDdl
                .createTable(table)
                .lines()
                .drop(1)
                .dropLast(1)
                .map { it.trim().removeSuffix(",") }
        assertEquals(
            listOf(
                """PRIMARY KEY ("id")""",
                """CHECK ("code" IN ('IT''S', 'A\B'))""",
                """CHECK (length("code") >= 2)""",
                """CHECK (length("code") <= 4)""",
                """CHECK (regexp_matches("code", '^[A-Z''\\]+$'))""",
                """CHECK ("score" >= 0)""",
                """CHECK ("score" < 4.0)""",
                """CHECK ("day" > DATE '1990-01-01')""",
                """CHECK ("at" <= TIMESTAMP '2100-01-01 00:00:00.000000')""",
                """CHECK ("flag" IN (TRUE))""",
                """CHECK ("level" IN (100, 200))""",
            ),
            clauses.drop(7),
        )
    }

    @Test
    fun `comments cover the table and described columns only, with quotes escaped`() {
        assertEquals(
            listOf(
                """COMMENT ON TABLE "section" IS 'One offering of a course; it''s taught in one room'""",
                """COMMENT ON COLUMN "section"."id" IS 'Identity'""",
                """COMMENT ON COLUMN "section"."order" IS 'Sort order'""",
            ),
            DomainModelDdl.comments(section),
        )
    }

    @Test
    fun `comment text is a plain SQL literal, so newlines, backslashes, double quotes and unicode pass through`() {
        val tricky = "line1\nline2 \\ \"quoted\" it's é ✓"
        val table = TableDefinition(TableName("t"), tricky, listOf(column("id", description = tricky)))
        val expectedLiteral = "'line1\nline2 \\ \"quoted\" it''s é ✓'"
        assertEquals(
            listOf("""COMMENT ON TABLE "t" IS $expectedLiteral""", """COMMENT ON COLUMN "t"."id" IS $expectedLiteral"""),
            DomainModelDdl.comments(table),
        )
    }

    @Test
    fun `a table without keys or descriptions renders only its columns and no comments`() {
        val bare = TableDefinition(TableName("t"), "", listOf(column("v", ColumnType.BOOLEAN, nullable = true)))
        assertEquals("CREATE TABLE \"t\" (\n  \"v\" BOOLEAN\n)", DomainModelDdl.createTable(bare))
        assertEquals(emptyList(), DomainModelDdl.comments(bare))
    }

    @Test
    fun `blank descriptions produce no comment`() {
        val table = TableDefinition(TableName("t"), "   ", listOf(column("id", description = " \n ")))
        assertEquals(emptyList(), DomainModelDdl.comments(table))
    }
}
