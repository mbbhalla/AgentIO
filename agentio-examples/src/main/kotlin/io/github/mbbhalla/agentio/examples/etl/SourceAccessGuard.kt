package io.github.mbbhalla.agentio.examples.etl

import io.github.mbbhalla.agentio.data.env.DuckDbDatabaseEnvironment
import io.github.mbbhalla.agentio.data.model.ColumnName
import io.github.mbbhalla.agentio.data.model.DataValue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.InvalidPathException
import java.nio.file.Path

/**
 * Lets SQL written by an agent read only the run's source files and the target database.
 *
 * DuckDB reads any file the process can (`read_text('/etc/passwd')`, `FROM '~/.aws/credentials'`), and
 * the agents writing the SQL also read CSV content, which is untrusted input. Before agent SQL is bound
 * or run, every table source in DuckDB's own parse tree (`json_serialize_sql`, which parses without
 * binding or reading anything) is checked:
 *
 * | Source                                         | Allowed when                                        |
 * |------------------------------------------------|-----------------------------------------------------|
 * | `read_csv`, `read_csv_auto`, `sniff_csv`       | the first argument is a string literal naming a [SourceFiles] path |
 * | `unnest`, `generate_series`, `range`           | always; they read no files                          |
 * | table or CTE by name                           | the name is an identifier, not a file path          |
 * | joins, subqueries, `VALUES`, `PIVOT`           | always; their own sources are checked               |
 *
 * Everything else, including other table functions and computed paths, is rejected.
 */
