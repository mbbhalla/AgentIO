package io.github.mbbhalla.agentio.data.env

import io.github.mbbhalla.agentio.data.model.ColumnDefinition
import io.github.mbbhalla.agentio.data.model.ColumnName
import io.github.mbbhalla.agentio.data.model.ColumnType
import io.github.mbbhalla.agentio.data.model.DataValue
import io.github.mbbhalla.agentio.data.model.Dataset
import io.github.mbbhalla.agentio.data.model.DomainModel
import io.github.mbbhalla.agentio.data.model.ForeignKey
import io.github.mbbhalla.agentio.data.model.ForeignKeyRef
import io.github.mbbhalla.agentio.data.model.TableDefinition
import io.github.mbbhalla.agentio.data.model.TableName
import io.github.mbbhalla.agentio.data.model.ValueConstraint
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.sql.SQLException
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.io.path.createDirectory
import kotlin.io.path.writeText
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Builds empty, constraint-enforcing environments from a [DomainModel] and checks DuckDB enforces every rule. */
class DuckDbDatabaseEnvironmentDomainModelTest {
    private val universityDirectory = Path.of("src/test/resources/domainmodel/university")
    private val universityContract = universityDirectory.resolve("university.odcs.yaml")

    private fun DuckDbDatabaseEnvironment.query(sql: String) = executeQuery(selectStatement(sql).getOrThrow())

    private fun DuckDbDatabaseEnvironment.insert(sql: String) = executeUpdate(insertStatement(sql).getOrThrow())

    private fun DuckDbDatabaseEnvironment.scalar(sql: String): DataValue =
        query(sql)
            .records
            .single()
            .values.values
            .single()

    private fun DuckDbDatabaseEnvironment.count(table: String): Long =
        (scalar("SELECT count(*) AS n FROM $table") as DataValue.LongValue).value

    /** DuckDB's own message for a statement its constraints reject at run time; the whole point of a target environment. */
    private fun DuckDbDatabaseEnvironment.rejects(sql: String): String = assertThrows<SQLException> { insert(sql) }.message.orEmpty()

    /** A statement DuckDB already refuses to bind, so [DuckDbDatabaseEnvironment.insertStatement] never yields one. */
    private fun DuckDbDatabaseEnvironment.rejectsAtValidation(sql: String): String =
        insertStatement(sql).exceptionOrNull()?.message.orEmpty()

    private fun Dataset.Record.string(column: String): String? = (this[ColumnName(column)] as? DataValue.StringValue)?.value

    private fun Dataset.Record.boolean(column: String): Boolean = (this[ColumnName(column)] as DataValue.BooleanValue).value

    private fun String.columnNames(): List<ColumnName> = split(',').map { ColumnName(it) }

    /**
     * Random value constraints a column of [type] can carry, in the forms DuckDB prints back from its
     * catalog without reformatting (whole-second instants, short decimals, non-negative numbers): an
     * enumeration, or bounds, or for strings lengths and a pattern.
     */
    private fun randomConstraints(
        random: Random,
        type: ColumnType,
    ): List<ValueConstraint> {
        fun instant(day: Int) = DataValue.TimestampValue("2026-01-%02dT00:00:00Z".format(day))

        fun timestamp(hour: Int) = DataValue.TimestampValue("2026-06-15T%02d:30:00Z".format(hour))

        fun number(n: Int): DataValue = if (type == ColumnType.DOUBLE) DataValue.DoubleValue(n / 4.0) else DataValue.LongValue(n.toLong())

        fun ordered(n: Int): DataValue =
            when (type) {
                ColumnType.DATE -> instant(n + 1)
                ColumnType.TIMESTAMP -> timestamp(n)
                else -> number(n)
            }
        val family = random.nextInt(4)
        return when {
            family == 0 -> emptyList()
            type == ColumnType.BOOLEAN -> listOf(ValueConstraint.OneOf(listOf(DataValue.BooleanValue(random.nextBoolean()))))
            type == ColumnType.VARCHAR && family == 1 ->
                listOf(
                    ValueConstraint.OneOf(
                        listOf("CLASS", "it's", "a\\b", "x, y", "é✓").shuffled(random).take(random.nextInt(1, 4)).map {
                            DataValue.StringValue(it)
                        },
                    ),
                )
            type == ColumnType.VARCHAR -> {
                val min = random.nextInt(0, 3)
                listOfNotNull(
                    ValueConstraint.MinLength(min).takeIf { random.nextBoolean() },
                    ValueConstraint.MaxLength(min + random.nextInt(0, 10)).takeIf { random.nextBoolean() },
                    ValueConstraint
                        .Pattern(
                            listOf("^[A-Z]+$", """^\\d{4}(SP|SU|FA)$""", "it's|a\\b", "(?i)^[a-z_]+$").random(random),
                        ).takeIf {
                            random.nextBoolean()
                        },
                )
            }
            family == 1 -> listOf(ValueConstraint.OneOf((1..random.nextInt(1, 4)).map { ordered(it * 2) }))
            else -> {
                val low = random.nextInt(0, 5)
                listOfNotNull(
                    ValueConstraint.Minimum(ordered(low), exclusive = random.nextBoolean()).takeIf { random.nextBoolean() },
                    ValueConstraint
                        .Maximum(
                            ordered(low + random.nextInt(2, 10)),
                            exclusive = random.nextBoolean(),
                        ).takeIf { random.nextBoolean() },
                )
            }
        }
    }

