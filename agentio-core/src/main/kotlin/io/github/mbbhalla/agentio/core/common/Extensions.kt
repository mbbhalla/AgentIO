package io.github.mbbhalla.agentio.core.common

import aws.smithy.kotlin.runtime.content.Document
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.types.Tool
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.float
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.long
import kotlinx.serialization.json.longOrNull

/*
    Convert JsonObject (type used in MCP Kotlin SDK) to Document (type used in Converse API)
    https://docs.aws.amazon.com/smithy-kotlin/api/latest/runtime-core/aws.smithy.kotlin.runtime.content/-document/
 */
internal fun JsonObject.toDocument(): Document {
    fun JsonElement.toDocument(): Document =
        when (this) {
            // Handle primitive types
            is JsonPrimitive -> {
                when {
                    this.isString -> Document.String(this.content)
                    this.intOrNull != null -> Document.Number(this.int)
                    this.longOrNull != null -> Document.Number(this.long)
                    this.doubleOrNull != null -> Document.Number(this.double)
                    this.floatOrNull != null -> Document.Number(this.float)
                    this.booleanOrNull != null -> Document.Boolean(this.boolean)
                    else -> Document.String(this.toString()) // Fallback for other primitives
                }
            }

            // Handle JSON objects recursively
            is JsonObject -> {
                Document.Map(
                    value =
                        this.entries.associate {
                            it.key to it.value.toDocument()
                        },
                )
            }

            // Handle JSON arrays recursively
            is JsonArray -> {
                val documentList = this.map { it.toDocument() }
                Document.List(value = documentList)
            }
        }
    return (this as JsonElement).toDocument()
}

/*
    Convert Document (type used in Converse API) to JsonObject (type used in MCP Kotlin SDK)
    https://docs.aws.amazon.com/smithy-kotlin/api/latest/runtime-core/aws.smithy.kotlin.runtime.content/-document/
 */
@Suppress("CyclomaticComplexMethod")
internal fun Document.toJsonObject(): JsonObject {
    fun Document.toJsonElement(): JsonElement =
        when (this) {
            is Document.String -> this.asStringOrNull()?.let { JsonPrimitive(it) } ?: JsonNull
            is Document.Number ->
                when (this.value) {
                    is Float -> JsonPrimitive(this.value.toFloat())
                    is Double -> JsonPrimitive(this.value.toDouble())
                    is Int -> JsonPrimitive(this.value.toInt())
                    is Long -> JsonPrimitive(this.value.toLong())
                    else -> JsonNull
                }
            is Document.Boolean -> this.asBooleanOrNull()?.let { JsonPrimitive(it) } ?: JsonNull
            is Document.List ->
                JsonArray(
                    this.value.map {
                        it?.toJsonElement() ?: JsonNull
                    },
                )
            is Document.Map ->
                JsonObject(
                    content =
                        this.value.entries.associate {
                            it.key to (it.value?.toJsonElement() ?: JsonNull)
                        },
                )
        }
    return this.toJsonElement().jsonObject
}

suspend fun Client.listToolsAndSkipDenyList(deniedTools: Set<String>): List<Tool> =
    this
        .listTools()
        .tools
        .filterNot { deniedTools.contains(it.name) }
