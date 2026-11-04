package io.github.mbbhalla.agentio.module.text2sql.authz

import io.github.mbbhalla.agentio.data.env.DatabaseEnvironment
import io.github.mbbhalla.agentio.data.env.SelectSqlStatement
import io.github.mbbhalla.agentio.data.model.TableName
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Rewrites a `SELECT` so that every base table it reads is replaced by a common table expression
 * that applies the caller's row-level [Criteria]:
 *
 * ```
 * SELECT * FROM inventory                            -- input
 * WITH authz_inventory AS (                          -- output
 *   SELECT * FROM inventory WHERE (("site" = 'TX') OR ("site" = 'CA'))
 * ) SELECT * FROM authz_inventory AS inventory
 * ```
 *
 * DuckDB itself parses the query and renders the rewrite ([DatabaseEnvironment.parseSelect],
 * [DatabaseEnvironment.renderSelect]) on the environment that validated the input statement, so the
 * tables the rewrite filters are exactly the tables DuckDB reads.
 *
 * Guarantees:
 *  - the output is a [SelectSqlStatement] of that same environment, so it binds there and
 *    [DatabaseEnvironment.executeQuery] refuses to run it anywhere else;
 *  - every read of a base table sees only the rows its policy allows: filtering is applied at the
 *    base-table boundary, before any join, aggregation or set operation;
 *  - a table without a policy fails the request unless the context opts into
 *    [UnlistedTablePolicy.ALLOW_ALL_ROWS];
 *  - sources that read data without naming a table are rejected: table functions (`query_table`,
 *    `read_csv`, ...), `SUMMARIZE` / `DESCRIBE`, and file paths (`FROM 'data.csv'`);
 *  - CTE references are resolved per scope the way DuckDB resolves them, so a `WITH` cannot shadow a
 *    base table to escape its policy.
 *
 * Table aliases are preserved; when a table had no alias its written name is installed as the alias
 * so that qualified column references such as `inventory.site` keep resolving.
 */
object SqlAuthorizer {
    /** An unauthorized table, a rejected table source or an unrenderable policy is an [IllegalArgumentException] failure. */
    fun authorize(
        sql: SelectSqlStatement,
        context: AuthorizationContext,
    ): Result<AuthorizedSql> =
        try {
            Result.success(rewrite(sql, context))
        } catch (e: IllegalArgumentException) {
            Result.failure(e)
        }

    private fun rewrite(
        sql: SelectSqlStatement,
        context: AuthorizationContext,
    ): AuthorizedSql {
        val env: DatabaseEnvironment = sql.environment
        val query = env.parseSelect(sql.value)
        val namesInQuery = strings(query).toSet()
        val cteNames = linkedMapOf<List<String>, String>() // lower-cased table path -> CTE that filters it
        val unfiltered = mutableSetOf<TableName>()
        val rewritten =
            mapBaseTables(query, visibleCtes = emptySet()) { baseTable ->
                val path = baseTable.path().map(String::lowercase)
                val table = tableOf(path)
                when {
                    table in context.policies ->
                        baseTable.readingFrom(cteNames.getOrPut(path) { uniqueName(table, namesInQuery + cteNames.values) })
                    context.unlistedTablePolicy == UnlistedTablePolicy.ALLOW_ALL_ROWS -> baseTable.also { unfiltered += table }
                    else -> throw IllegalArgumentException("Table '${table.value}' is not authorized")
                }
            }.jsonObject
        val filteringCtes =
            cteNames.map { (path, name) ->
                env.parseSelect(cteSql(name, path, context.policies.getValue(tableOf(path)))).cteEntries().single()
            }
        val rendered = env.renderSelect(rewritten.withCteEntries(filteringCtes + rewritten.cteEntries()))
        return AuthorizedSql(
            sql = env.selectStatement(rendered).getOrThrow(),
            filteredTables = cteNames.keys.map(::tableOf).toSet(),
            unfilteredTables = unfiltered,
        )
    }

    /**
     * Applies [transform] to every `BASE_TABLE` in [tree] that does not name a CTE visible at that point.
     * Query nodes (objects with a `cte_map`) open a scope; any other table source must be readable.
     */
    private fun mapBaseTables(
        tree: JsonElement,
        visibleCtes: Set<String>,
        transform: (JsonObject) -> JsonObject,
    ): JsonElement =
        when {
            tree is JsonArray -> JsonArray(tree.map { mapBaseTables(it, visibleCtes, transform) })
            tree !is JsonObject -> tree
            "cte_map" in tree -> mapQueryNode(tree, visibleCtes, transform)
            else -> {
                val node = JsonObject(tree.mapValues { mapBaseTables(it.value, visibleCtes, transform) })
                when {
                    "sample" !in node || "class" in node -> node // not a table source
                    node.type() != "BASE_TABLE" ->
                        node.also { require(it.type() in READABLE_TABLE_SOURCES) { "Table source ${it.type()} is not permitted" } }
                    node.path().size == 1 && node.path().single().lowercase() in visibleCtes -> node
                    else -> transform(node)
                }
            }
        }

