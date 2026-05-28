package io.github.mbbhalla.agentio.examples.adversarial.server

import io.github.mbbhalla.agentio.core.common.Description
import io.github.mbbhalla.agentio.core.lib.tool.AbstractMcpTool
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.jsonPrimitive

internal class ParseRequirementsTool : AbstractMcpTool<ParseRequirementsTool.Input, ParseRequirementsTool.Output>() {
    @Serializable
    data class Input(
        @field:Description("Raw requirements text to parse into structured elements")
        val requirementsText: String,
    )

    @Serializable
    data class Output(
        @field:Description("Extracted entities from the requirements")
        val entities: List<String>,
        @field:Description("Extracted operations or actions")
        val operations: List<String>,
        @field:Description("Identified constraints")
        val constraints: List<String>,
    )

    override fun name() = "parse_requirements"

    override fun description() = "Parse natural language requirements into structured entities, operations, and constraints"

    override fun getInputKClass() = Input::class

    override fun getOutputKClass() = Output::class

    override fun getToolConfig() = ToolConfig()

    override fun buildInput(callToolRequest: CallToolRequest): Input {
        val text =
            callToolRequest.params.arguments
                ?.get("requirementsText")
                ?.jsonPrimitive
                ?.content ?: ""
        return Input(requirementsText = text)
    }

    override fun invoke(input: Input): Output {
        val text = input.requirementsText.lowercase()
        val entityPatterns = listOf("user", "order", "product", "payment", "cart", "item", "account", "session", "address", "category")
        val operationPatterns = listOf("create", "read", "update", "delete", "list", "search", "filter", "sort", "paginate", "authenticate")
        val constraintPatterns = listOf("must", "required", "maximum", "minimum", "unique", "optional", "at least", "no more than")

        return Output(
            entities = entityPatterns.filter { text.contains(it) },
            operations = operationPatterns.filter { text.contains(it) },
            constraints =
                constraintPatterns.filter { text.contains(it) }.map { keyword ->
                    val idx = text.indexOf(keyword)
                    text.substring(idx, minOf(idx + 80, text.length)).trim()
                },
        )
    }
}

internal class ValidateSchemaConsistencyTool :
    AbstractMcpTool<ValidateSchemaConsistencyTool.Input, ValidateSchemaConsistencyTool.Output>() {
    @Serializable
    data class Input(
        @field:Description("JSON schema definition to validate for internal consistency")
        val schemaJson: String,
    )

    @Serializable
    data class Output(
        @field:Description("Whether the schema is internally consistent")
        val isConsistent: Boolean,
        @field:Description("Detected issues with the schema")
        val issues: List<String>,
        @field:Description("Suggestions for improvement")
        val suggestions: List<String>,
    )

    override fun name() = "validate_schema_consistency"

    override fun description() = "Check a JSON API schema for internal consistency, naming conventions, and REST best practices"

    override fun getInputKClass() = Input::class

    override fun getOutputKClass() = Output::class

    override fun getToolConfig() = ToolConfig()

    override fun buildInput(callToolRequest: CallToolRequest): Input {
        val schema =
            callToolRequest.params.arguments
                ?.get("schemaJson")
                ?.jsonPrimitive
                ?.content ?: ""
        return Input(schemaJson = schema)
    }

    override fun invoke(input: Input): Output {
        val issues = mutableListOf<String>()
        val suggestions = mutableListOf<String>()
        val schema = input.schemaJson

        if (!schema.contains("\"id\"") && !schema.contains("\"Id\"")) {
            issues.add("No 'id' field detected — resources typically need a unique identifier")
        }
        if (schema.contains("\"password\"") && !schema.contains("writeOnly")) {
            issues.add("Password field found without writeOnly annotation — sensitive fields should be write-only")
        }
        if (schema.contains("camelCase") && schema.contains("snake_case")) {
            issues.add("Mixed naming conventions detected (camelCase and snake_case)")
        }
        if (!schema.contains("\"createdAt\"") && !schema.contains("\"created_at\"")) {
            suggestions.add("Consider adding timestamp fields (createdAt, updatedAt) for audit trails")
        }
        if (!schema.contains("\"required\"")) {
            suggestions.add("Consider specifying required vs optional fields explicitly")
        }
        if (schema.contains("\"array\"") && !schema.contains("maxItems")) {
            suggestions.add("Array fields should specify maxItems to prevent unbounded responses")
        }

        return Output(
            isConsistent = issues.isEmpty(),
            issues = issues,
            suggestions = suggestions,
        )
    }
}

internal class CheckSecurityPatternsTool : AbstractMcpTool<CheckSecurityPatternsTool.Input, CheckSecurityPatternsTool.Output>() {
    @Serializable
    data class Input(
        @field:Description("API design text or schema to check for security concerns")
        val designText: String,
    )

    @Serializable
    data class Output(
        @field:Description("Security concerns found in the design")
        val concerns: List<String>,
        @field:Description("Security recommendations")
        val recommendations: List<String>,
        @field:Description("Risk level: LOW, MEDIUM, HIGH")
        val riskLevel: String,
    )

    override fun name() = "check_security_patterns"

    override fun description() = "Analyze an API design for security anti-patterns and recommend mitigations"

    override fun getInputKClass() = Input::class

    override fun getOutputKClass() = Output::class

    override fun getToolConfig() = ToolConfig()

    override fun buildInput(callToolRequest: CallToolRequest): Input {
        val text =
            callToolRequest.params.arguments
                ?.get("designText")
                ?.jsonPrimitive
                ?.content ?: ""
        return Input(designText = text)
    }

    override fun invoke(input: Input): Output {
        val text = input.designText.lowercase()
        val concerns = mutableListOf<String>()
        val recommendations = mutableListOf<String>()

        if (text.contains("password") && !text.contains("hash") && !text.contains("bcrypt")) {
            concerns.add("Password handling without explicit hashing strategy")
            recommendations.add("Specify password hashing algorithm (bcrypt, argon2)")
        }
        if (!text.contains("auth") && !text.contains("token") && !text.contains("jwt")) {
            concerns.add("No authentication mechanism specified")
            recommendations.add("Add authentication (OAuth2, JWT, API keys)")
        }
        if (!text.contains("rate") && !text.contains("throttl")) {
            concerns.add("No rate limiting mentioned")
            recommendations.add("Add rate limiting per client/endpoint")
        }
        if (text.contains("admin") && !text.contains("rbac") && !text.contains("role")) {
            concerns.add("Admin endpoints without explicit RBAC")
            recommendations.add("Implement role-based access control (RBAC)")
        }
        if (!text.contains("https") && !text.contains("tls") && !text.contains("ssl")) {
            recommendations.add("Specify TLS requirement for all endpoints")
        }

        val riskLevel =
            when {
                concerns.size >= 3 -> "HIGH"
                concerns.size >= 1 -> "MEDIUM"
                else -> "LOW"
            }

        return Output(
            concerns = concerns,
            recommendations = recommendations,
            riskLevel = riskLevel,
        )
    }
}
