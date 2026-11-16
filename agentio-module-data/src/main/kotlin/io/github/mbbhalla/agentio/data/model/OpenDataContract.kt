package io.github.mbbhalla.agentio.data.model

import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlException
import com.charleskorn.kaml.YamlList
import com.charleskorn.kaml.YamlMap
import com.charleskorn.kaml.YamlNode
import com.charleskorn.kaml.YamlNull
import com.charleskorn.kaml.YamlScalar
import java.nio.file.Path
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import kotlin.io.path.readText

/**
 * Reads a domain model from an Open Data Contract Standard (ODCS) v3 document, the Linux Foundation
 * (Bitol) YAML standard for data contracts: <https://bitol-io.github.io/open-data-contract-standard/>.
 * Only the `schema` section is read; every other section of the contract is accepted and ignored.
 *
 * ```yaml
 * apiVersion: v3.2.0
 * kind: DataContract
 * schema:
 *   - name: section
 *     description: One offering of a course in a term
 *     properties:
 *       - name: course_code
 *         logicalType: string
 *         physicalType: VARCHAR
 *         required: true
 *         primaryKey: true
 *         primaryKeyPosition: 1
 *         relationships:
 *           - to: course.course_code
 *       - name: seats
 *         logicalType: integer
 *         required: true
 *     relationships:
 *       - type: foreignKey
 *         from: [section.building_id, section.room_number]
 *         to: [room.building_id, room.room_number]
 * ```
 *
 * | ODCS                                            | Model                                                        |
 * |-------------------------------------------------|--------------------------------------------------------------|
 * | schema object                                   | table; `physicalName`, else `name`, lower-cased              |
 * | property                                        | column; `physicalName`, else `name`, lower-cased             |
 * | `description`                                   | description                                                  |
 * | `physicalType` (`VARCHAR(18)`, `BIGINT`, ...)   | column type; decides when present                            |
 * | `logicalType` string/integer/number/boolean/date/timestamp | VARCHAR/INTEGER/DOUBLE/BOOLEAN/DATE/TIMESTAMP when `physicalType` is absent; `integer` with `logicalTypeOptions.format` i64 or u32 is BIGINT |
 * | `required: true`, or a primary key property     | `NOT NULL`                                                   |
 * | `primaryKey: true`, ordered by `primaryKeyPosition` | `PRIMARY KEY`; a composite key needs distinct positions  |
 * | `unique: true`                                  | single-column `UNIQUE` (ODCS has no composite unique key); ignored on a single-column primary key, which it repeats |
 * | `relationships` (property or object level)      | `FOREIGN KEY`; shorthand `object.property` or fully qualified `schema/<id>/properties/<id>`; arrays for composite keys |
 *
 * | `enum` (entries `{value: ...}`)                 | `CHECK (col IN (...))`                                       |
 * | `logicalTypeOptions.minimum` / `maximum` / `exclusiveMinimum` / `exclusiveMaximum` | `CHECK (col >= v)` and the like; numbers, dates and timestamps |
 * | `logicalTypeOptions.minLength` / `maxLength` / `pattern` | `CHECK (length(col) >= n)`, `CHECK (regexp_matches(col, 'p'))`; strings |
 *
 * `time`, `object`, `array`, `map` and `vector` properties have no column mapping and are rejected,
 * as are relationships to external contracts and nested properties. `quality` rules and
 * `logicalTypeOptions.multipleOf` are accepted but not enforced. Keys are collected property-level
 * first, in property order, then object-level in declared order. An unknown key on a schema object or
 * property is an error, so a misspelt `primaryKey` cannot silently drop a key.
 */
