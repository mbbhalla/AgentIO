package io.github.mbbhalla.agentio.data.model

import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.assertThrows
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class DomainModelTest {
    private fun column(
        name: String,
        type: ColumnType = ColumnType.VARCHAR,
        nullable: Boolean = false,
    ) = ColumnDefinition(ColumnName(name), type, nullable, description = "")

    /** A table `name` with primary key `id` and one nullable single-column foreign key per entry of [references]. */
    private fun table(
        name: String,
        vararg references: String,
        columns: List<ColumnDefinition> = listOf(column("id")) + references.map { column("${it}_id", nullable = true) },
    ) = TableDefinition(
        name = TableName(name),
        description = "",
        columns = columns,
        primaryKey = listOf(ColumnName("id")),
        foreignKeys = references.map { ForeignKey(listOf(ColumnName("${it}_id")), TableName(it), listOf(ColumnName("id"))) },
    )

    private fun DomainModel.orderedNames() = creationOrder.map { it.name.value }

    @Nested
    inner class CreationOrder {
        @Test
        fun `places referenced tables before the tables that reference them`() {
            // alphabetical order would be a, b, c; dependencies force c, b, a
            val model = DomainModel(listOf(table("a", "b"), table("b", "c"), table("c")))
            assertEquals(listOf("c", "b", "a"), model.orderedNames())
        }

        @Test
        fun `breaks ties by name`() {
            val model = DomainModel(listOf(table("zeta"), table("alpha"), table("mid", "zeta", "alpha")))
            assertEquals(listOf("alpha", "zeta", "mid"), model.orderedNames())
        }

        @Test
        fun `a self reference does not block ordering`() {
            assertEquals(listOf("product"), DomainModel(listOf(table("product", "product"))).orderedNames())
        }

        @Test
        fun `does not depend on the order the tables are given in`() {
            val tables = listOf(table("a", "b", "c"), table("b", "c"), table("c"), table("d", "a"), table("e"))
            val expected = DomainModel(tables).orderedNames()
            assertEquals(expected, DomainModel(tables.reversed()).orderedNames())
            assertEquals(expected, DomainModel(tables.shuffled(Random(7))).orderedNames())
        }

        /**
         * Property test over seeded random acyclic models: every table appears exactly once, every
         * referenced table precedes its referencing table, and the input order is irrelevant.
         */
        @Test
        fun `is a valid topological order for random acyclic models`() {
            val random = Random(20261116)
            repeat(200) { round ->
                val size = random.nextInt(1, 25)
                val names = List(size) { "t%02d".format(it) }
                // A table may only reference tables with a smaller index (acyclic by construction) and itself.
                val tables =
                    names.mapIndexed { index, name ->
                        val targets =
                            names.take(index).filter { random.nextInt(3) == 0 } + listOfNotNull(name.takeIf { random.nextInt(6) == 0 })
                        table(name, *targets.toTypedArray())
                    }

                val model = DomainModel(tables.shuffled(random))
                val order = model.creationOrder.map { it.name }

                assertEquals(names.size, order.size, "round $round: every table once")
                assertEquals(names.map { TableName(it) }.toSet(), order.toSet(), "round $round: exactly the given tables")
                model.creationOrder.forEachIndexed { position, definition ->
                    definition.referencedTables().forEach { referenced ->
                        assertTrue(
                            order.indexOf(referenced) < position,
                            "round $round: ${referenced.value} must precede ${definition.name.value}",
                        )
                    }
                }
                assertEquals(order, DomainModel(tables).creationOrder.map { it.name }, "round $round: input order is irrelevant")
            }
        }

        @Test
        fun `handles a long chain`() {
            val names = List(300) { "t%03d".format(it) }
            val chain = names.mapIndexed { i, name -> if (i == 0) table(name) else table(name, names[i - 1]) }
            assertEquals(names, DomainModel(chain.reversed()).orderedNames())
        }

        @Test
        fun `a cycle fails construction and names the tables that cannot be ordered`() {
            val e =
                assertThrows<IllegalArgumentException> {
                    DomainModel(listOf(table("exception", "investigation"), table("investigation", "exception"), table("rule")))
                }
            assertTrue(e.message!!.contains("cycle"), e.message)
            assertTrue(e.message!!.contains("[exception, investigation]"), e.message)
            assertFalse(e.message!!.contains("rule"), e.message)
        }

        @Test
        fun `a table depending on a cycle is reported with the cycle`() {
            val e =
                assertThrows<IllegalArgumentException> {
                    DomainModel(listOf(table("a", "b"), table("b", "a"), table("c", "a")))
                }
            assertTrue(e.message!!.endsWith("[a, b, c]"), e.message)
        }

        @Test
        fun `a longer cycle is detected`() {
            val e =
                assertThrows<IllegalArgumentException> {
                    DomainModel(
                        listOf(table("a", "b"), table("b", "c"), table("c", "d"), table("d", "a")),
                    )
                }
            assertTrue(e.message!!.endsWith("[a, b, c, d]"), e.message)
        }
    }

    @Nested
    inner class ModelInvariants {
        @Test
        fun `an empty model is rejected`() {
            assertEquals(
                "A domain model needs at least one table",
                assertThrows<IllegalArgumentException> { DomainModel(emptyList()) }.message,
            )
        }

        @Test
        fun `a foreign key to a table outside the model fails construction`() {
            val e = assertThrows<IllegalArgumentException> { DomainModel(listOf(table("site", "company"))) }
            assertEquals("Table 'site' has a foreign key to unknown table 'company'", e.message)
        }

        /** `room (building_id, room_number, capacity)` with the given keys, and `section` referencing it with the given foreign key. */
        private fun roomAndSection(
            roomPrimaryKey: List<String>,
            roomUniqueKeys: List<List<String>> = emptyList(),
            sectionColumns: List<ColumnDefinition> = listOf(column("building_id", nullable = true), column("room_number", nullable = true)),
            fkColumns: List<String>,
            fkReferencedColumns: List<String>,
        ): List<TableDefinition> =
            listOf(
                TableDefinition(
                    TableName("room"),
                    "",
                    listOf(column("building_id"), column("room_number"), column("capacity", ColumnType.INTEGER)),
                    primaryKey = roomPrimaryKey.map(::ColumnName),
                    uniqueKeys = roomUniqueKeys.map { it.map(::ColumnName) },
                ),
                TableDefinition(
                    TableName("section"),
                    "",
                    listOf(column("id")) + sectionColumns,
                    primaryKey = listOf(ColumnName("id")),
                    foreignKeys = listOf(ForeignKey(fkColumns.map(::ColumnName), TableName("room"), fkReferencedColumns.map(::ColumnName))),
                ),
            )

        @Test
        fun `a foreign key to a column the referenced table does not have is rejected`() {
            val e =
                assertThrows<IllegalArgumentException> {
                    DomainModel(
                        roomAndSection(
                            roomPrimaryKey = listOf("building_id"),
                            fkColumns = listOf("building_id"),
                            fkReferencedColumns = listOf("nope"),
                        ),
                    )
                }
            assertEquals("Table 'section' has a foreign key to 'room.nope', which does not exist", e.message)
        }

        @Test
        fun `a foreign key to a column that is no key of the referenced table is rejected`() {
            val e =
                assertThrows<IllegalArgumentException> {
                    DomainModel(
                        roomAndSection(
                            roomPrimaryKey = listOf("building_id", "room_number"),
                            fkColumns = listOf("building_id"),
                            fkReferencedColumns = listOf("building_id"),
                        ),
                    )
                }
            assertEquals(
                "Table 'section' has a foreign key to 'room' (building_id), " +
                    "which is not its primary key or a unique key in that column order; its keys are [(building_id, room_number)]",
                e.message,
            )
        }

        @Test
        fun `a foreign key to the referenced key in a different column order is rejected, as DuckDB requires`() {
            val e =
                assertThrows<IllegalArgumentException> {
                    DomainModel(
                        roomAndSection(
                            roomPrimaryKey = listOf("building_id", "room_number"),
                            fkColumns = listOf("room_number", "building_id"),
                            fkReferencedColumns = listOf("room_number", "building_id"),
                        ),
                    )
                }
            assertTrue(
                e.message!!.contains("(room_number, building_id), which is not its primary key or a unique key in that column order"),
                e.message,
            )
        }

        @Test
        fun `a foreign key to a unique key in a different column order is rejected too`() {
            val e =
                assertThrows<IllegalArgumentException> {
                    DomainModel(
                        roomAndSection(
                            roomPrimaryKey = listOf("capacity"),
                            roomUniqueKeys = listOf(listOf("building_id", "room_number")),
                            fkColumns = listOf("room_number", "building_id"),
                            fkReferencedColumns = listOf("room_number", "building_id"),
                        ),
                    )
                }
            assertTrue(e.message!!.endsWith("its keys are [(capacity), (building_id, room_number)]"), e.message)
        }

        @Test
        fun `a foreign key to a unique key, in its order, is accepted`() {
            val model =
                DomainModel(
                    roomAndSection(
                        roomPrimaryKey = listOf("capacity"),
                        roomUniqueKeys = listOf(listOf("room_number", "building_id")),
                        fkColumns = listOf("room_number", "building_id"),
                        fkReferencedColumns = listOf("room_number", "building_id"),
                    ),
                )
            assertEquals(listOf("room", "section"), model.orderedNames())
        }

        @Test
        fun `a foreign key to a table without any key is rejected`() {
            val e =
                assertThrows<IllegalArgumentException> {
                    DomainModel(
                        roomAndSection(
                            roomPrimaryKey = emptyList(),
                            fkColumns = listOf("building_id"),
                            fkReferencedColumns = listOf("building_id"),
                        ),
                    )
                }
            assertTrue(e.message!!.endsWith("its keys are []"), e.message)
        }

        @Test
        fun `a foreign key column whose type differs from the referenced column is rejected, even when DuckDB could widen it`() {
            val e =
                assertThrows<IllegalArgumentException> {
                    DomainModel(
                        roomAndSection(
                            roomPrimaryKey = listOf("capacity"),
                            sectionColumns = listOf(column("room_capacity", ColumnType.BIGINT, nullable = true)),
                            fkColumns = listOf("room_capacity"),
                            fkReferencedColumns = listOf("capacity"),
                        ),
                    )
                }
            assertEquals(
                "Table 'section' has a foreign key column 'room_capacity' of type BIGINT referencing 'room.capacity' of type INTEGER; " +
                    "foreign key columns must have the same type",
                e.message,
            )
        }

        @Test
        fun `a self-referencing foreign key is checked against the table's own keys`() {
            val ok =
                TableDefinition(
                    TableName("faculty"),
                    "",
                    listOf(column("id"), column("mentor_id", nullable = true), column("badge", ColumnType.INTEGER, nullable = true)),
                    primaryKey = listOf(ColumnName("id")),
                    foreignKeys = listOf(ForeignKey(listOf(ColumnName("mentor_id")), TableName("faculty"), listOf(ColumnName("id")))),
                )
            assertEquals(listOf("faculty"), DomainModel(listOf(ok)).orderedNames())

            val wrongType =
                ok.copy(
                    foreignKeys = listOf(ForeignKey(listOf(ColumnName("badge")), TableName("faculty"), listOf(ColumnName("id")))),
                )
            assertTrue(
                assertThrows<IllegalArgumentException> { DomainModel(listOf(wrongType)) }.message!!.contains("must have the same type"),
            )

            val notAKey =
                ok.copy(
                    foreignKeys =
                        listOf(
                            ForeignKey(listOf(ColumnName("mentor_id")), TableName("faculty"), listOf(ColumnName("badge"))),
                        ),
                )
            assertEquals(
                "Table 'faculty' has a foreign key to 'faculty' (badge), " +
                    "which is not its primary key or a unique key in that column order; its keys are [(id)]",
                assertThrows<IllegalArgumentException> { DomainModel(listOf(notAKey)) }.message,
            )
        }

        @Test
        fun `keys lists the primary key first and then the unique keys, skipping an absent primary key`() {
            val t =
                TableDefinition(
                    TableName("t"),
                    "",
                    listOf(column("a"), column("b"), column("c", nullable = true)),
                    primaryKey = listOf(ColumnName("a")),
                    uniqueKeys = listOf(listOf(ColumnName("b")), listOf(ColumnName("c"))),
                )
            assertEquals(listOf(listOf(ColumnName("a")), listOf(ColumnName("b")), listOf(ColumnName("c"))), t.keys())
            assertEquals(
                listOf(listOf(ColumnName("c"))),
                t.copy(primaryKey = emptyList(), uniqueKeys = listOf(listOf(ColumnName("c")))).keys(),
            )
            assertEquals(emptyList(), TableDefinition(TableName("u"), "", listOf(column("x"))).keys())
        }

        @Test
        fun `duplicate table names fail construction`() {
            val e = assertThrows<IllegalArgumentException> { DomainModel(listOf(table("site"), table("site"))) }
            assertEquals("Duplicate table names: [site]", e.message)
        }

        @Test
        fun `tables are exposed by name and are the given definitions`() {
            val site = table("site", "company")
            val company = table("company")
            val model = DomainModel(listOf(site, company))
            assertEquals(setOf(TableName("site"), TableName("company")), model.tables.keys)
            assertSame(site, model.tables.getValue(TableName("site")))
            assertSame(company, model.creationOrder.first())
        }
    }

    @Nested
    inner class TableInvariants {
        @Test
        fun `a table without columns is rejected`() {
            val e = assertThrows<IllegalArgumentException> { TableDefinition(TableName("t"), "", emptyList()) }
            assertEquals("Table 't' needs at least one column", e.message)
        }

        @Test
        fun `duplicate column names are rejected`() {
            val e =
                assertThrows<IllegalArgumentException> {
                    TableDefinition(
                        TableName("t"),
                        "",
                        listOf(column("id"), column("x"), column("id")),
                    )
                }
            assertEquals("Table 't' has duplicate columns: [id]", e.message)
        }

        @Test
        fun `a key naming a column the table does not have is rejected`() {
            val e =
                assertThrows<IllegalArgumentException> {
                    TableDefinition(TableName("t"), "", listOf(column("id")), primaryKey = listOf(ColumnName("nope")))
                }
            assertEquals("Table 't' has no column 'nope' named in a key", e.message)
        }

        @Test
        fun `a unique key or foreign key naming a missing column is rejected too`() {
            val unique =
                assertThrows<IllegalArgumentException> {
                    TableDefinition(TableName("t"), "", listOf(column("id")), uniqueKeys = listOf(listOf(ColumnName("nope"))))
                }
            assertEquals("Table 't' has no column 'nope' named in a key", unique.message)
            val foreign =
                assertThrows<IllegalArgumentException> {
                    TableDefinition(
                        TableName("t"),
                        "",
                        listOf(column("id")),
                        foreignKeys = listOf(ForeignKey(listOf(ColumnName("nope")), TableName("u"), listOf(ColumnName("id")))),
                    )
                }
            assertEquals("Table 't' has no column 'nope' named in a key", foreign.message)
        }

        @Test
        fun `a unique key without columns is rejected`() {
            val e =
                assertThrows<IllegalArgumentException> {
                    TableDefinition(
                        TableName("t"),
                        "",
                        listOf(column("id")),
                        uniqueKeys = listOf(emptyList()),
                    )
                }
            assertEquals("Table 't' has a unique key without columns", e.message)
        }

        @Test
        fun `a column named twice in one key is rejected`() {
            val twice = listOf(ColumnName("id"), ColumnName("id"))
            val columns = listOf(column("id"))
            val expected = "Table 't' names a column twice in one key: [id, id]"
            assertEquals(
                expected,
                assertThrows<IllegalArgumentException> { TableDefinition(TableName("t"), "", columns, primaryKey = twice) }.message,
            )
            assertEquals(
                expected,
                assertThrows<IllegalArgumentException> {
                    TableDefinition(TableName("t"), "", columns, uniqueKeys = listOf(twice))
                }.message,
            )
            assertEquals(
                expected,
                assertThrows<IllegalArgumentException> {
                    TableDefinition(
                        TableName("t"),
                        "",
                        columns,
                        foreignKeys = listOf(ForeignKey(twice, TableName("u"), listOf(ColumnName("a"), ColumnName("b")))),
                    )
                }.message,
            )
        }

        @Test
        fun `a unique key implied by the primary key or by another unique key is rejected`() {
            val columns = listOf(column("id"), column("a"), column("b", nullable = true), column("c", nullable = true))
            val pk = listOf(ColumnName("id"))
            assertEquals(
                "Table 't' has a unique key (id) that the primary key (id) already implies",
                assertThrows<IllegalArgumentException> {
                    TableDefinition(TableName("t"), "", columns, pk, uniqueKeys = listOf(listOf(ColumnName("id"))))
                }.message,
            )
            assertEquals(
                "Table 't' has a unique key (a, id) that the primary key (id) already implies",
                assertThrows<IllegalArgumentException> {
                    TableDefinition(TableName("t"), "", columns, pk, uniqueKeys = listOf(listOf(ColumnName("a"), ColumnName("id"))))
                }.message,
            )
            assertEquals(
                "Table 't' has a unique key (a, b) that the unique key (a) already implies",
                assertThrows<IllegalArgumentException> {
                    TableDefinition(
                        TableName("t"),
                        "",
                        columns,
                        pk,
                        uniqueKeys = listOf(listOf(ColumnName("a")), listOf(ColumnName("a"), ColumnName("b"))),
                    )
                }.message,
            )
            assertEquals(
                "Table 't' has a unique key (a, b) that the unique key (b, a) already implies",
                assertThrows<IllegalArgumentException> {
                    TableDefinition(
                        TableName("t"),
                        "",
                        columns,
                        pk,
                        uniqueKeys = listOf(listOf(ColumnName("a"), ColumnName("b")), listOf(ColumnName("b"), ColumnName("a"))),
                    )
                }.message,
                "the same columns in another order are the same key",
            )
            // a unique key that is a strict subset of the primary key is stronger than the key, and allowed
            TableDefinition(
                TableName("t"),
                "",
                columns,
                listOf(ColumnName("id"), ColumnName("a")),
                uniqueKeys = listOf(listOf(ColumnName("id"))),
            )
            // distinct unique keys that overlap without containment are allowed
            TableDefinition(
                TableName("t"),
                "",
                columns,
                pk,
                uniqueKeys = listOf(listOf(ColumnName("a"), ColumnName("b")), listOf(ColumnName("b"), ColumnName("c"))),
            )
        }

        @Test
        fun `a foreign key declared twice, or referencing its own columns, is rejected`() {
            val columns = listOf(column("id"), column("parent_id", nullable = true))
            val toParent = ForeignKey(listOf(ColumnName("parent_id")), TableName("t"), listOf(ColumnName("id")))
            assertEquals(
                "Table 't' declares the same foreign key twice: [(parent_id) -> t (id)]",
                assertThrows<IllegalArgumentException> {
                    TableDefinition(TableName("t"), "", columns, listOf(ColumnName("id")), foreignKeys = listOf(toParent, toParent))
                }.message,
            )
            assertEquals(
                "Table 't' has a foreign key (id) that references its own columns",
                assertThrows<IllegalArgumentException> {
                    TableDefinition(
                        TableName("t"),
                        "",
                        columns,
                        listOf(ColumnName("id")),
                        foreignKeys = listOf(ForeignKey(listOf(ColumnName("id")), TableName("t"), listOf(ColumnName("id")))),
                    )
                }.message,
            )
            // the same columns to a different table, or to different columns of this table, are distinct keys
            TableDefinition(
                TableName("t"),
                "",
                columns,
                listOf(ColumnName("id")),
                foreignKeys = listOf(toParent, ForeignKey(listOf(ColumnName("parent_id")), TableName("other"), listOf(ColumnName("id")))),
            )
        }

        @Test
        fun `a nullable primary key column is rejected`() {
            val e =
                assertThrows<IllegalArgumentException> {
                    TableDefinition(TableName("t"), "", listOf(column("id", nullable = true)), primaryKey = listOf(ColumnName("id")))
                }
            assertEquals("Primary key column 't.id' must not be nullable", e.message)
        }

        @Test
        fun `a nullable unique or foreign key column is allowed`() {
            val t =
                TableDefinition(
                    TableName("t"),
                    "",
                    listOf(column("id"), column("email", nullable = true), column("parent_id", nullable = true)),
                    primaryKey = listOf(ColumnName("id")),
                    uniqueKeys = listOf(listOf(ColumnName("email"))),
                    foreignKeys = listOf(ForeignKey(listOf(ColumnName("parent_id")), TableName("t"), listOf(ColumnName("id")))),
                )
            assertEquals(emptySet(), t.referencedTables(), "a self reference is not a dependency")
        }

        @Test
        fun `a table without keys is valid`() {
            val t = TableDefinition(TableName("log"), "", listOf(column("message", nullable = true)))
            assertEquals(emptyList(), t.primaryKey)
            assertEquals(emptySet(), t.referencedTables())
            val info = t.toTableInfo().columns.single()
            assertFalse(info.primaryKey)
            assertNull(info.foreignKey)
            assertTrue(info.nullable)
        }
    }

    @Nested
    inner class ForeignKeyInvariants {
        @Test
        fun `mismatched column counts are rejected`() {
            val e =
                assertThrows<IllegalArgumentException> {
                    ForeignKey(listOf(ColumnName("a")), TableName("t"), listOf(ColumnName("x"), ColumnName("y")))
                }
            assertEquals("Foreign key has 1 columns but references 2 columns in 't'", e.message)
        }

        @Test
        fun `no columns is rejected`() {
            assertEquals(
                "A foreign key needs at least one column",
                assertThrows<IllegalArgumentException> { ForeignKey(emptyList(), TableName("t"), emptyList()) }.message,
            )
        }

        @Test
        fun `referencing the same column twice is rejected`() {
            val e =
                assertThrows<IllegalArgumentException> {
                    ForeignKey(listOf(ColumnName("a"), ColumnName("b")), TableName("t"), listOf(ColumnName("x"), ColumnName("x")))
                }
            assertEquals("Foreign key references a column of 't' twice: [x, x]", e.message)
        }
    }

    @Nested
    inner class ToTableInfo {
        private val shipmentLot =
            TableDefinition(
                name = TableName("shipment_lot"),
                description = "A lot within a shipment",
                columns =
                    listOf(
                        column("id"),
                        column("shipment_id"),
                        column("tpartner_id"),
                        column("lot_qty", ColumnType.DOUBLE, nullable = true),
                    ),
                primaryKey = listOf(ColumnName("id"), ColumnName("shipment_id")),
                foreignKeys =
                    listOf(
                        ForeignKey(
                            columns = listOf(ColumnName("shipment_id"), ColumnName("tpartner_id")),
                            referencedTable = TableName("shipment"),
                            referencedColumns = listOf(ColumnName("id"), ColumnName("supplier_tpartner_id")),
                        ),
                    ),
            )

        @Test
        fun `derives primary key flags, nullability and per-column foreign key references`() {
            val info = shipmentLot.toTableInfo()

            assertEquals(TableName("shipment_lot"), info.name)
            assertEquals("A lot within a shipment", info.description)
            assertEquals(shipmentLot.columns.map { it.name }, info.columns.map { it.name }, "column order is preserved")
            val byName = info.columns.associateBy { it.name.value }
            assertTrue(byName.getValue("id").primaryKey)
            assertTrue(byName.getValue("shipment_id").primaryKey)
            assertFalse(byName.getValue("tpartner_id").primaryKey)
            assertNull(byName.getValue("id").foreignKey)
            assertEquals(ForeignKeyRef(TableName("shipment"), ColumnName("id")), byName.getValue("shipment_id").foreignKey)
            assertEquals(
                ForeignKeyRef(TableName("shipment"), ColumnName("supplier_tpartner_id")),
                byName.getValue("tpartner_id").foreignKey,
            )
            assertEquals(ColumnType.DOUBLE, byName.getValue("lot_qty").type)
            assertTrue(byName.getValue("lot_qty").nullable)
            assertFalse(byName.getValue("id").nullable)
        }

        @Test
        fun `a column in two foreign keys reports the first`() {
            val t =
                TableDefinition(
                    TableName("t"),
                    "",
                    listOf(column("id"), column("x", nullable = true)),
                    primaryKey = listOf(ColumnName("id")),
                    foreignKeys =
                        listOf(
                            ForeignKey(listOf(ColumnName("x")), TableName("first"), listOf(ColumnName("a"))),
                            ForeignKey(listOf(ColumnName("x")), TableName("second"), listOf(ColumnName("b"))),
                        ),
                )
            assertEquals(ForeignKeyRef(TableName("first"), ColumnName("a")), t.toTableInfo().columns[1].foreignKey)
        }
    }
}