    /**
     * A random acyclic model. Table `i` may reference tables `< i` or itself; each foreign key copies
     * the types of a key of its target so the model is valid by [DomainModel]'s rules.
     */
    private fun randomModel(
        random: Random,
        tableCount: Int,
    ): DomainModel {
        val tables = mutableListOf<TableDefinition>()
        repeat(tableCount) { index ->
            val name = TableName("t$index")
            val ownColumns =
                List(random.nextInt(1, 5)) { c ->
                    val type = ColumnType.entries.random(random)
                    ColumnDefinition(
                        ColumnName("c$c"),
                        type,
                        nullable = false,
                        description = if (random.nextBoolean()) "col $c" else "",
                        constraints = randomConstraints(random, type),
                    )
                }.toMutableList()
            val primaryKey = ownColumns.take(random.nextInt(1, ownColumns.size + 1)).map { it.name }
            val uniqueKeys =
                listOf(ownColumns.shuffled(random).take(random.nextInt(1, ownColumns.size + 1)).map { it.name })
                    .filter { random.nextInt(3) == 0 && !it.containsAll(primaryKey) } // a key the primary key implies is not a key
            val foreignKeys = mutableListOf<ForeignKey>()
            val targets = (tables + TableDefinition(name, "", ownColumns, primaryKey, uniqueKeys)).filter { random.nextInt(3) == 0 }
            targets.forEach { target ->
                val key = target.keys().random(random)
                val fkColumns =
                    key.map { referenced ->
                        val type = target.columns.first { it.name == referenced }.type
                        ColumnDefinition(ColumnName("fk${foreignKeys.size}_${referenced.value}"), type, nullable = true, description = "")
                    }
                ownColumns += fkColumns
                foreignKeys += ForeignKey(fkColumns.map { it.name }, target.name, key)
            }
            tables +=
                TableDefinition(name, if (random.nextBoolean()) "table $index" else "", ownColumns, primaryKey, uniqueKeys, foreignKeys)
        }
        return DomainModel(tables.shuffled(random))
    }

    /** Rebuilds a [TableDefinition] from DuckDB's catalog, the inverse of [DomainModelDdl]. */
    private fun DuckDbDatabaseEnvironment.tableFromCatalog(table: TableName): TableDefinition {
        val name = table.value
        val description =
            query(
                "SELECT comment FROM duckdb_tables() WHERE table_name = '$name'",
            ).records.single().string("comment").orEmpty()
        val constraints =
            query(
                "SELECT constraint_type, array_to_string(constraint_column_names, ',') AS cols, referenced_table, " +
                    "array_to_string(referenced_column_names, ',') AS ref_cols, expression " +
                    "FROM duckdb_constraints() WHERE table_name = '$name' ORDER BY constraint_index",
            ).records.groupBy { it.string("constraint_type")!! }
        val checksByColumn = constraints["CHECK"].orEmpty().groupBy({ ColumnName(it.string("cols")!!) }, { it.string("expression")!! })
        val columns =
            query(
                "SELECT column_name, data_type, is_nullable, comment FROM duckdb_columns() WHERE table_name = '$name' ORDER BY column_index",
            ).records
                .map {
                    val column = ColumnName(it.string("column_name")!!)
                    val type = ColumnType.valueOf(it.string("data_type")!!)
                    ColumnDefinition(
                        name = column,
                        type = type,
                        nullable = it.boolean("is_nullable"),
                        description = it.string("comment").orEmpty(),
                        constraints = checksByColumn[column].orEmpty().map { expression -> CatalogCheck.parse(expression, column, type) },
                    )
                }
        val notNull = constraints["NOT NULL"].orEmpty().map { ColumnName(it.string("cols")!!) }.toSet()
        assertEquals(
            columns.filterNot { it.nullable }.map { it.name }.toSet(),
            notNull,
            "$name: NOT NULL constraints match non-nullable columns",
        )
        return TableDefinition(
            name = table,
            description = description,
            columns = columns,
            primaryKey =
                constraints["PRIMARY KEY"]
                    ?.single()
                    ?.string("cols")
                    ?.columnNames()
                    .orEmpty(),
            uniqueKeys = constraints["UNIQUE"].orEmpty().map { it.string("cols")!!.columnNames() },
            foreignKeys =
                constraints["FOREIGN KEY"].orEmpty().map {
                    ForeignKey(
                        it.string("cols")!!.columnNames(),
                        TableName(it.string("referenced_table")!!),
                        it.string("ref_cols")!!.columnNames(),
                    )
                },
        )
    }

