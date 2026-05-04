package com.amazon.agentio.common

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

internal class InlineExtensionsTest {
    @Test
    @DisplayName("Should find most frequent values in simple data class")
    fun testBasicFrequencyCounting() {
        val list = listOf(
            SimpleData("X", 1), // name="X" appears 3 times (most frequent)
            SimpleData("Y", 2), // count=1 appears 2 times (most frequent)
            SimpleData("X", 1),
            SimpleData("X", 3),
            SimpleData("Z", 4),
        )

        val result = list.mostFrequentValues()
        val expected = SimpleData("X", 1)

        assertEquals(expected, result)
    }

    @Test
    @DisplayName("Should handle single item list by returning that item")
    fun testSingleItemList() {
        val list = listOf(SimpleData("only", 42))
        val result = list.mostFrequentValues()
        val expected = SimpleData("only", 42)

        assertEquals(expected, result)
    }

    @Test
    @DisplayName("Should handle list where all items are identical")
    fun testAllItemsIdentical() {
        val list = listOf(
            SimpleData("same", 100),
            SimpleData("same", 100),
            SimpleData("same", 100),
        )

        val result = list.mostFrequentValues()
        val expected = SimpleData("same", 100)

        assertEquals(expected, result)
    }

    @Test
    @DisplayName("Should work with extension function syntax")
    fun testExtensionFunctionSyntax() {
        val list = listOf(
            SimpleData("A", 1),
            SimpleData("A", 2),
            SimpleData("B", 1),
        )

        val result = list.mostFrequentValues()
        val expected = SimpleData("A", 1)

        assertEquals(expected, result)
    }

    @Test
    @DisplayName("Should handle complex objects with sets and nested objects")
    fun testComplexObjectsWithSets() {
        val obj1 = AnotherObject("A")
        val obj2 = AnotherObject("B")
        val obj3 = AnotherObject("C")
        val yetObj1 = YetAnotherObject(1, "First")
        val yetObj2 = YetAnotherObject(2, "Second")

        val list = listOf(
            DT(setOf(obj1, obj2), yetObj1, "common"), // x=setOf(A,B) appears 2 times
            DT(setOf(obj1, obj3), yetObj2, "common"), // y=yetObj1 appears 3 times
            DT(setOf(obj1, obj2), yetObj1, "different"), // z="common" appears 3 times
            DT(setOf(obj2, obj3), yetObj1, "common"),
        )

        val result = list.mostFrequentValues()
        val expected = DT(setOf(obj1, obj2), yetObj1, "common")

        assertEquals(expected, result)
    }

    @Test
    @DisplayName("Should handle ties by picking first occurrence in groupBy order")
    fun testTieBreakingBehavior() {
        val list = listOf(
            SimpleData("abc", 10), // "abc" appears 2 times
            SimpleData("def", 20), // "def" appears 2 times
            SimpleData("abc", 30), // Both have same frequency - should pick first
            SimpleData("def", 40),
        )

        val result = list.mostFrequentValues()

        // Should pick "abc" as it appears first in the original list
        assertEquals("abc", result.name)
    }

    @Test
    @DisplayName("Should handle exact tie scenario: attr='abc' 5 times, attr='def' 5 times")
    fun testExactTieScenario() {
        val list = listOf(
            SimpleData("abc", 1), SimpleData("abc", 2), SimpleData("abc", 3),
            SimpleData("abc", 4), SimpleData("abc", 5), // abc appears 5 times
            SimpleData("def", 6), SimpleData("def", 7), SimpleData("def", 8),
            SimpleData("def", 9), SimpleData("def", 10), // def appears 5 times
        )

        val result = list.mostFrequentValues()

        // Should pick "abc" as it appears first in iteration order
        assertEquals("abc", result.name)

        // Verify the frequency count manually
        val frequencies = list.groupBy { it.name }.mapValues { it.value.size }
        assertEquals(5, frequencies["abc"])
        assertEquals(5, frequencies["def"])
    }

    @Test
    @DisplayName("Should be deterministic - same input produces same output")
    fun testDeterministicBehavior() {
        val list = listOf(
            SimpleData("X", 1),
            SimpleData("Y", 1),
        )

        val result1 = list.mostFrequentValues()
        val result2 = list.mostFrequentValues()
        val result3 = list.mostFrequentValues()

        assertEquals(result1, result2)
        assertEquals(result2, result3)
    }

    @Test
    @DisplayName("Should handle nullable properties when null is most frequent")
    fun testNullableMostFrequent() {
        val list = listOf(
            NullableData(null, 5), // name=null appears 2 times (most frequent)
            NullableData("test", 10), // count=5 appears 2 times (most frequent)
            NullableData(null, 5),
            NullableData("other", 20),
        )

        val result = list.mostFrequentValues()
        val expected = NullableData(null, 5)

        assertEquals(expected, result)
        assertNull(result.name)
        assertEquals(5, result.value)
    }

    @Test
    @DisplayName("Should handle nullable properties when non-null is most frequent")
    fun testNullableNonNullMostFrequent() {
        val list = listOf(
            NullableData("winner", 1),
            NullableData("winner", 2),
            NullableData("winner", 3), // "winner" appears 3 times
            NullableData(null, 4), // null appears 1 time
            NullableData("other", 5), // "other" appears 1 time
        )

        val result = list.mostFrequentValues()

        assertEquals("winner", result.name)
    }

    @Test
    @DisplayName("Should throw exception for empty list")
    fun testEmptyListThrowsException() {
        val emptyList: List<SimpleData> = emptyList()

        val exception = assertThrows<IllegalArgumentException> {
            emptyList.mostFrequentValues()
        }

        assertEquals("List cannot be empty", exception.message)
    }

    @Test
    @DisplayName("Step-by-step manual verification of algorithm")
    fun testStepByStepVerification() {
        val list = listOf(
            SimpleData("A", 1), // Index 0
            SimpleData("B", 1), // Index 1
            SimpleData("A", 2), // Index 2
            SimpleData("A", 1), // Index 3
        )

        // Manual frequency calculation
        val nameFrequencies = list.groupBy { it.name }.mapValues { it.value.size }
        val countFrequencies = list.groupBy { it.count }.mapValues { it.value.size }

        // Verify our manual calculation
        assertEquals(mapOf("A" to 3, "B" to 1), nameFrequencies)
        assertEquals(mapOf(1 to 3, 2 to 1), countFrequencies)

        // Test the actual function
        val result = list.mostFrequentValues()
        val expected = SimpleData("A", 1)

        assertEquals(expected, result)

        // Verify the winner has the highest frequency
        assertEquals(3, nameFrequencies[result.name])
        assertEquals(3, countFrequencies[result.count])
    }

    @Test
    @DisplayName("Verify frequency counting logic with detailed assertions")
    fun testDetailedFrequencyVerification() {
        val list = listOf(
            SimpleData("X", 10),
            SimpleData("Y", 20),
            SimpleData("X", 30),
            SimpleData("Z", 10),
            SimpleData("X", 10),
        )

        // Expected frequencies:
        // name: X=3, Y=1, Z=1 -> winner: X
        // count: 10=3, 20=1, 30=1 -> winner: 10

        val result = list.mostFrequentValues()

        assertEquals("X", result.name)
        assertEquals(10, result.count)

        // Verify by manual counting
        val nameCount = list.count { it.name == "X" }
        val countCount = list.count { it.count == 10 }

        assertEquals(3, nameCount)
        assertEquals(3, countCount)
    }
}
