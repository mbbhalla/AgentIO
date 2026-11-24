package io.github.mbbhalla.agentio.examples.etl

import io.github.mbbhalla.agentio.core.common.Description
import io.github.mbbhalla.agentio.data.model.ColumnType
import kotlinx.serialization.Serializable

/** The planner's answer: which target tables the sources feed, in what order, and what links them. */
@Serializable
data class LoadPlan(
    @field:Description(
        "Target tables to load, in load order. A table that a step joins or references must be loaded by an earlier step.",
    )
    val steps: List<LoadStep>,
    @field:Description(
        "Column references between planned tables that the target descriptions state, such as 'Foreign key to " +
            "Employee.BusinessEntityID'. Each one is checked for orphaned rows when its fromTable is loaded.",
    )
    val references: List<Reference>,
    @field:Description("Planning decisions the sources and descriptions do not settle, for the reviewer; empty when there are none")
    val questions: List<RaisedQuestion> = emptyList(),
)

/** A choice an agent could not settle from the sources and the target's descriptions. */
@Serializable
data class RaisedQuestion(
    @field:Description("What has to be decided, as one question naming the target table and columns")
    val question: String,
    @field:Description("The facts behind it: source values with counts, the target column's description, any DuckDB error")
    val evidence: String,
    @field:Description("Two to four distinct choices")
    val options: List<String>,
    @field:Description(
        "0-based index of the option your answer uses until the reviewer decides; null only when no SQL can pass without an answer",
    )
    val assumedOption: Int?,
) {
    /** Why the question cannot be put to a reviewer, or empty. */
    fun problems(): List<String> =
        listOfNotNull(
            "a question needs text".takeIf { question.isBlank() },
            "'$question' needs two to four options, found ${options.size}".takeIf { options.size !in OPTION_COUNT },
            "'$question' has a blank or repeated option".takeIf { options.any { it.isBlank() } || options.distinct().size != options.size },
            "'$question' assumes option $assumedOption, which does not exist".takeIf {
                assumedOption != null &&
                    assumedOption !in options.indices
            },
        )

    companion object {
        private val OPTION_COUNT = 2..4
    }
}

/** A reviewer's answer, as the agents receive it. */
@Serializable
data class DecidedQuestion(
    val question: String,
    val answer: String,
)

@Serializable
data class LoadStep(
    @field:Description("Target table name, lower case")
    val targetTable: String,
    @field:Description("Names of the source CSV files that feed this table")
    val sourceFiles: List<String>,
    @field:Description(
        "How the table is derived: its grain, how source rows become target rows, keys and lookups, and the " +
            "transformation of each column",
    )
    val mapping: String,
)

/** `fromTable.fromColumn` holds values of `toTable.toColumn`; a non-null value with no match is an orphan. */
@Serializable
data class Reference(
    val fromTable: String,
    val fromColumn: String,
    val toTable: String,
    val toColumn: String,
) {
    override fun toString(): String = "$fromTable.$fromColumn -> $toTable.$toColumn"
}

@Serializable
data class TargetTableSummary(
    val name: String,
    val description: String,
    val columnCount: Int,
)

@Serializable
data class TargetTableDetail(
    val name: String,
    val description: String,
    @field:Description("Rows loaded so far")
    val rowCount: Long,
    val columns: List<TargetColumn>,
    @field:Description("The CREATE TABLE statement DuckDB enforces, with keys and CHECK constraints")
    val ddl: String,
)

@Serializable
data class TargetColumn(
    val name: String,
    val type: ColumnType,
    val nullable: Boolean,
    val primaryKey: Boolean,
    val description: String,
)

/** What DuckDB's CSV sniffer reads from a source file, with the first rows as text. */
@Serializable
data class SourceProfile(
    val fileName: String,
    @field:Description("Absolute path to pass to read_csv")
    val path: String,
    @field:Description("The first lines of the file exactly as written, including any lines before the header")
    val firstLines: List<String>,
    val delimiter: String,
    val skipRows: Long,
    val hasHeader: Boolean,
    @field:Description("Date format the sniffer detected, if any")
    val dateFormat: String?,
    @field:Description("Timestamp format the sniffer detected, if any")
    val timestampFormat: String?,
    @field:Description("Header names with the types the sniffer guessed; guesses, not facts")
    val columns: List<SourceColumn>,
    val rowCount: Long,
    @field:Description("The first rows as JSON objects, every value read as text")
    val sampleRows: List<String>,
)

@Serializable
data class SourceColumn(
    val name: String,
    val sniffedType: String,
)

@Serializable
data class QueryResult(
    @field:Description("Rows the query returns in total")
    val rowCount: Long,
    @field:Description("Up to the first rows, as JSON objects")
    val rows: List<String>,
)

/** The SQL an agent wrote for one target table, as the pipeline loaded it. */
@Serializable
data class LoadedTable(
    val table: String,
    val selectSql: String,
    val explanation: String,
    val rows: Int,
    val attempts: Int,
)

/** A planned reference and the non-null values in it with no match, after the load. */
@Serializable
data class ReferenceCheck(
    val reference: Reference,
    val orphanedRows: Long,
)

/**
 * One round's mapping as the reviewer sees it: the plan, the tables loaded in order, and where the round
 * stopped when a table could not be loaded. It holds the SQL, so an approved proposal becomes `etl.sql`
 * without the agents running again.
 */
@Serializable
data class EtlProposal(
    val plan: LoadPlan,
    val loaded: List<LoadedTable>,
    @field:Description("The table the round could not load, and why; null when every planned table loaded")
    val stoppedAt: String?,
    val references: List<ReferenceCheck>,
) {
    /** Every planned table loaded and no reference has an orphan. */
    val complete: Boolean get() = stoppedAt == null && references.all { it.orphanedRows == 0L }
}

/** The outcome of inserting a SELECT into its target table and removing the rows again. */
@Serializable
data class LoadTrial(
    val table: String,
    val insertedRows: Int,
    @field:Description("NULL count per column, for columns that received any NULL")
    val nullCounts: Map<String, Long>,
    @field:Description("References from this table whose values have no match in the referenced table, with the count")
    val orphanedReferences: List<String>,
    @field:Description("A few inserted rows, as JSON objects")
    val sampleRows: List<String>,
)