    /**
     * Reads DuckDB's normalised `CHECK` expression text back into the [ValueConstraint] that produced
     * it, for example `(day_type IN ('CLASS', 'EXAM'))`, `("order" >= 1)`, `(length(code) <= 4)`,
     * `regexp_matches(code, '^[A-Z]+$')`, `(opened_on >= CAST('1800-01-01' AS DATE))`.
     */
    private object CatalogCheck {
        private const val IDENTIFIER = """(?:"[^"]+"|[a-z_][a-z0-9_]*)"""
        private val oneOf = Regex("""^\($IDENTIFIER IN \((.*)\)\)$""")
        private val bound = Regex("""^\($IDENTIFIER (>=|>|<=|<) (.*)\)$""")
        private val length = Regex("""^\(length\($IDENTIFIER\) (>=|<=) (\d+)\)$""")
        private val pattern = Regex("""^regexp_matches\($IDENTIFIER, '((?:[^']|'')*)'\)$""")
        private val cast = Regex("""^CAST\('([^']*)' AS (DATE|TIMESTAMP)\)$""")
        private val timestampFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSSSSS")

        fun parse(
            expression: String,
            column: ColumnName,
            type: ColumnType,
        ): ValueConstraint {
            require(expression.contains(column.value)) { "CHECK '$expression' does not mention ${column.value}" }
            oneOf.find(expression)?.let { return ValueConstraint.OneOf(splitLiterals(it.groupValues[1]).map(::literal)) }
            bound.find(expression)?.let { m ->
                val value = literal(m.groupValues[2])
                return when (m.groupValues[1]) {
                    ">=" -> ValueConstraint.Minimum(value)
                    ">" -> ValueConstraint.Minimum(value, exclusive = true)
                    "<=" -> ValueConstraint.Maximum(value)
                    else -> ValueConstraint.Maximum(value, exclusive = true)
                }
            }
            length.find(expression)?.let { m ->
                val n = m.groupValues[2].toInt()
                return if (m.groupValues[1] == ">=") ValueConstraint.MinLength(n) else ValueConstraint.MaxLength(n)
            }
            pattern.find(expression)?.let { return ValueConstraint.Pattern(it.groupValues[1].replace("''", "'")) }
            throw IllegalArgumentException("Unrecognised CHECK expression for $type column ${column.value}: $expression")
        }

        /** Splits `'a', 'b, c', 1` on commas outside quotes. */
        private fun splitLiterals(list: String): List<String> {
            val out = mutableListOf<String>()
            val current = StringBuilder()
            var inQuote = false
            list.forEach { ch ->
                when {
                    ch == '\'' -> {
                        inQuote = !inQuote
                        current.append(ch)
                    }
                    ch == ',' && !inQuote -> {
                        out += current.toString().trim()
                        current.clear()
                    }
                    else -> current.append(ch)
                }
            }
            if (current.isNotBlank()) out += current.toString().trim()
            return out
        }

        private fun literal(text: String): DataValue {
            cast.find(text)?.let { m ->
                val instant =
                    if (m.groupValues[2] == "DATE") {
                        LocalDate.parse(m.groupValues[1]).atStartOfDay(ZoneOffset.UTC).toInstant()
                    } else {
                        LocalDateTime.parse(m.groupValues[1], timestampFormat).toInstant(ZoneOffset.UTC)
                    }
                return DataValue.TimestampValue(instant.toString())
            }
            return when {
                text.startsWith("'") -> DataValue.StringValue(text.removeSurrounding("'").replace("''", "'"))
                text == "true" || text == "false" -> DataValue.BooleanValue(text.toBoolean())
                text.toLongOrNull() != null -> DataValue.LongValue(text.toLong())
                else -> DataValue.DoubleValue(text.toDouble())
            }
        }
    }