internal class SourceAccessGuard(
    private val env: DuckDbDatabaseEnvironment,
    private val sources: SourceFiles,
) {
    /** Fails with [IllegalArgumentException] naming every disallowed source, or when [sql] is not one SELECT. */
    fun check(sql: String): Result<Unit> =
        runCatching {
            val body = EtlWorkspace.body(sql)
            // DuckDbDatabaseEnvironment rejects every ';' but a trailing one, even inside a string, so that no
            // second statement can run; say so here rather than let it surface as a parser failure.
            require(';' !in body) { "SQL must not contain ';' anywhere, not even in a string; write chr(59) for a semicolon" }
            val violations = tableSources(parse(body)).mapNotNull { violation(it) }.distinct().toList()
            require(violations.isEmpty()) { "SQL reads disallowed sources: ${violations.joinToString("; ")}" }
        }

    private fun parse(sql: String): JsonElement {
        val statement =
            env
                .selectStatement("SELECT CAST(json_serialize_sql(${sqlStringLiteral(sql)}) AS VARCHAR) AS $TREE_COLUMN")
                .getOrElse { throw IllegalStateException("DuckDB could not run its SQL parser: ${it.message}", it) }
        val tree = env.executeQuery(statement).records.single()[ColumnName(TREE_COLUMN)]
        check(tree is DataValue.StringValue) { "json_serialize_sql returned $tree" }
        val parsed = Json.parseToJsonElement(tree.value).jsonObject
        require(parsed["error"]?.jsonPrimitive?.booleanOrNull != true) {
            "Not a single SELECT: ${parsed["error_message"]?.jsonPrimitive?.content}"
        }
        val statements = parsed.getValue("statements").jsonArray
        require(statements.size == 1) { "Exactly one SELECT statement is required, found ${statements.size}" }
        return statements.single()
    }

    /** Table sources and query nodes: in DuckDB's parse tree, the objects that carry `sample` and no expression `class`. */
    private fun tableSources(tree: JsonElement): Sequence<JsonObject> =
        when (tree) {
            is JsonArray -> tree.asSequence().flatMap { tableSources(it) }
            is JsonObject -> {
                val nested = tree.values.asSequence().flatMap { tableSources(it) }
                if (SAMPLE_KEY in tree && CLASS_KEY !in tree) sequenceOf(tree) + nested else nested
            }
            is JsonPrimitive -> emptySequence()
        }

    private fun violation(source: JsonObject): String? =
        when (val type = source.text(TYPE_KEY)) {
            BASE_TABLE -> source.text(TABLE_NAME_KEY).takeUnless { IDENTIFIER.matches(it) }?.let { "'$it' is read as a file" }
            TABLE_FUNCTION -> tableFunctionViolation(source.getValue(FUNCTION_KEY).jsonObject)
            in COMPOSITE_SOURCES -> null
            else -> "table source $type is not permitted"
        }

    private fun tableFunctionViolation(function: JsonObject): String? {
        val name = function.text(FUNCTION_NAME_KEY).lowercase()
        return when (name) {
            in GENERATORS -> null
            in FILE_READERS -> pathViolation(name, function)
            else -> "table function $name is not permitted; read source files with read_csv('<absolute path>')"
        }
    }

    private fun pathViolation(
        function: String,
        node: JsonObject,
    ): String? {
        val first =
            node[ARGUMENTS_KEY]
                ?.jsonArray
                ?.firstOrNull()
                ?.jsonObject
                ?.get(EXPRESSION_KEY)
                ?.jsonObject
        val literal = first?.takeIf { it.text(TYPE_KEY) == VALUE_CONSTANT }?.get(LITERAL_KEY)?.jsonObject
        val path =
            literal?.takeIf { it.text(KIND_KEY) == STRING_KIND }?.text(TEXT_KEY)
                ?: return "$function must read one source file named by a string literal"
        val resolved =
            try {
                Path.of(path)
            } catch (_: InvalidPathException) {
                return "$function path '$path' is not a file path"
            }
        return when {
            !resolved.isAbsolute -> "$function path '$path' must be absolute"
            !sources.isSource(resolved) -> "$function path '$path' is not one of the source files ${sources.names} in ${sources.directory}"
            else -> null
        }
    }

    private fun JsonObject.text(key: String): String =
        requireNotNull(this[key] as? JsonPrimitive) { "Unexpected DuckDB parse tree: no '$key' in a ${this[TYPE_KEY]} node" }.content

    companion object {
        private const val TREE_COLUMN = "tree"
        private const val SAMPLE_KEY = "sample"
        private const val CLASS_KEY = "class"
        private const val TYPE_KEY = "type"
        private const val TABLE_NAME_KEY = "table_name"
        private const val FUNCTION_KEY = "function"
        private const val FUNCTION_NAME_KEY = "function_name"
        private const val ARGUMENTS_KEY = "arguments"
        private const val EXPRESSION_KEY = "expression"
        private const val LITERAL_KEY = "literal"
        private const val KIND_KEY = "kind"
        private const val TEXT_KEY = "text"
        private const val STRING_KIND = "STRING"
        private const val VALUE_CONSTANT = "VALUE_CONSTANT"
        private const val BASE_TABLE = "BASE_TABLE"
        private const val TABLE_FUNCTION = "TABLE_FUNCTION"

        /** A table or CTE name; anything else in a `BASE_TABLE` is a replacement scan of a file. */
        private val IDENTIFIER = Regex("[A-Za-z_][A-Za-z0-9_]*")
        private val FILE_READERS = setOf("read_csv", "read_csv_auto", "sniff_csv")
        private val GENERATORS = setOf("unnest", "generate_series", "range")

        /** Query nodes and table sources whose own inputs are separate nodes in the tree. */
        private val COMPOSITE_SOURCES =
            setOf(
                "SELECT_NODE",
                "SET_OPERATION_NODE",
                "RECURSIVE_CTE_NODE",
                "CTE_NODE",
                "JOIN",
                "SUBQUERY",
                "EXPRESSION_LIST",
                "EMPTY",
                "PIVOT",
            )

        /** A SQL string literal: quotes doubled, which is DuckDB's only escape in a standard string. */
        fun sqlStringLiteral(text: String): String = "'${text.replace("'", "''")}'"
    }
}