    /**
     * DuckDB's CTE scoping: a CTE is visible to the later CTEs of its `WITH`, to the query body and to
     * everything nested in them. A recursive CTE also sees itself in its recursive term (`right`), but
     * not in its anchor (`left`), where DuckDB resolves the name to the base table.
     */
    private fun mapQueryNode(
        node: JsonObject,
        visibleCtes: Set<String>,
        transform: (JsonObject) -> JsonObject,
    ): JsonObject {
        val ctes = node.cteEntries()
        val scopes = ctes.runningFold(visibleCtes) { scope, cte -> scope + cte.lowercase("key") }
        val bodyScope = scopes.last()
        val recursiveTermScope = if (node.type() == "RECURSIVE_CTE_NODE") bodyScope + node.lowercase("cte_name") else bodyScope
        return JsonObject(
            node.mapValues { (key, value) ->
                when (key) {
                    "cte_map" -> cteMap(ctes.mapIndexed { i, cte -> mapBaseTables(cte, scopes[i], transform).jsonObject })
                    "right" -> mapBaseTables(value, recursiveTermScope, transform)
                    else -> mapBaseTables(value, bodyScope, transform)
                }
            },
        )
    }

    private fun JsonObject.readingFrom(cteName: String): JsonObject =
        JsonObject(
            this +
                mapOf(
                    "catalog_name" to JsonPrimitive(""),
                    "schema_name" to JsonPrimitive(""),
                    "table_name" to JsonPrimitive(cteName),
                    "qualified_name" to JsonObject(mapOf("path" to JsonArray(listOf(JsonPrimitive(cteName))))),
                    "alias" to JsonPrimitive(getValue("alias").jsonPrimitive.content.ifEmpty { path().last() }),
                ),
        )

    private fun cteSql(
        name: String,
        path: List<String>,
        criteria: Criteria,
    ): String {
        val where = if (criteria == Criteria.AllRows) "" else " WHERE ${CriteriaRenderer.render(criteria)}"
        return "WITH $name AS (SELECT * FROM ${path.joinToString(".", transform = CriteriaRenderer::identifier)}$where) SELECT 1"
    }

    /** A name that equals no string in the query, so it can neither shadow nor be shadowed by anything in it. */
    private fun uniqueName(
        table: TableName,
        taken: Set<String>,
    ): String =
        generateSequence(1) { it + 1 }
            .map { n -> if (n == 1) "authz_${table.value}" else "authz_${table.value}_$n" }
            .first { it !in taken }

    /** Fails for names that cannot be AgentIO tables, such as file paths read by replacement scans. */
    private fun tableOf(path: List<String>): TableName = TableName(path.last())

    private fun strings(tree: JsonElement): Sequence<String> =
        when (tree) {
            is JsonArray -> tree.asSequence().flatMap { strings(it) }
            is JsonObject -> tree.values.asSequence().flatMap { strings(it) }
            is JsonPrimitive -> if (tree.isString) sequenceOf(tree.content.lowercase()) else emptySequence()
        }

    private fun JsonObject.type(): String? = (this["type"] as? JsonPrimitive)?.content

    private fun JsonObject.lowercase(key: String): String = getValue(key).jsonPrimitive.content.lowercase()

    private fun JsonObject.path(): List<String> =
        getValue("qualified_name").jsonObject.getValue("path").jsonArray.map {
            it.jsonPrimitive.content
        }

    private fun JsonObject.cteEntries(): List<JsonObject> =
        getValue("cte_map")
            .jsonObject
            .getValue("map")
            .jsonArray
            .map { it.jsonObject }

    private fun JsonObject.withCteEntries(entries: List<JsonObject>): JsonObject = JsonObject(this + ("cte_map" to cteMap(entries)))

    private fun cteMap(entries: List<JsonObject>): JsonObject = JsonObject(mapOf("map" to JsonArray(entries)))

    /** Table sources that only read what they contain; `BASE_TABLE` is handled separately. */
    private val READABLE_TABLE_SOURCES = setOf("JOIN", "SUBQUERY", "EXPRESSION_LIST", "EMPTY", "PIVOT")
}