    /** The 17-table university model; see `university/Manifest.md`. A fresh environment per test. */
    @Nested
    inner class UniversityTargetEnvironment {
        private val env = DuckDbDatabaseEnvironment.fromOdcs(universityDirectory)

        private fun seedCampusAndDepartment() {
            env.insert("INSERT INTO campus (campus_id, code, name, is_main) VALUES ('C1', 'NTH', 'North', true)")
            env.insert("INSERT INTO department (department_id, name, campus_id) VALUES ('D1', 'Computer Science', 'C1')")
        }

        private fun seedFaculty(
            id: String,
            email: String? = null,
            mentor: String? = null,
        ) = env.insert(facultyInsert(id, email, mentor))

        private fun seedStudent(
            id: String,
            number: Long,
            email: String,
        ) = env.insert(studentInsert(id, number, email))

        @Test
        fun `every table exists and is empty`() {
            assertEquals(17, env.listTables().size)
            assertTrue(env.listTables().all { env.count(it.value) == 0L })
            assertNull(env.snapshot)
        }

        /**
         * Round trip: model → DDL → DuckDB catalog → model. Rebuilding every table from
         * `duckdb_columns()`, `duckdb_constraints()` and `duckdb_tables()` must give back exactly the
         * definition that was parsed from the contract: columns in order with type and nullability, primary key,
         * unique keys, foreign keys with their column pairs, and every description.
         */
        @Test
        fun `the DuckDB catalog is exactly the model, table by table`() {
            val model = DomainModel.fromOdcs(listOf(universityContract))
            assertEquals(model.tables.keys, env.listTables())
            model.tables.values.forEach { expected -> assertEquals(expected, env.tableFromCatalog(expected.name), expected.name.value) }
        }

        @Test
        fun `table metadata is the model's view for every table`() {
            val model = DomainModel.fromOdcs(listOf(universityContract))
            model.tables.values.forEach { table -> assertEquals(table.toTableInfo(), env.getTableInfo(table.name), table.name.value) }
        }

        @Test
        fun `environments built from one model are independent`() {
            val other = DuckDbDatabaseEnvironment.fromOdcs(universityDirectory)
            env.insert("INSERT INTO campus (campus_id, code, name, is_main) VALUES ('C1', 'NTH', 'North', true)")
            assertEquals(1L, env.count("campus"))
            assertEquals(0L, other.count("campus"))
            assertEquals(
                1,
                other.insert("INSERT INTO campus (campus_id, code, name, is_main) VALUES ('C1', 'NTH', 'North', true)"),
                "no shared key space",
            )
        }

        @Test
        fun `executeUpdate returns the number of affected rows`() {
            assertEquals(
                3,
                env.insert(
                    "INSERT INTO campus (campus_id, code, name, is_main) VALUES ('C1', 'NTH', 'North', true), ('C2', 'STH', 'South', false), ('C3', 'EST', 'East', false)",
                ),
            )
            assertEquals(2, env.executeUpdate(env.updateStatement("UPDATE campus SET city = 'Springfield' WHERE NOT is_main").getOrThrow()))
            assertEquals(0, env.executeUpdate(env.deleteStatement("DELETE FROM campus WHERE campus_id = 'none'").getOrThrow()))
            assertEquals(3, env.executeUpdate(env.deleteStatement("DELETE FROM campus").getOrThrow()))
        }

        @Test
        fun `a multi-row insert is atomic, so one bad row rejects them all`() {
            env.insert("INSERT INTO campus (campus_id, code, name, is_main) VALUES ('C1', 'NTH', 'North', true)")
            val message =
                env.rejects(
                    "INSERT INTO campus (campus_id, code, name, is_main) VALUES ('C2', 'STH', 'South', false), ('C3', 'EST', 'East', false), ('C1', 'DUP', 'Again', false)",
                )
            assertTrue(message.contains("Duplicate key \"campus_id: C1\""), message)
            assertEquals(1L, env.count("campus"), "C2 and C3 were rolled back with C1")
        }

        /**
         * The correctness-by-construction claim, checked against the real database: any [DomainModel]
         * that exists is accepted by DuckDB. Random models with random types, composite primary and
         * unique keys, foreign keys to primary keys, unique keys and the table itself, and value
         * constraints of every kind, with the catalog (keys and `CHECK` expressions) read back and
         * compared to the model each time.
         */
        @Test
        fun `every valid model is accepted by DuckDB and comes back from the catalog unchanged`() {
            val random = Random(20261116)
            repeat(80) { round ->
                val model = randomModel(random, tableCount = random.nextInt(1, 9))
                val built = DuckDbDatabaseEnvironment.fromDomainModel(model)
                assertEquals(model.tables.keys, built.listTables(), "round $round")
                model.tables.values.forEach { expected ->
                    assertEquals(expected, built.tableFromCatalog(expected.name), "round $round, ${expected.name.value}")
                }
            }
        }

        @Test
        fun `value constraints are enforced on insert and update, and NULL passes them`() {
            env.insert(
                "INSERT INTO term (term_code, name, starts_on, ends_on, is_current) VALUES ('2026FA', 'Fall 2026', DATE '2026-09-01', DATE '2026-12-20', true)",
            )
            val day = """INSERT INTO academic_calendar (term_code, "date", day_type) VALUES ('2026FA', DATE '2026-09-%s', '%s')"""
            assertEquals(1, env.insert(day.format("01", "CLASS")), "enum member accepted")
            assertTrue(env.rejects(day.format("02", "LECTURE")).contains("day_type IN ('CLASS', 'EXAM', 'HOLIDAY', 'READING')"), "enum")

            val term =
                "INSERT INTO term (term_code, name, starts_on, ends_on, is_current) VALUES ('%s', 'x', DATE '%s', DATE '2026-12-20', false)"
            assertTrue(env.rejects(term.format("26FALL", "2026-09-01")).contains("regexp_matches(term_code"), "pattern")
            assertTrue(env.rejects(term.format("1980FA", "1980-09-01")).contains("starts_on >= CAST('1990-01-01' AS DATE)"), "date minimum")

            seedCampusAndDepartment()
            val campus = "INSERT INTO campus (campus_id, code, name, is_main) VALUES ('C2', '%s', 'x', false)"
            assertTrue(
                env.rejects(campus.format("nth")).contains("regexp_matches(code"),
                "pattern on code: lower case, length within bounds",
            )
            assertTrue(env.rejects(campus.format("N")).contains("length(code) >= 2"), "minLength")
            assertTrue(env.rejects(campus.format("NORTH")).contains("length(code) <= 4"), "maxLength")
            env.insert("INSERT INTO building (building_id, campus_id, name) VALUES ('B1', 'C1', 'Turing Hall')")
            assertTrue(
                env.rejects("INSERT INTO room (building_id, room_number, capacity) VALUES ('B1', '101', 0)").contains("capacity >= 1"),
                "minimum",
            )
            assertEquals(
                1,
                env.insert("INSERT INTO room (building_id, room_number, capacity, has_projector) VALUES ('B1', '101', 40, NULL)"),
            )
            val course = "INSERT INTO course (course_code, department_id, title, credits, level) VALUES ('%s', 'D1', 'x', %d, %s)"
            assertEquals(1, env.insert(course.format("CS101", 6, "NULL")), "NULL passes an enum")
            assertTrue(env.rejects(course.format("CS102", 31, "NULL")).contains("credits <= 30"), "maximum")
            assertTrue(
                env.rejects(course.format("CS102", 6, "150")).contains("\"level\" IN (100, 200, 300, 400)"),
                "integer enum; level is a keyword DuckDB quotes",
            )
            val program =
                "INSERT INTO program (program_id, department_id, name, degree_level, credits_required) " +
                    "VALUES ('P1', 'D1', 'x', 'BACHELOR', %d)"
            assertTrue(env.rejects(program.format(0)).contains("credits_required > 0"), "exclusive minimum")
            assertEquals(1, env.insert(program.format(180)))

            seedStudent("S1", 1001, "grace@uni.example")
            assertTrue(env.rejects(studentInsert("S2", 1002, "not-an-email")).contains("regexp_matches(email"), "email pattern")
            val payment = "INSERT INTO payment (payment_id, student_id, amount, method, received_at) VALUES ('X1', 'S1', %s, '%s', now())"
            assertTrue(env.rejects(payment.format("0", "CARD")).contains("amount > 0"), "exclusive minimum on DOUBLE")
            assertTrue(
                env.rejects(payment.format("10", "CASH")).contains("IN ('CARD', 'TRANSFER', 'CHEQUE', 'SCHOLARSHIP')"),
                "enum",
            )
            assertEquals(1, env.insert(payment.format("10", "CARD")))

            assertEquals(1, env.executeUpdate(env.updateStatement("UPDATE student SET gpa = 4.0 WHERE student_id = 'S1'").getOrThrow()))
            val tooHigh =
                assertThrows<SQLException> {
                    env.executeUpdate(
                        env.updateStatement("UPDATE student SET gpa = 4.1 WHERE student_id = 'S1'").getOrThrow(),
                    )
                }
            assertTrue(tooHigh.message!!.contains("gpa <= 4"), "CHECK applies to UPDATE too")
        }

        @Test
        fun `a self-referencing key is checked per statement, so a parent and its child cannot share one insert`() {
            seedCampusAndDepartment()
            val together =
                "INSERT INTO faculty (faculty_id, department_id, given_name, family_name, hired_on, mentor_faculty_id, is_tenured) " +
                    "VALUES ('F1', 'D1', 'Ada', 'Lovelace', DATE '2020-09-01', NULL, true), ('F2', 'D1', 'Grace', 'Hopper', DATE '2021-09-01', 'F1', false)"
            assertTrue(env.rejects(together).contains("key \"faculty_id: F1\" does not exist"))
            assertEquals(0L, env.count("faculty"))
            seedFaculty("F1")
            assertEquals(1, seedFaculty("F2", mentor = "F1"), "the same rows succeed in two statements")
        }

        @Test
        fun `DuckDB holds every declared constraint`() {
            val byType =
                env
                    .query("SELECT constraint_type, count(*) AS n FROM duckdb_constraints() GROUP BY 1")
                    .records
                    .associate {
                        (it[ColumnName("constraint_type")] as DataValue.StringValue).value to
                            (it[ColumnName("n")] as DataValue.LongValue).value
                    }
            assertEquals(mapOf("PRIMARY KEY" to 17L, "FOREIGN KEY" to 24L, "UNIQUE" to 3L, "NOT NULL" to 73L, "CHECK" to 42L), byType)
        }

        @Test
        fun `descriptions are stored as comments the agent can query`() {
            assertEquals(
                42L,
                (env.scalar("SELECT count(*) AS n FROM duckdb_columns() WHERE comment IS NOT NULL") as DataValue.LongValue).value,
            )
            assertEquals(
                DataValue.StringValue(
                    "A physical campus of the university. Buildings, departments and students' home campus all point here.",
                ),
                env.scalar("SELECT comment FROM duckdb_tables() WHERE table_name = 'campus'"),
            )
            val hint = env.scalar("SELECT comment FROM duckdb_columns() WHERE table_name = 'student' AND column_name = 'home_campus_code'")
            assertTrue((hint as DataValue.StringValue).value.contains("not an enforced foreign key"), hint.value)
        }

        @Test
        fun `table metadata mirrors the model`() {
            val section = env.getTableInfo(TableName("section"))
            val byName = section.columns.associateBy { it.name.value }
            assertEquals("One offering of a course in a term, taught by one instructor in one room", section.description)
            assertTrue(byName.getValue("course_code").primaryKey)
            assertFalse(byName.getValue("course_code").nullable)
            assertEquals(ForeignKeyRef(TableName("course"), ColumnName("course_code")), byName.getValue("course_code").foreignKey)
            assertEquals(ForeignKeyRef(TableName("room"), ColumnName("building_id")), byName.getValue("building_id").foreignKey)
            assertEquals(ForeignKeyRef(TableName("room"), ColumnName("room_number")), byName.getValue("room_number").foreignKey)
            assertTrue(byName.getValue("building_id").nullable)
            assertEquals(ColumnType.INTEGER, byName.getValue("seats").type)
            assertEquals(ColumnType.TIMESTAMP, byName.getValue("first_meeting_at").type)
        }

        @Test
        fun `rows that honour the keys are accepted and rows that break them are rejected`() {
            assertEquals(1, env.insert("INSERT INTO campus (campus_id, code, name, is_main) VALUES ('C1', 'NTH', 'North', true)"))
            assertEquals(1, env.insert("INSERT INTO building (building_id, campus_id, name) VALUES ('B1', 'C1', 'Turing Hall')"))

            assertTrue(
                env
                    .rejects(
                        "INSERT INTO campus (campus_id, code, name, is_main) VALUES ('C1', 'STH', 'South', false)",
                    ).contains("Duplicate key \"campus_id: C1\""),
            )
            assertTrue(
                env
                    .rejects(
                        "INSERT INTO building (building_id, campus_id, name) VALUES ('B2', 'C-missing', 'Annex')",
                    ).contains("key \"campus_id: C-missing\" does not exist"),
            )
            assertTrue(
                env
                    .rejects(
                        "INSERT INTO building (building_id, campus_id, name) VALUES (NULL, 'C1', 'Annex')",
                    ).contains("NOT NULL constraint failed: building.building_id"),
            )
            assertTrue(
                env
                    .rejectsAtValidation(
                        "INSERT INTO building (building_id, campus_id, name, floors) VALUES ('B3', 'C1', 'Annex', 'many')",
                    ).contains("Could not convert string 'many' to INT"),
            )
            assertEquals(1L, env.count("building"), "a rejected statement inserts nothing")
        }

        @Test
        fun `unique keys are enforced`() {
            seedCampusAndDepartment()
            seedFaculty("F1", email = "ada@uni.example")
            assertTrue(
                env
                    .rejects(
                        facultyInsert("F2", email = "ada@uni.example"),
                    ).contains("Duplicate key \"email: ada@uni.example\" violates unique constraint"),
            )
            assertEquals(1, seedFaculty("F3", email = null), "nulls do not collide")
            assertEquals(1, seedFaculty("F4", email = null))

            seedStudent("S1", 1001, "grace@uni.example")
            assertTrue(
                env
                    .rejects(
                        studentInsert("S2", 1001, "other@uni.example"),
                    ).contains("Duplicate key \"student_number: 1001\" violates unique constraint"),
            )
        }

        @Test
        fun `a self-referencing foreign key is enforced across statements`() {
            seedCampusAndDepartment()
            seedFaculty("F1")
            assertEquals(1, seedFaculty("F2", mentor = "F1"))
            assertTrue(env.rejects(facultyInsert("F3", mentor = "ZZ")).contains("key \"faculty_id: ZZ\" does not exist"))
        }

        @Test
        fun `two- and three-column composite foreign keys are enforced`() {
            seedCampusAndDepartment()
            env.insert("INSERT INTO building (building_id, campus_id, name) VALUES ('B1', 'C1', 'Turing Hall')")
            env.insert("INSERT INTO room (building_id, room_number, capacity) VALUES ('B1', '101', 40)")
            env.insert("INSERT INTO course (course_code, department_id, title, credits) VALUES ('CS101', 'D1', 'Programming I', 6)")
            env.insert(
                "INSERT INTO term (term_code, name, starts_on, ends_on, is_current) VALUES ('2026FA', 'Fall 2026', DATE '2026-09-01', DATE '2026-12-20', true)",
            )

            val section =
                "INSERT INTO section (course_code, term_code, section_number, building_id, room_number, seats) " +
                    "VALUES ('CS101', '2026FA', %d, 'B1', '%s', 40)"
            assertEquals(1, env.insert(section.format(1, "101")))
            assertTrue(env.rejects(section.format(2, "999")).contains("key \"building_id: B1, room_number: 999\" does not exist"))

            seedStudent("S1", 1001, "grace@uni.example")
            val enrollment =
                "INSERT INTO enrollment (student_id, course_code, term_code, section_number, status, enrolled_on) " +
                    "VALUES ('S1', 'CS101', '2026FA', %d, 'ENROLLED', TIMESTAMP '2026-08-15 09:00:00')"
            assertEquals(1, env.insert(enrollment.format(1)))
            assertTrue(
                env
                    .rejects(
                        enrollment.format(2),
                    ).contains("key \"course_code: CS101, term_code: 2026FA, section_number: 2\" does not exist"),
            )
        }

        @Test
        fun `a column named like a keyword takes part in a composite primary key`() {
            env.insert(
                "INSERT INTO term (term_code, name, starts_on, ends_on, is_current) VALUES ('2026FA', 'Fall 2026', DATE '2026-09-01', DATE '2026-12-20', true)",
            )
            val day = """INSERT INTO academic_calendar (term_code, "date", day_type) VALUES ('2026FA', %s, 'CLASS')"""
            assertEquals(1, env.insert(day.format("DATE '2026-09-01'")))
            assertTrue(env.rejects(day.format("DATE '2026-09-01'")).contains("Duplicate key \"term_code: 2026FA, date: 2026-09-01\""))
            assertTrue(env.rejectsAtValidation(day.format("'not a date'")).contains("invalid date field format"))
            assertEquals(DataValue.TimestampValue("2026-09-01T00:00:00Z"), env.scalar("""SELECT "date" FROM academic_calendar"""))
        }

        @Test
        fun `update and delete run through their validated statements`() {
            env.insert("INSERT INTO campus (campus_id, code, name, is_main) VALUES ('C1', 'NTH', 'North', true)")
            assertEquals(
                1,
                env.executeUpdate(env.updateStatement("UPDATE campus SET name = 'North Campus' WHERE campus_id = 'C1'").getOrThrow()),
            )
            assertEquals(DataValue.StringValue("North Campus"), env.scalar("SELECT name FROM campus"))
            assertEquals(1, env.executeUpdate(env.deleteStatement("DELETE FROM campus WHERE campus_id = 'C1'").getOrThrow()))
            assertEquals(0L, env.count("campus"))
        }

        @Test
        fun `a statement validated by another environment is refused`() {
            val other = DuckDbDatabaseEnvironment.fromOdcs(universityDirectory)
            val stmt =
                other
                    .insertStatement(
                        "INSERT INTO campus (campus_id, code, name, is_main) VALUES ('C1', 'NTH', 'North', true)",
                    ).getOrThrow()
            val e = assertThrows<IllegalArgumentException> { env.executeUpdate(stmt) }
            assertEquals("InsertSqlStatement was validated by a different environment", e.message)
        }

        private fun facultyInsert(
            id: String,
            email: String? = null,
            mentor: String? = null,
        ) = "INSERT INTO faculty (faculty_id, department_id, given_name, family_name, email, hired_on, mentor_faculty_id, is_tenured) " +
            "VALUES ('$id', 'D1', 'Ada', 'Lovelace', ${email?.let {
                "'$it'"
            } ?: "NULL"}, DATE '2020-09-01', ${mentor?.let { "'$it'" } ?: "NULL"}, false)"

        private fun studentInsert(
            id: String,
            number: Long,
            email: String,
        ) = "INSERT INTO student (student_id, student_number, given_name, family_name, email, enrolled_at, is_international) " +
            "VALUES ('$id', $number, 'Grace', 'Hopper', '$email', TIMESTAMP '2026-09-01 08:00:00', false)"
    }

    @Nested
    inner class QuotedIdentifiers {
        private fun column(
            name: String,
            nullable: Boolean = false,
        ) = ColumnDefinition(ColumnName(name), ColumnType.VARCHAR, nullable, description = "")

        @Test
        fun `a column named like a keyword is fine because identifiers are quoted`() {
            val env =
                DuckDbDatabaseEnvironment.fromDomainModel(
                    DomainModel(
                        listOf(
                            TableDefinition(
                                TableName("t"),
                                "",
                                listOf(column("id"), column("order", nullable = true)),
                                primaryKey = listOf(ColumnName("id")),
                            ),
                        ),
                    ),
                )
            assertEquals(1, env.insert("""INSERT INTO t (id, "order") VALUES ('1', 'first')"""))
            assertEquals(DataValue.StringValue("first"), env.scalar("""SELECT "order" FROM t"""))
        }
    }

    /** Behaviour that the university fixture does not exercise, built from inline models. */
    @Nested
    inner class InlineModels {
        private fun column(
            name: String,
            type: ColumnType = ColumnType.VARCHAR,
            nullable: Boolean = false,
            description: String = "",
        ) = ColumnDefinition(ColumnName(name), type, nullable, description)

        @Test
        fun `a foreign key to a unique key that is not the primary key is enforced`() {
            val env =
                DuckDbDatabaseEnvironment.fromDomainModel(
                    DomainModel(
                        listOf(
                            TableDefinition(
                                TableName("p"),
                                "",
                                listOf(column("id"), column("code")),
                                primaryKey = listOf(ColumnName("id")),
                                uniqueKeys = listOf(listOf(ColumnName("code"))),
                            ),
                            TableDefinition(
                                TableName("c"),
                                "",
                                listOf(column("id"), column("p_code", nullable = true)),
                                primaryKey = listOf(ColumnName("id")),
                                foreignKeys = listOf(ForeignKey(listOf(ColumnName("p_code")), TableName("p"), listOf(ColumnName("code")))),
                            ),
                        ),
                    ),
                )
            env.insert("INSERT INTO p VALUES ('1', 'A')")
            assertEquals(1, env.insert("INSERT INTO c VALUES ('c1', 'A')"))
            assertEquals(1, env.insert("INSERT INTO c VALUES ('c2', NULL)"), "a null foreign key is allowed")
            assertTrue(env.rejects("INSERT INTO c VALUES ('c3', 'Z')").contains("key \"code: Z\" does not exist"))
        }

        @Test
        fun `a table without a primary key accepts duplicate rows`() {
            val env =
                DuckDbDatabaseEnvironment.fromDomainModel(
                    DomainModel(listOf(TableDefinition(TableName("log"), "", listOf(column("message", nullable = true))))),
                )
            assertEquals(2, env.insert("INSERT INTO log VALUES ('same'), ('same')"))
            assertEquals(2L, env.count("log"))
        }

        @Test
        fun `descriptions round-trip through DuckDB exactly, including quotes, newlines and unicode`() {
            val tricky = "line1\nline2 \\ \"quoted\" it's é ✓"
            val env =
                DuckDbDatabaseEnvironment.fromDomainModel(
                    DomainModel(listOf(TableDefinition(TableName("t"), tricky, listOf(column("id", description = tricky))))),
                )
            assertEquals(DataValue.StringValue(tricky), env.scalar("SELECT comment FROM duckdb_tables() WHERE table_name = 't'"))
            assertEquals(
                DataValue.StringValue(tricky),
                env.scalar("SELECT comment FROM duckdb_columns() WHERE table_name = 't' AND column_name = 'id'"),
            )
            assertEquals(tricky, env.getTableInfo(TableName("t")).description)
        }

        @Test
        fun `every column type stores and returns a value of its kind`() {
            val env =
                DuckDbDatabaseEnvironment.fromDomainModel(
                    DomainModel(
                        listOf(
                            TableDefinition(
                                TableName("t"),
                                "",
                                ColumnType.entries.map { column(it.name.lowercase(), it, nullable = true) },
                            ),
                        ),
                    ),
                )
            env.insert("INSERT INTO t VALUES ('text', 7, 9000000000, 1.5, true, TIMESTAMP '2026-01-02 03:04:05', DATE '2026-01-02')")
            val row = env.query("SELECT * FROM t").records.single()
            assertEquals(DataValue.StringValue("text"), row[ColumnName("varchar")])
            assertEquals(DataValue.LongValue(7), row[ColumnName("integer")])
            assertEquals(DataValue.LongValue(9_000_000_000), row[ColumnName("bigint")])
            assertEquals(DataValue.DoubleValue(1.5), row[ColumnName("double")])
            assertEquals(DataValue.BooleanValue(true), row[ColumnName("boolean")])
            // DuckDB TIMESTAMP has no zone; the JDBC driver hands it back as java.sql.Timestamp in the JVM's
            // default zone, so compare the wall-clock value rather than a fixed instant.
            val stored =
                Instant
                    .parse(
                        (row[ColumnName("timestamp")] as DataValue.TimestampValue).value,
                    ).atZone(ZoneId.systemDefault())
                    .toLocalDateTime()
            assertEquals(LocalDateTime.of(2026, 1, 2, 3, 4, 5), stored)
            assertEquals(DataValue.TimestampValue("2026-01-02T00:00:00Z"), row[ColumnName("date")])
            assertTrue(env.rejectsAtValidation("INSERT INTO t (bigint) VALUES ('nine billion')").contains("Could not convert"))
        }
    }

    @Nested
    inner class FromOdcsDirectory {
        @TempDir
        lateinit var dir: Path

        private val header = "apiVersion: v3.2.0\nkind: DataContract\n"
        private val campus =
            header + "schema:\n  - name: campus\n    properties:\n      - { name: campus_id, logicalType: string, primaryKey: true }\n"
        private val building =
            header + "schema:\n  - name: building\n    properties:\n" +
                "      - { name: building_id, logicalType: string, primaryKey: true }\n" +
                "      - { name: campus_id, logicalType: string, relationships: [{ to: campus.campus_id }] }\n"

        @Test
        fun `a missing directory is rejected`() {
            val e = assertThrows<IllegalArgumentException> { DuckDbDatabaseEnvironment.fromOdcs(dir.resolve("nope")) }
            assertTrue(e.message!!.startsWith("Directory does not exist"), e.message)
        }

        @Test
        fun `a directory without yml or yaml files is rejected`() {
            val empty = dir.resolve("empty").createDirectory()
            empty.resolve("README.md").writeText("# nothing here")
            assertEquals(
                "No .yml or .yaml files found in: $empty",
                assertThrows<IllegalArgumentException> {
                    DuckDbDatabaseEnvironment.fromOdcs(empty)
                }.message,
            )
        }

        @Test
        fun `yml and yaml files are read, in name order, and everything else is ignored`() {
            val split = dir.resolve("split").createDirectory()
            split.resolve("campus.yml").writeText(campus)
            split.resolve("building.yaml").writeText(building)
            split.resolve("notes.md").writeText("# ignored")
            split.resolve("university.odcs.yaml.bak").writeText("{")

            val env = DuckDbDatabaseEnvironment.fromOdcs(split)

            assertEquals(setOf(TableName("campus"), TableName("building")), env.listTables())
            assertEquals(
                ForeignKeyRef(TableName("campus"), ColumnName("campus_id")),
                env.getTableInfo(TableName("building")).columns[1].foreignKey,
            )
        }

        @Test
        fun `a directory with a broken contract fails as a whole`() {
            val broken = dir.resolve("broken").createDirectory()
            broken.resolve("campus.yml").writeText(campus)
            broken.resolve("zzz.yml").writeText(
                header + "schema:\n  - name: z\n    properties:\n      - { name: v, logicalType: array, items: { logicalType: string } }\n",
            )

            val e = assertThrows<IllegalArgumentException> { DuckDbDatabaseEnvironment.fromOdcs(broken) }
            assertTrue(e.message!!.endsWith("schema object 'z' property 'v': logicalType 'array' has no column mapping"), e.message)
        }
    }
}
