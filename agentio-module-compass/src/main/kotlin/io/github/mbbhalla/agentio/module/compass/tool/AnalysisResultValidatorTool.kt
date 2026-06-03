package io.github.mbbhalla.agentio.module.compass.tool

import io.github.mbbhalla.agentio.core.common.Description
import io.github.mbbhalla.agentio.core.common.JsonSchemaUtil
import io.github.mbbhalla.agentio.core.lib.tool.AbstractMcpTool
import io.github.mbbhalla.agentio.data.env.DatabaseEnvironment
import io.github.mbbhalla.agentio.data.env.SelectSqlStatement
import io.github.mbbhalla.agentio.data.model.DataValue
import io.github.mbbhalla.agentio.module.compass.model.AnalysisResult
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory

/**
 * Validates an [AnalysisResult] against an active database environment:
 * - Each ResultItem.sql must be a valid SELECT.
 * - Each SQL must return exactly one row, one column.
 * - The returned scalar must match the ResultItem.value (numerically, ignoring Long↔Double).
 */
class AnalysisResultValidatorTool(
    private val env: DatabaseEnvironment,
) : AbstractMcpTool<AnalysisResultValidatorTool.Input, AnalysisResultValidatorTool.Output>() {
    @Serializable
    data class Input(
        @field:Description("JSON-encoded AnalysisResult object to validate")
        val analysisResultJson: String,
    )

    @Serializable
    data class Output(
        @field:Description("True if validation passed, False if any check failed")
        val valid: Boolean,
        @field:Description("Error description when valid = False; null otherwise")
        val error: String?,
    )

    override fun name() = "analysis_result_validator"

    override fun description() =
        "Validates an AnalysisResult JSON: every result item must have a SELECT SQL " +
            "that returns the same scalar value"

    override fun getInputKClass() = Input::class

    override fun getOutputKClass() = Output::class

    override fun getToolConfig() = ToolConfig(emitSchemaAndRequiredAttributesForAllToolCalls = true)

    override fun buildInput(callToolRequest: CallToolRequest): Input =
        Input(
            analysisResultJson =
                callToolRequest.params.arguments
                    ?.get("analysisResultJson")
                    ?.jsonPrimitive
                    ?.content
                    ?: throw IllegalArgumentException("'analysisResultJson' is required"),
        )

    override fun invoke(input: Input): Output {
        LOG.info("Validating AnalysisResult JSON of length {}", input.analysisResultJson.length)

        return runCatching {
            val parsed =
                runCatching { JsonSchemaUtil.json.decodeFromString(AnalysisResult.serializer(), input.analysisResultJson) }
                    .recoverCatching {
                        JsonSchemaUtil.json
                            .decodeFromString(Container.serializer(), input.analysisResultJson)
                            .analysisResult
                    }.getOrElse { throwable ->
                        val schemaJson =
                            JsonSchemaUtil.json.encodeToString(
                                JsonObject.serializer(),
                                JsonSchemaUtil.generateJsonSchema(AnalysisResult::class),
                            )
                        throw IllegalArgumentException(
                            "Cannot parse JSON as AnalysisResult. Expected schema: $schemaJson. Underlying error: ${throwable.message}",
                            throwable,
                        )
                    }

            parsed.resultItems.forEach { item -> validateResultItem(item) }
        }.fold(
            onSuccess = { Output(valid = true, error = null) },
            onFailure = { Output(valid = false, error = it.message ?: it.javaClass.simpleName) },
        )
    }

    private fun validateResultItem(item: AnalysisResult.ResultItem) {
        val select =
            runCatching { SelectSqlStatement(item.sql) }
                .getOrElse { throw IllegalArgumentException("ResultItem '${item.key}': SQL is not a valid SELECT — ${it.message}") }

        val dataset = env.executeQuery(select)

        check(dataset.records.size == 1) {
            "ResultItem '${item.key}': SQL must return exactly one row but returned ${dataset.records.size}"
        }
        val row = dataset.records.single()
        check(row.values.size == 1) {
            "ResultItem '${item.key}': SQL must return exactly one column but returned ${row.values.size}"
        }
        val actual = row.values.values.single()
        check(valuesMatch(actual = actual, expected = item.value)) {
            "ResultItem '${item.key}': SQL returned $actual but result item value is ${item.value}"
        }
    }

    private fun valuesMatch(
        actual: DataValue,
        expected: DataValue,
    ): Boolean {
        if (actual == expected) return true
        val actualAsDouble = (actual as? DataValue.LongValue)?.value?.toDouble() ?: (actual as? DataValue.DoubleValue)?.value
        val expectedAsDouble = (expected as? DataValue.LongValue)?.value?.toDouble() ?: (expected as? DataValue.DoubleValue)?.value
        return actualAsDouble != null && expectedAsDouble != null && actualAsDouble == expectedAsDouble
    }

    @Serializable
    private data class Container(
        val analysisResult: AnalysisResult,
    )

    companion object {
        private val LOG = LoggerFactory.getLogger(AnalysisResultValidatorTool::class.java)
    }
}
