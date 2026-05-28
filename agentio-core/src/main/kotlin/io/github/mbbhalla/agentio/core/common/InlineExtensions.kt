package io.github.mbbhalla.agentio.core.common

import kotlin.collections.get
import kotlin.reflect.full.memberProperties
import kotlin.reflect.full.primaryConstructor
import kotlin.reflect.jvm.isAccessible

/**
 * Generic function that creates an object with the most frequent value for each property
 * from a list of objects of the same type.
 *
 * Algorithm:
 * 1. For each property in the constructor parameters
 * 2. Group all input objects by that property's value
 * 3. Find the group with the most objects (most frequent value)
 * 4. Use that value for the result object
 * 5. Construct new object with all the most frequent values
 */
@Suppress(
    "TooGenericExceptionCaught",
    "SwallowedException",
    "ThrowsCount",
    "MaxLineLength",
    "UseCheckOrError",
    "SpreadOperator",
)
internal inline fun <reified T : Any> List<T>.mostFrequentValues(): T {
    require(this.isNotEmpty()) { "List cannot be empty" }

    if (this.size == 1) {
        return this.first()
    }

    val kClass = T::class
    val constructor =
        kClass.primaryConstructor
            ?: throw IllegalArgumentException("Class must have a primary constructor")

    // Get all properties indexed by name
    val properties = kClass.memberProperties.associateBy { it.name }

    // For each constructor parameter, find the most frequent value
    val parameterValues =
        constructor.parameters.map { param ->
            val property =
                properties[param.name]
                    ?: throw IllegalArgumentException("Property '${param.name}' not found in class")

            // Ensure we can access the property
            property.isAccessible = true

            // Extract all values for this property from the input list
            val allValues =
                this.map { instance ->
                    try {
                        property.get(instance)
                    } catch (e: Exception) {
                        throw IllegalStateException("Cannot access property '${param.name}' on instance $instance: ${e.message}")
                    }
                }

            // Group by value and count occurrences
            val valueToCount = allValues.groupBy { it }.mapValues { it.value.size }

            // Find the value(s) with maximum count
            val maxCount = valueToCount.values.maxOrNull() ?: 0
            val mostFrequentValue = valueToCount.entries.first { it.value == maxCount }.key

            // Alternative tie-breaking strategies (commented out):
            // 1. Pick first occurrence in original list:
            // val mostFrequentValue = allValues.first { valueToCount[it] == maxCount }

            // 2. Pick last occurrence in original list:
            // val mostFrequentValue = allValues.last { valueToCount[it] == maxCount }

            // 3. Pick lexicographically smallest (for comparable types):
            // val mostFrequentValue
            // = valueToCount.entries.filter { it.value == maxCount }.minByOrNull { it.key.toString() }?.key

            // 4. Pick randomly among tied values:
            // val tiedValues = valueToCount.entries.filter { it.value == maxCount }.map { it.key }
            // val mostFrequentValue = tiedValues.random()

            // Validate nullability
            if (mostFrequentValue == null && !param.type.isMarkedNullable) {
                throw IllegalStateException("Most frequent value for '${param.name}' is null but parameter is not nullable")
            }

            mostFrequentValue
        }

    // Create the result object
    return try {
        constructor.call(*parameterValues.toTypedArray())
    } catch (e: Exception) {
        throw IllegalStateException("Failed to construct result object: ${e.message}\nParameters: $parameterValues")
    }
}