internal object OpenDataContract {
    private val LOGICAL_TYPES =
        mapOf(
            "string" to ColumnType.VARCHAR,
            "integer" to ColumnType.INTEGER,
            "number" to ColumnType.DOUBLE,
            "boolean" to ColumnType.BOOLEAN,
            "date" to ColumnType.DATE,
            "timestamp" to ColumnType.TIMESTAMP,
        )
    private val UNMAPPED_LOGICAL_TYPES = setOf("time", "object", "array", "map", "vector")
    private val INTEGER_FORMATS =
        mapOf(
            "i8" to ColumnType.INTEGER,
            "i16" to ColumnType.INTEGER,
            "i32" to ColumnType.INTEGER,
            "u8" to ColumnType.INTEGER,
            "u16" to ColumnType.INTEGER,
            "i64" to ColumnType.BIGINT,
            "u32" to ColumnType.BIGINT,
        )
    private val ELEMENT_KEYS =
        setOf(
            "authoritativeDefinitions",
            "businessName",
            "customProperties",
            "deprecated",
            "description",
            "id",
            "logicalType",
            "name",
            "physicalName",
            "physicalType",
            "quality",
            "synonyms",
            "tags",
        )
    private val OBJECT_KEYS = ELEMENT_KEYS + setOf("dataGranularityDescription", "properties", "relationships")
    private val PROPERTY_KEYS =
        ELEMENT_KEYS +
            setOf(
                "classification",
                "criticalDataElement",
                "encryptedName",
                "enum",
                "examples",
                "items",
                "logicalTypeOptions",
                "map",
                "partitioned",
                "partitionKeyPosition",
                "primaryKey",
                "primaryKeyPosition",
                "properties",
                "relationships",
                "required",
                "semanticType",
                "transformDescription",
                "transformLogic",
                "transformSourceObjects",
                "unique",
            )
    private val RELATIONSHIP_KEYS = setOf("customProperties", "description", "from", "id", "to", "type")
    private const val FOREIGN_KEY = "foreignKey"
    private const val NO_POSITION = -1

    /** `VARCHAR(18)`, `decimal(10, 2)`, `TIMESTAMP WITH TIME ZONE`: the type name without a parenthesized size. */
    private val PHYSICAL_TYPE = Regex("""^\s*([A-Za-z][A-Za-z ]*?)\s*(\([^)]*\))?\s*$""")
    private val FULLY_QUALIFIED = Regex("""^schema/([^/]+)/properties/([^/]+)$""")
    private val SHORTHAND = Regex("""^([^./#]+)\.([^./#]+)$""")

    /** Parses every file and combines their schema objects into one model; references resolve across files. */
    fun parse(files: List<Path>): List<TableDefinition> {
        require(files.isNotEmpty()) { "No ODCS files given" }
        val objects = files.flatMap { readObjects(it) }
        require(objects.isNotEmpty()) { "No schema objects found in ODCS files: $files" }
        val index = Index(objects)
        return objects.map { toTable(it, index) }
    }

    // ---- reading -------------------------------------------------------------------------------------------

    private class SchemaObject(
        val where: String,
        val name: String,
        val id: String?,
        val tableName: TableName,
        val description: String,
        val properties: List<Property>,
        val relationships: List<YamlMap>,
    )

    private class Property(
        val where: String,
        val name: String,
        val id: String?,
        val column: ColumnDefinition,
        val primaryKey: Boolean,
        val primaryKeyPosition: Int,
        val unique: Boolean,
        val relationships: List<YamlMap>,
    )

    private fun readObjects(file: Path): List<SchemaObject> {
        val root = parseFile(file)
        if (root is YamlNull) return emptyList()
        val document = root.asMap("$file")
        document.string("kind", "$file")?.let { require(it == "DataContract") { "$file: kind must be DataContract, found '$it'" } }
        document.string("apiVersion", "$file")?.let { require(it.startsWith("v3")) { "$file: apiVersion must be v3.x, found '$it'" } }
        require(document.entries.keys.any { it.content == "schema" }) { "$file: an ODCS document needs a 'schema' section" }
        val schema = document.node("schema") ?: return emptyList()
        return schema.asList("$file schema").mapIndexed { i, node -> readObject(node, "$file schema[$i]") }
    }

    private fun parseFile(file: Path): YamlNode =
        try {
            Yaml.default.parseToYamlNode(file.readText())
        } catch (e: YamlException) {
            throw IllegalArgumentException("Invalid YAML in $file at line ${e.line}, column ${e.column}: ${e.message}", e)
        } catch (e: java.io.IOException) {
            throw IllegalArgumentException("Cannot read YAML $file: ${e.message}", e)
        }

