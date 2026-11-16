package io.github.mbbhalla.agentio.data.model

import io.github.mbbhalla.agentio.data.model.ValueConstraint.Companion.text
import java.nio.file.Path

/**
 * A relational domain model: tables with typed, documented columns and the primary, unique and
 * foreign keys that tie them together. It carries no data. [DuckDbDatabaseEnvironment.fromDomainModel]
 * turns it into an empty database that enforces every rule it declares.
 *
 * A model that exists can be created: every rule DuckDB applies to the DDL is checked here first, so
 * [DuckDbDatabaseEnvironment.fromDomainModel] cannot be rejected. Construction fails if:
 * - the model has no tables, or two tables share a name;
 * - a foreign key references a table that is not in the model, or a column the referenced table does
 *   not have;
 * - the referenced columns are not the referenced table's primary key or one of its unique keys, in
 *   the same column order (DuckDB requires an exact match);
 * - a foreign key column's type differs from the referenced column's type (DuckDB does not widen,
 *   not even `INTEGER` to `BIGINT`);
 * - foreign keys form a cycle (DuckDB declares foreign keys only inside `CREATE TABLE`, so a cycle has
 *   no valid creation order; the model author must drop one side).
 *
 * @property tables the tables by name
 * @property creationOrder the tables ordered so that every referenced table precedes the tables
 *   that reference it; ties are broken by name, so the order is deterministic
 */
class DomainModel(
    tables: Collection<TableDefinition>,
) {
    val tables: Map<TableName, TableDefinition>
    val creationOrder: List<TableDefinition>

    init {
        require(tables.isNotEmpty()) { "A domain model needs at least one table" }
        val duplicates = tables.groupBy { it.name }.filterValues { it.size > 1 }.keys
        require(duplicates.isEmpty()) { "Duplicate table names: ${duplicates.map { it.value }.sorted()}" }
        this.tables = tables.associateBy { it.name }
        tables.forEach { table -> table.foreignKeys.forEach { validateForeignKey(table, it) } }
        creationOrder = orderByDependencies(this.tables)
    }

    /** The checks that need the referenced table; [ForeignKey] and [TableDefinition] check what they can alone. */
    private fun validateForeignKey(
        table: TableDefinition,
        fk: ForeignKey,
    ) {
        val where = "Table '${table.name.value}' has a foreign key"
        val referenced =
            requireNotNull(tables[fk.referencedTable]) { "$where to unknown table '${fk.referencedTable.value}'" }
        val referencedColumns = referenced.columns.associateBy { it.name }
        fk.referencedColumns.forEach { column ->
            require(column in referencedColumns) { "$where to '${fk.referencedTable.value}.${column.value}', which does not exist" }
        }
        require(fk.referencedColumns in referenced.keys()) {
            "$where to '${fk.referencedTable.value}' ${fk.referencedColumns.names()}, which is not its primary key or a unique key " +
                "in that column order; its keys are ${referenced.keys().map { it.names() }}"
        }
        val ownColumns = table.columns.associateBy { it.name }
        fk.columns.zip(fk.referencedColumns).forEach { (own, theirs) ->
            val ownType = ownColumns.getValue(own).type
            val theirType = referencedColumns.getValue(theirs).type
            require(ownType == theirType) {
                "$where column '${own.value}' of type $ownType referencing " +
                    "'${fk.referencedTable.value}.${theirs.value}' of type $theirType; foreign key columns must have the same type"
            }
        }
    }

    private fun List<ColumnName>.names(): String = joinToString(", ", "(", ")") { it.value }

    companion object {
        /** File extensions of a YAML file. */
        val YAML_EXTENSIONS: Set<String> = setOf("yml", "yaml")

        /**
         * Parses Open Data Contract Standard (ODCS v3) documents into a model; the schema objects of
         * all files are combined and relationships resolve across them. See [OpenDataContract] for
         * the mapping and rules.
         *
         * @throws IllegalArgumentException if a file is not valid YAML or ODCS, a property has no
         *   column mapping, or a key or relationship is malformed; the message names the file,
         *   schema object and property
         */
        fun fromOdcs(files: List<Path>): DomainModel = DomainModel(OpenDataContract.parse(files))

        /**
         * Kahn's algorithm over the foreign-key graph. A table is ready when every table it references
         * has been placed; self-references never block. Ready tables are placed in name order.
         */
        private fun orderByDependencies(tables: Map<TableName, TableDefinition>): List<TableDefinition> {
            val remaining = tables.keys.toMutableSet()
            val ordered = mutableListOf<TableDefinition>()
            while (remaining.isNotEmpty()) {
                val ready =
                    remaining
                        .filter { name -> tables.getValue(name).referencedTables().none { it in remaining } }
                        .sortedBy { it.value }
                require(ready.isNotEmpty()) {
                    "Foreign keys form a cycle; these tables cannot be ordered for creation: " +
                        remaining.map { it.value }.sorted()
                }
                ordered += ready.map { tables.getValue(it) }
                remaining -= ready
            }
            return ordered
        }
    }
}

