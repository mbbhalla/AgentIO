package io.github.mbbhalla.agentio.module.compass.model

import io.github.mbbhalla.agentio.core.common.Description
import io.github.mbbhalla.agentio.data.model.DataValue
import kotlinx.serialization.Serializable

@Serializable
data class AnalysisResult(
    @field:Description("Analysis result items — each item is grounded in SQL over the underlying data")
    val resultItems: Set<ResultItem>,
    @field:Description("Name of the dataset backing the analysis")
    val datasetName: String,
    @field:Description("Markdown-formatted analysis explanation for human consumption")
    val humanExplanation: String,
    @field:Description("Plain-text analysis explanation for downstream SMTLIB2 constraint generation")
    val smtlib2ConstraintGeneratorExplanation: String,
) {
    @Serializable
    data class ResultItem(
        @field:Description("Result item key name")
        val key: String,
        @field:Description("Result item value")
        val value: DataValue,
        @field:Description("Result item explanation comment")
        val comment: String,
        @field:Description(
            "SQL SELECT over the dataset tables that produces this result item value. " +
                "Must reference real dataset tables and columns and return a single scalar (one row, one column).",
        )
        val sql: String,
    )
}