    private fun readObject(
        node: YamlNode,
        position: String,
    ): SchemaObject {
        val map = node.asMap(position)
        val name = map.requiredString("name", position)
        val where = "${position.substringBeforeLast(" schema[")} schema object '$name'"
        map.checkKeys(OBJECT_KEYS, where)
        map.string("logicalType", where)?.let { require(it == "object") { "$where: logicalType must be object, found '$it'" } }
        return SchemaObject(
            where = where,
            name = name,
            id = map.string("id", where),
            tableName = locating(where) { TableName((map.string("physicalName", where) ?: name).lowercase()) },
            description = map.string("description", where).orEmpty(),
            properties =
                map.node("properties")?.asList("$where properties").orEmpty().mapIndexed {
                    i,
                    p,
                    ->
                    readProperty(p, "$where property[$i]", where)
                },
            relationships =
                map.node("relationships")?.asList("$where relationships").orEmpty().mapIndexed {
                    i,
                    r,
                    ->
                    r.asMap("$where relationships[$i]")
                },
        )
    }

    private fun readProperty(
        node: YamlNode,
        position: String,
        objectWhere: String,
    ): Property {
        val map = node.asMap(position)
        val name = map.requiredString("name", position)
        val where = "$objectWhere property '$name'"
        map.checkKeys(PROPERTY_KEYS, where)
        val primaryKey = map.boolean("primaryKey", where) ?: false
        val primaryKeyPosition = map.int("primaryKeyPosition", where) ?: NO_POSITION
        require(primaryKey || primaryKeyPosition == NO_POSITION) { "$where: primaryKeyPosition is set but primaryKey is not true" }
        require(primaryKeyPosition == NO_POSITION || primaryKeyPosition >= 1) {
            "$where: primaryKeyPosition must be 1 or more, found $primaryKeyPosition"
        }
        return Property(
            where = where,
            name = name,
            id = map.string("id", where),
            column =
                columnType(map, where).let { type ->
                    val constraints = valueConstraints(map, type, where)
                    locating(where) {
                        ColumnDefinition(
                            name = ColumnName((map.string("physicalName", where) ?: name).lowercase()),
                            type = type,
                            nullable = !((map.boolean("required", where) ?: false) || primaryKey),
                            description = map.string("description", where).orEmpty(),
                            constraints = constraints,
                        )
                    }
                },
            primaryKey = primaryKey,
            primaryKeyPosition = primaryKeyPosition,
            unique = map.boolean("unique", where) ?: false,
            relationships =
                map.node("relationships")?.asList("$where relationships").orEmpty().mapIndexed {
                    i,
                    r,
                    ->
                    r.asMap("$where relationships[$i]")
                },
        )
    }

    private fun columnType(
        property: YamlMap,
        where: String,
    ): ColumnType {
        val physical = property.string("physicalType", where)
        if (physical != null) {
            val baseName = PHYSICAL_TYPE.find(physical)?.groupValues?.get(1)
            return baseName?.let { ColumnType.fromTypeNameOrNull(it) }
                ?: throw IllegalArgumentException(
                    "$where: physicalType '$physical' has no column mapping; use one of ${ColumnType.entries.map {
                        it.name
                    }} or a common alias",
                )
        }
        val logical =
            property.string("logicalType", where) ?: throw IllegalArgumentException("$where: a property needs logicalType or physicalType")
        if (logical == "integer") {
            val format =
                property
                    .node(
                        "logicalTypeOptions",
                    )?.asMap("$where logicalTypeOptions")
                    ?.string("format", "$where logicalTypeOptions")
            return if (format == null) {
                ColumnType.INTEGER
            } else {
                INTEGER_FORMATS[format]
                    ?: throw IllegalArgumentException(
                        "$where: integer format '$format' has no column mapping; use one of ${INTEGER_FORMATS.keys}",
                    )
            }
        }
        return LOGICAL_TYPES[logical]
            ?: throw IllegalArgumentException(
                if (logical in UNMAPPED_LOGICAL_TYPES) {
                    "$where: logicalType '$logical' has no column mapping"
                } else {
                    "$where: unknown logicalType '$logical'; ODCS logical types are ${(LOGICAL_TYPES.keys + UNMAPPED_LOGICAL_TYPES).sorted()}"
                },
            )
    }

    // ---- value constraints ---------------------------------------------------------------------------------

    private val ENUM_ENTRY_KEYS = setOf("authoritativeDefinitions", "customProperties", "description", "id", "label", "tags", "value")
    private val LOGICAL_TYPE_OPTION_KEYS =
        setOf(
            "exclusiveMaximum",
            "exclusiveMinimum",
            "format",
            "maxItems",
            "maxLength",
            "maxProperties",
            "maximum",
            "minItems",
            "minLength",
            "minProperties",
            "minimum",
            "multipleOf",
            "pattern",
            "required",
            "uniqueItems",
            "timezone",
            "defaultTimezone",
            "dimensions",
            "elementType",
            "distanceMetric",
            "embeddingModel",
            "embeddingModelVersion",
            "normalized",
        )
    private val IGNORED_LOGICAL_TYPE_OPTIONS = setOf("format", "multipleOf", "timezone", "defaultTimezone")

    /**
     * `enum` and the bounds in `logicalTypeOptions` as [ValueConstraint]s. `format`, `timezone` and
     * `defaultTimezone` describe the value's shape and are ignored; `multipleOf` is accepted but not enforced.
     */
    private fun valueConstraints(
        property: YamlMap,
        type: ColumnType,
        where: String,
    ): List<ValueConstraint> {
        val constraints = mutableListOf<ValueConstraint>()
        property.node("enum")?.let { constraints += enumConstraint(it.asList("$where enum"), type, "$where enum") }
        property.node("logicalTypeOptions")?.let { node ->
            val options = node.asMap("$where logicalTypeOptions")
            val optionsWhere = "$where logicalTypeOptions"
            options.checkKeys(LOGICAL_TYPE_OPTION_KEYS, optionsWhere)
            val unmapped =
                options.entries.keys
                    .map { it.content }
                    .filterNot { it in IGNORED_LOGICAL_TYPE_OPTIONS || it in BOUND_OPTIONS }
            require(
                unmapped.isEmpty(),
            ) { "$optionsWhere: $unmapped apply to array, object or vector properties, which have no column mapping" }
            options.scalar("minimum", optionsWhere)?.let {
                constraints +=
                    locating(optionsWhere) { ValueConstraint.Minimum(bound(it, type, "$optionsWhere minimum")) }
            }
            options.scalar("exclusiveMinimum", optionsWhere)?.let {
                constraints +=
                    locating(optionsWhere) { ValueConstraint.Minimum(bound(it, type, "$optionsWhere exclusiveMinimum"), exclusive = true) }
            }
            options.scalar("maximum", optionsWhere)?.let {
                constraints +=
                    locating(optionsWhere) { ValueConstraint.Maximum(bound(it, type, "$optionsWhere maximum")) }
            }
            options.scalar("exclusiveMaximum", optionsWhere)?.let {
                constraints +=
                    locating(optionsWhere) { ValueConstraint.Maximum(bound(it, type, "$optionsWhere exclusiveMaximum"), exclusive = true) }
            }
            options.int("minLength", optionsWhere)?.let { constraints += locating(optionsWhere) { ValueConstraint.MinLength(it) } }
            options.int("maxLength", optionsWhere)?.let { constraints += locating(optionsWhere) { ValueConstraint.MaxLength(it) } }
            options.string("pattern", optionsWhere)?.let { constraints += locating(optionsWhere) { ValueConstraint.Pattern(it) } }
        }
        return constraints
    }

    private val BOUND_OPTIONS = setOf("minimum", "exclusiveMinimum", "maximum", "exclusiveMaximum", "minLength", "maxLength", "pattern")

    /** Each entry is an object `{value: ...}` with optional `label`, `description`, `tags`, `id` and custom properties, as the standard requires. */
    private fun enumConstraint(
        entries: List<YamlNode>,
        type: ColumnType,
        where: String,
    ): ValueConstraint.OneOf {
        val values =
            entries.mapIndexed { i, entry ->
                val map =
                    entry as? YamlMap
                        ?: throw IllegalArgumentException(
                            "$where[$i]: an enum entry is an object with 'value', found a ${entry.kind()} at ${entry.at()}",
                        )
                map.checkKeys(ENUM_ENTRY_KEYS, "$where[$i]")
                val scalar = map.scalar("value", "$where[$i]") ?: throw IllegalArgumentException("$where[$i]: an enum entry needs 'value'")
                value(scalar, type, "$where[$i] value")
            }
        return locating(where) { ValueConstraint.OneOf(values) }
    }

    /**
     * A bound as a number, or as a date or timestamp for a temporal column. Read independently of a
     * string column's type, so that `minimum` on a string is reported as a constraint the column cannot
     * have rather than as a malformed number.
     */
    private fun bound(
        scalar: YamlScalar,
        type: ColumnType,
        where: String,
    ): DataValue =
        when (type) {
            ColumnType.DATE, ColumnType.TIMESTAMP, ColumnType.INTEGER, ColumnType.BIGINT, ColumnType.DOUBLE -> value(scalar, type, where)
            ColumnType.VARCHAR, ColumnType.BOOLEAN ->
                scalar.content.toLongOrNull()?.let { DataValue.LongValue(it) }
                    ?: scalar.content.toDoubleOrNull()?.let { DataValue.DoubleValue(it) }
                    ?: runCatching {
                        DataValue.TimestampValue(instant(scalar.content, type, where).toString())
                    }.getOrElse { DataValue.StringValue(scalar.content) }
        }

    /** A YAML scalar as the [DataValue] a column of [type] holds. */
    private fun value(
        scalar: YamlScalar,
        type: ColumnType,
        where: String,
    ): DataValue {
        val text = scalar.content
        return when (type) {
            ColumnType.VARCHAR -> DataValue.StringValue(text)
            ColumnType.INTEGER, ColumnType.BIGINT ->
                DataValue.LongValue(
                    text.toLongOrNull() ?: throw IllegalArgumentException("$where: expected an integer for a $type column, found '$text'"),
                )
            ColumnType.DOUBLE ->
                DataValue.DoubleValue(
                    text.toDoubleOrNull() ?: throw IllegalArgumentException("$where: expected a number for a DOUBLE column, found '$text'"),
                )
            ColumnType.BOOLEAN ->
                when (text) {
                    "true" -> DataValue.BooleanValue(true)
                    "false" -> DataValue.BooleanValue(false)
                    else -> throw IllegalArgumentException("$where: expected true or false for a BOOLEAN column, found '$text'")
                }
            ColumnType.DATE, ColumnType.TIMESTAMP -> DataValue.TimestampValue(instant(text, type, where).toString())
        }
    }

    /** ISO-8601 instant, local date-time (read as UTC) or date (midnight UTC), the forms the standard's examples use. */
    private fun instant(
        text: String,
        type: ColumnType,
        where: String,
    ): Instant =
        runCatching { Instant.parse(text) }
            .recoverCatching { OffsetDateTime.parse(text).toInstant() }
            .recoverCatching { LocalDateTime.parse(text).toInstant(ZoneOffset.UTC) }
            .recoverCatching { LocalDate.parse(text).atStartOfDay(ZoneOffset.UTC).toInstant() }
            .getOrElse {
                throw IllegalArgumentException(
                    "$where: expected an ISO-8601 date or timestamp for a $type column, found '$text'",
                )
            }

    // ---- resolving -----------------------------------------------------------------------------------------

    /** Objects by `name` and by `id`, each with its properties by `name` and by `id`; both must be unambiguous. */
    private class Index(
        objects: List<SchemaObject>,
    ) {
        private val byName = unambiguous(objects, "name") { it.name }
        private val byId = unambiguous(objects.filter { it.id != null }, "id") { it.id!! }
        private val propertiesByName = objects.associateWith { o -> unambiguous(o.properties, "name", o.where) { it.name } }
        private val propertiesById =
            objects.associateWith { o ->
                unambiguous(o.properties.filter { it.id != null }, "id", o.where) { it.id!! }
            }

        /** The object and property a reference names; shorthand resolves by `name`, fully qualified by `id`. */
        fun resolve(
            reference: String,
            where: String,
        ): Pair<SchemaObject, Property> {
            require('#' !in reference) { "$where: reference '$reference' points into another contract, which is not supported" }
            FULLY_QUALIFIED.find(reference)?.let { match ->
                val (objectId, propertyId) = match.destructured
                val obj =
                    byId[objectId]
                        ?: throw IllegalArgumentException("$where: reference '$reference' names no schema object with id '$objectId'")
                val property =
                    propertiesById.getValue(obj)[propertyId]
                        ?: throw IllegalArgumentException(
                            "$where: reference '$reference' names no property with id '$propertyId' in '${obj.name}'",
                        )
                return obj to property
            }
            SHORTHAND.find(reference)?.let { match ->
                val (objectName, propertyName) = match.destructured
                val obj =
                    byName[objectName]
                        ?: throw IllegalArgumentException("$where: reference '$reference' names no schema object '$objectName'")
                val property =
                    propertiesByName.getValue(obj)[propertyName]
                        ?: throw IllegalArgumentException(
                            "$where: reference '$reference' names no property '$propertyName' in '$objectName'",
                        )
                return obj to property
            }
            throw IllegalArgumentException(
                "$where: reference '$reference' is neither shorthand 'object.property' nor fully qualified 'schema/<id>/properties/<id>' " +
                    "(nested properties are not supported)",
            )
        }

        private fun <T> unambiguous(
            items: List<T>,
            keyName: String,
            where: String? = null,
            key: (T) -> String,
        ): Map<String, T> {
            val duplicates = items.groupBy(key).filterValues { it.size > 1 }.keys
            require(duplicates.isEmpty()) {
                "${where?.let {
                    "$it: "
                }.orEmpty()}more than one ${if (where == null) "schema object" else "property"} has the $keyName ${duplicates.sorted()}"
            }
            return items.associateBy(key)
        }
    }

    private fun toTable(
        obj: SchemaObject,
        index: Index,
    ): TableDefinition {
        val primaryKey = primaryKey(obj)
        // `unique: true` on the only primary key property repeats what the key says; ODCS allows it, the model does not.
        val uniqueKeys = obj.properties.filter { it.unique && listOf(it.column.name) != primaryKey }.map { listOf(it.column.name) }
        val foreignKeys =
            obj.properties.flatMap { p ->
                p.relationships.mapIndexed { i, r -> propertyRelationship(p, r, "${p.where} relationships[$i]", index) }
            } +
                obj.relationships.mapIndexed { i, r -> objectRelationship(obj, r, "${obj.where} relationships[$i]", index) }
        return locating(obj.where) {
            TableDefinition(
                name = obj.tableName,
                description = obj.description,
                columns = obj.properties.map { it.column },
                primaryKey = primaryKey,
                uniqueKeys = uniqueKeys,
                foreignKeys = foreignKeys,
            )
        }
    }

    /** One primary key property needs no position; several need distinct positions, which give the key's column order. */
    private fun primaryKey(obj: SchemaObject): List<ColumnName> {
        val keyProperties = obj.properties.filter { it.primaryKey }
        if (keyProperties.size <= 1) return keyProperties.map { it.column.name }
        val missing = keyProperties.filter { it.primaryKeyPosition == NO_POSITION }
        require(missing.isEmpty()) {
            "${obj.where}: a composite primary key needs a primaryKeyPosition on every key property; missing on ${missing.map { it.name }}"
        }
        val positions = keyProperties.groupBy { it.primaryKeyPosition }.filterValues { it.size > 1 }
        require(positions.isEmpty()) {
            "${obj.where}: primary key properties share a primaryKeyPosition: ${positions.mapValues { (_, ps) -> ps.map { it.name } }}"
        }
        return keyProperties.sortedBy { it.primaryKeyPosition }.map { it.column.name }
    }

    private fun propertyRelationship(
        property: Property,
        relationship: YamlMap,
        where: String,
        index: Index,
    ): ForeignKey {
        relationship.checkKeys(RELATIONSHIP_KEYS, where)
        checkType(relationship, where)
        require(relationship.node("from") == null) { "$where: 'from' is implicit at property level and must not be given" }
        val to = relationship.node("to") ?: throw IllegalArgumentException("$where: a relationship needs 'to'")
        require(
            to is YamlScalar,
        ) { "$where: 'to' must be a single reference at property level; composite keys are object-level relationships" }
        val (target, targetProperty) = index.resolve(to.content, where)
        return locating(where) { ForeignKey(listOf(property.column.name), target.tableName, listOf(targetProperty.column.name)) }
    }

    private fun objectRelationship(
        obj: SchemaObject,
        relationship: YamlMap,
        where: String,
        index: Index,
    ): ForeignKey {
        relationship.checkKeys(RELATIONSHIP_KEYS, where)
        checkType(relationship, where)
        val from = relationship.node("from") ?: throw IllegalArgumentException("$where: an object-level relationship needs 'from'")
        val to = relationship.node("to") ?: throw IllegalArgumentException("$where: a relationship needs 'to'")
        require((from is YamlList) == (to is YamlList)) { "$where: 'from' and 'to' must both be single references or both be lists" }
        val fromRefs = from.references("$where from")
        val toRefs = to.references("$where to")
        require(fromRefs.size == toRefs.size) { "$where: 'from' has ${fromRefs.size} references but 'to' has ${toRefs.size}" }
        val sources = fromRefs.map { index.resolve(it, "$where from") }
        sources.forEach { (owner, property) ->
            require(owner === obj) { "$where: 'from' must name properties of '${obj.name}', found '${owner.name}.${property.name}'" }
        }
        val targets = toRefs.map { index.resolve(it, "$where to") }
        val targetObjects = targets.map { it.first }.distinct()
        require(
            targetObjects.size == 1,
        ) { "$where: 'to' must name properties of one schema object, found ${targetObjects.map { it.name }}" }
        return locating(where) {
            ForeignKey(sources.map { it.second.column.name }, targetObjects.single().tableName, targets.map { it.second.column.name })
        }
    }

    private fun checkType(
        relationship: YamlMap,
        where: String,
    ) {
        relationship
            .string(
                "type",
                where,
            )?.let { require(it == FOREIGN_KEY) { "$where: relationship type must be $FOREIGN_KEY, found '$it'" } }
    }

    // ---- YAML helpers --------------------------------------------------------------------------------------

    private fun YamlNode.asMap(where: String): YamlMap =
        this as? YamlMap ?: throw IllegalArgumentException("$where: expected a mapping at ${at()}")

    private fun YamlNode.asList(where: String): List<YamlNode> =
        (this as? YamlList)?.items ?: throw IllegalArgumentException("$where: expected a list at ${at()}")

    private fun YamlNode.references(where: String): List<String> =
        when (this) {
            is YamlScalar -> listOf(content)
            is YamlList ->
                items.map {
                    (it as? YamlScalar)?.content
                        ?: throw IllegalArgumentException("$where: expected a reference at ${it.at()}")
                }
            else -> throw IllegalArgumentException("$where: expected a reference or a list of references at ${at()}")
        }

    private fun YamlNode.at(): String = "line ${location.line}, column ${location.column}"

    /** The node under [key], with an explicit YAML `null` treated as absent. */
    private fun YamlMap.node(key: String): YamlNode? =
        entries.entries
            .firstOrNull { it.key.content == key }
            ?.value
            ?.takeUnless { it is YamlNull }

    private fun YamlMap.scalar(
        key: String,
        where: String,
    ): YamlScalar? =
        node(key)?.let {
            it as? YamlScalar
                ?: throw IllegalArgumentException("$where: '$key' must be a scalar, found a ${it.kind()} at ${it.at()}")
        }

    private fun YamlMap.string(
        key: String,
        where: String,
    ): String? = scalar(key, where)?.content

    private fun YamlMap.requiredString(
        key: String,
        where: String,
    ): String = string(key, where) ?: throw IllegalArgumentException("$where: '$key' is required")

    private fun YamlMap.boolean(
        key: String,
        where: String,
    ): Boolean? =
        scalar(key, where)?.let {
            when (it.content) {
                "true" -> true
                "false" -> false
                else -> throw IllegalArgumentException("$where: '$key' must be true or false, found '${it.content}'")
            }
        }

    private fun YamlMap.int(
        key: String,
        where: String,
    ): Int? =
        scalar(key, where)?.let {
            it.content.toIntOrNull()
                ?: throw IllegalArgumentException("$where: '$key' must be an integer, found '${it.content}'")
        }

    private fun YamlMap.checkKeys(
        allowed: Set<String>,
        where: String,
    ) {
        val unknown = entries.keys.map { it.content }.filterNot { it in allowed }
        require(
            unknown.isEmpty(),
        ) { "$where: unknown ${if (unknown.size == 1) "key" else "keys"} $unknown; ODCS allows ${allowed.sorted()}" }
    }

    private fun YamlNode.kind(): String =
        when (this) {
            is YamlMap -> "mapping"
            is YamlList -> "list"
            is YamlScalar -> "scalar"
            is YamlNull -> "null"
            else -> "node"
        }

    /** Prefixes the message of an [IllegalArgumentException] thrown by a model constructor with where in the contract it arose. */
    private inline fun <T> locating(
        where: String,
        construct: () -> T,
    ): T =
        try {
            construct()
        } catch (e: IllegalArgumentException) {
            throw IllegalArgumentException("$where: ${e.message}", e)
        }
}
