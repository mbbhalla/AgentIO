package io.github.mbbhalla.agentio.core.common

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.github.victools.jsonschema.generator.Option
import com.github.victools.jsonschema.generator.OptionPreset
import com.github.victools.jsonschema.generator.SchemaGenerator
import com.github.victools.jsonschema.generator.SchemaGeneratorConfigBuilder
import com.github.victools.jsonschema.generator.SchemaVersion
import com.github.victools.jsonschema.module.jakarta.validation.JakartaValidationModule
import com.github.victools.jsonschema.module.jakarta.validation.JakartaValidationOption
import com.networknt.schema.InputFormat
import com.networknt.schema.JsonSchemaFactory
import com.networknt.schema.SpecVersion
import com.networknt.schema.ValidationMessage
import io.github.mbbhalla.agentio.core.common.JsonSchemaUtil.json
import io.vavr.kotlin.Try
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.reflect.KClass
import kotlin.reflect.full.isSuperclassOf
import kotlin.reflect.full.memberProperties
import kotlin.reflect.jvm.javaField
import kotlin.reflect.jvm.jvmErasure

object JsonSchemaUtil {
    val json by lazy {
        Json {
            ignoreUnknownKeys = true
            prettyPrint = true
            isLenient = true
        }
    }

    val jacksonObjectMapper by lazy {
        jacksonObjectMapper().apply {
            setSerializationInclusion(JsonInclude.Include.NON_NULL)
        }
    }

    private val jsonSchemaGenerator by lazy {
        val configBuilder =
            SchemaGeneratorConfigBuilder(
                SchemaVersion.DRAFT_2020_12,
                OptionPreset.PLAIN_JSON,
            ).with(Option.DEFINITIONS_FOR_ALL_OBJECTS)
                .with(
                    JakartaValidationModule(
                        JakartaValidationOption.INCLUDE_PATTERN_EXPRESSIONS,
                    ),
                )

        configBuilder
            .forFields()
            .withTitleResolver {
                it.getAnnotation(Title::class.java)?.value
            }.withDescriptionResolver {
                it.getAnnotation(Description::class.java)?.value
            }.withRequiredCheck { fs ->
                fs.declaringType.erasedType.kotlin.memberProperties
                    .single { it.name == fs.name }
                    .returnType.isMarkedNullable
                    .not()
            }
        configBuilder
            .forTypesInGeneral()
            .withTitleResolver {
                it.type.erasedType
                    .getAnnotation(Title::class.java)
                    ?.value
            }.withDescriptionResolver {
                it.type.erasedType
                    .getAnnotation(Description::class.java)
                    ?.value
            }

        SchemaGenerator(configBuilder.build())
    }

    /**
     * Generates a standard JSON Schema for a given KClass
     * https://json-schema.org/draft/2020-12/schema
     */
    fun <T : Any> generateJsonSchema(kClass: KClass<T>): JsonObject =
        json
            .parseToJsonElement(
                jsonSchemaGenerator.generateSchema(kClass.java).toString(),
            ).jsonObject

    fun validateJsonWithSchema(
        json: JsonString,
        schema: JsonString,
    ): Set<ValidationMessage> {
        val errors =
            JsonSchemaFactory
                .getInstance(SpecVersion.VersionFlag.V202012)
                .getSchema(schema.value)
                .validate(json.value, InputFormat.JSON)
        return errors
    }