/**
 * One table of a [DomainModel].
 *
 * @property primaryKey columns of the primary key; empty when the table has none
 * @property uniqueKeys each entry is the column list of one unique key
 * @throws IllegalArgumentException if the table has no columns or two columns with one name, a unique
 *   key has no columns, a key names a column twice or names a column the table does not have, a
 *   primary key column is nullable (DuckDB makes primary key columns `NOT NULL` regardless, and the
 *   model must say what the database does), a unique key is implied by the primary key or by another
 *   unique key (a superset or duplicate says nothing), the same foreign key is declared twice, or a
 *   foreign key references its own columns (every row would reference itself)
 */
data class TableDefinition(
    val name: TableName,
    val description: String,
    val columns: List<ColumnDefinition>,
    val primaryKey: List<ColumnName> = emptyList(),
    val uniqueKeys: List<List<ColumnName>> = emptyList(),
    val foreignKeys: List<ForeignKey> = emptyList(),
) {
    init {
        ValueConstraint.requireNoNul(description, "Table '${name.value}' description")
        require(columns.isNotEmpty()) { "Table '${name.value}' needs at least one column" }
        val duplicateColumns = columns.groupBy { it.name }.filterValues { it.size > 1 }.keys
        require(duplicateColumns.isEmpty()) { "Table '${name.value}' has duplicate columns: ${duplicateColumns.map { it.value }.sorted()}" }
        require(uniqueKeys.all { it.isNotEmpty() }) { "Table '${name.value}' has a unique key without columns" }
        (listOf(primaryKey) + uniqueKeys + foreignKeys.map { it.columns }).forEach { key ->
            require(key.toSet().size == key.size) { "Table '${name.value}' names a column twice in one key: ${key.map { it.value }}" }
        }
        val columnsByName = columns.associateBy { it.name }
        (primaryKey + uniqueKeys.flatten() + foreignKeys.flatMap { it.columns }).forEach { column ->
            require(column in columnsByName) { "Table '${name.value}' has no column '${column.value}' named in a key" }
        }
        primaryKey.forEach { column ->
            require(!columnsByName.getValue(column).nullable) {
                "Primary key column '${name.value}.${column.value}' must not be nullable"
            }
        }
        uniqueKeys.forEachIndexed { i, unique ->
            val columnSet = unique.toSet()
            require(primaryKey.isEmpty() || !columnSet.containsAll(primaryKey)) {
                "Table '${name.value}' has a unique key ${unique.names()} that the primary key ${primaryKey.names()} already implies"
            }
            uniqueKeys.forEachIndexed { j, other ->
                require(i == j || !columnSet.containsAll(other)) {
                    "Table '${name.value}' has a unique key ${unique.names()} that the unique key ${other.names()} already implies"
                }
            }
        }
        val duplicateForeignKeys = foreignKeys.groupBy { it }.filterValues { it.size > 1 }.keys
        require(duplicateForeignKeys.isEmpty()) {
            "Table '${name.value}' declares the same foreign key twice: ${duplicateForeignKeys.map { it.describe() }}"
        }
        foreignKeys.forEach { fk ->
            require(fk.referencedTable != name || fk.columns != fk.referencedColumns) {
                "Table '${name.value}' has a foreign key ${fk.columns.names()} that references its own columns"
            }
        }
    }

    private fun List<ColumnName>.names(): String = joinToString(", ", "(", ")") { it.value }

    private fun ForeignKey.describe(): String = "${columns.names()} -> ${referencedTable.value} ${referencedColumns.names()}"

    /** Tables this table references through foreign keys, excluding itself. */
    fun referencedTables(): Set<TableName> = foreignKeys.map { it.referencedTable }.filter { it != name }.toSet()

    /** The column lists a foreign key may reference: the primary key (if any) and every unique key, each in declared order. */
    fun keys(): List<List<ColumnName>> = listOfNotNull(primaryKey.takeIf { it.isNotEmpty() }) + uniqueKeys

    /** The view of this table that agents discover through [DuckDbDatabaseEnvironment.getTableInfo]. */
    fun toTableInfo(): TableInfo =
        TableInfo(
            name = name,
            description = description,
            columns =
                columns.map { column ->
                    ColumnInfo(
                        name = column.name,
                        type = column.type,
                        nullable = column.nullable,
                        primaryKey = column.name in primaryKey,
                        foreignKey = foreignKeyRefFor(column.name),
                        description = column.description,
                    )
                },
        )

    /** The referenced column of the first foreign key [column] takes part in, if any. */
    private fun foreignKeyRefFor(column: ColumnName): ForeignKeyRef? =
        foreignKeys
            .firstOrNull { column in it.columns }
            ?.let { fk -> ForeignKeyRef(table = fk.referencedTable, column = fk.referencedColumns[fk.columns.indexOf(column)]) }
}