    /**
     * Generates a schema-like JsonObject for a given KClass
     */
    @Suppress(
        "LongMethod",
        "CyclomaticComplexMethod",
        "NestedBlockDepth",
    )
    fun <T : Any> generateSchemaJsonObject(
        kClass: KClass<T>,
        nonNullables: MutableSet<String>,
    ): JsonObject {
        val propertiesMap = mutableMapOf<String, JsonElement>()

        for (property in kClass.memberProperties) {
            val propertyName = property.name
            val propertyType = property.returnType
            val propertyMap = mutableMapOf<String, JsonElement>()

            // Get description from the Java field annotation
            val description = property.javaField?.getAnnotation(Description::class.java)?.value
            description?.let { propertyMap["description"] = JsonPrimitive(it) }

            // Track non-nullable properties
            if (!propertyType.isMarkedNullable) {
                nonNullables.add(propertyName)
            }

            // Add type information
            val typeClass = propertyType.jvmErasure

            when {
                isPrimitiveType(typeClass) -> {
                    // For primitive types (String, Int, etc.)
                    val typeName =
                        when (typeClass) {
                            String::class -> "string"
                            Int::class, Long::class -> "integer"
                            Float::class, Double::class -> "number"
                            Boolean::class -> "boolean"
                            else -> typeClass.simpleName?.lowercase() ?: "unknown"
                        }
                    propertyMap["type"] = JsonPrimitive(typeName)
                }

                typeClass == List::class ||
                    typeClass == MutableList::class ||
                    typeClass == Set::class ||
                    typeClass == MutableSet::class ||
                    Collection::class.isSuperclassOf(typeClass) -> {
                    // Handle collection types
                    propertyMap["type"] = JsonPrimitive("array")

                    // Get the generic type argument (e.g., SqlInfo from List<SqlInfo>)
                    val typeArguments = propertyType.arguments
                    if (typeArguments.isNotEmpty()) {
                        val firstArgument = typeArguments.first()
                        val itemType = firstArgument.type

                        if (itemType != null) {
                            val itemClass = itemType.jvmErasure

                            when {
                                isPrimitiveType(itemClass) -> {
                                    val itemTypeName =
                                        when (itemClass) {
                                            String::class -> "string"
                                            Int::class, Long::class -> "integer"
                                            Float::class, Double::class -> "number"
                                            Boolean::class -> "boolean"
                                            else -> itemClass.simpleName?.lowercase() ?: "unknown"
                                        }
                                    propertyMap["items"] = JsonObject(mapOf("type" to JsonPrimitive(itemTypeName)))
                                }

                                itemClass.java.isEnum -> {
                                    // Handle enum items in collections
                                    val enumSchema = mutableMapOf<String, JsonElement>()
                                    enumSchema["type"] = JsonPrimitive("string")
                                    val enumValues = itemClass.java.enumConstants?.map { it.toString() }
                                    if (!enumValues.isNullOrEmpty()) {
                                        enumSchema["enum"] = JsonArray(enumValues.map { JsonPrimitive(it) })
                                    }
                                    propertyMap["items"] = JsonObject(enumSchema)
                                }

                                else -> {
                                    // For custom data classes in the collection
                                    val itemSchema = generateSchemaJsonObject(itemClass, nonNullables)
                                    propertyMap["items"] =
                                        JsonObject(
                                            mapOf(
                                                "type" to JsonPrimitive("object"),
                                                "properties" to itemSchema,
                                            ),
                                        )
                                }
                            }
                        }
                    }
                }

                typeClass.java.isEnum -> {
                    // Handle enum types
                    propertyMap["type"] = JsonPrimitive("string")
                    val enumValues = typeClass.java.enumConstants?.map { it.toString() }
                    if (!enumValues.isNullOrEmpty()) {
                        propertyMap["enum"] = JsonArray(enumValues.map { JsonPrimitive(it) })
                    }
                }

                else -> {
                    // For custom classes (data classes and regular classes)
                    val nestedSchema = generateSchemaJsonObject(typeClass, nonNullables)
                    propertyMap["type"] = JsonPrimitive("object")
                    propertyMap["properties"] = nestedSchema
                }
            }

            propertiesMap[propertyName] = JsonObject(propertyMap)
        }

        return JsonObject(propertiesMap)
    }

    /**
     * Determines if a class is a primitive type
     */
    private fun isPrimitiveType(kClass: KClass<*>): Boolean =
        kClass == Boolean::class ||
            kClass == Int::class ||
            kClass == Long::class ||
            kClass == Double::class ||
            kClass == Float::class ||
            kClass == String::class
}

@Serializable
data class JsonString(
    val value: String,
) {
    init {
        require(
            Try {
                json.parseToJsonElement(value)
            }.isSuccess,
        ) {
            "Value is not valid JSON: $value"
        }
    }
}