/**
 * One column of a [TableDefinition].
 *
 * @property constraints rules on the column's values, each becoming a `CHECK`
 * @throws IllegalArgumentException if a constraint cannot apply to [type], two constraints are of one
 *   kind, a minimum exceeds a maximum or leaves no value between exclusive bounds on a discrete type
 *   (`INTEGER`, `BIGINT`, `DATE`), a minimum length exceeds a maximum length, or an enumerated value
 *   breaks another constraint of the column (the model would then allow a value that can never be
 *   stored)
 */
data class ColumnDefinition(
    val name: ColumnName,
    val type: ColumnType,
    val nullable: Boolean,
    val description: String,
    val constraints: List<ValueConstraint> = emptyList(),
) {
    init {
        ValueConstraint.requireNoNul(description, "Column '${name.value}' description")
        constraints.forEach { constraint ->
            require(constraint.appliesTo(type)) { "Column '${name.value}' of type $type cannot have the constraint $constraint" }
        }
        val duplicateKinds = constraints.groupBy { it::class.simpleName }.filterValues { it.size > 1 }.keys
        require(duplicateKinds.isEmpty()) { "Column '${name.value}' has more than one constraint of kind $duplicateKinds" }
        val minimum = constraints.filterIsInstance<ValueConstraint.Minimum>().singleOrNull()
        val maximum = constraints.filterIsInstance<ValueConstraint.Maximum>().singleOrNull()
        if (minimum != null && maximum != null) {
            val order = ValueConstraint.compare(minimum.value, maximum.value)
            require(order == null || order < 0 || (order == 0 && !minimum.exclusive && !maximum.exclusive)) {
                "Column '${name.value}' has a minimum above its maximum: $minimum, $maximum"
            }
            require(ValueConstraint.admitsSomething(type, minimum, maximum)) {
                "Column '${name.value}' of type $type has no value between its exclusive bounds: $minimum, $maximum"
            }
        }
        val minLength = constraints.filterIsInstance<ValueConstraint.MinLength>().singleOrNull()?.length
        val maxLength = constraints.filterIsInstance<ValueConstraint.MaxLength>().singleOrNull()?.length
        require(minLength == null || maxLength == null || minLength <= maxLength) {
            "Column '${name.value}' has a minimum length $minLength above its maximum length $maxLength"
        }
        constraints.filterIsInstance<ValueConstraint.OneOf>().singleOrNull()?.let { oneOf ->
            val others = constraints.filterNot { it is ValueConstraint.OneOf }
            oneOf.values.forEach { value ->
                others.forEach { other ->
                    require(
                        other.admits(value),
                    ) { "Column '${name.value}' allows the value ${value.text()}, which breaks its constraint $other" }
                }
            }
        }
    }
}

/**
 * A single- or multi-column foreign key. [columns] and [referencedColumns] pair up by position.
 *
 * @throws IllegalArgumentException if the column lists are empty, differ in length, or name a
 *   referenced column twice
 */
data class ForeignKey(
    val columns: List<ColumnName>,
    val referencedTable: TableName,
    val referencedColumns: List<ColumnName>,
) {
    init {
        require(columns.isNotEmpty()) { "A foreign key needs at least one column" }
        require(columns.size == referencedColumns.size) {
            "Foreign key has ${columns.size} columns but references ${referencedColumns.size} columns in '${referencedTable.value}'"
        }
        require(referencedColumns.toSet().size == referencedColumns.size) {
            "Foreign key references a column of '${referencedTable.value}' twice: ${referencedColumns.map { it.value }}"
        }
    }
}
