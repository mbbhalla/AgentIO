package io.github.mbbhalla.agentio.module.patterns.goap

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GoapPlannerTest {
    private data class Seed(
        val value: Int,
    )

    private data class Doubled(
        val value: Int,
    )

    private data class Squared(
        val value: Int,
    )

    private data class Combined(
        val value: Int,
    )

    private val double =
        goapAction<Doubled>(name = "double", requires = setOf(Seed::class)) {
            Result.success(
                Doubled(
                    it.get<Seed>().value * 2,
                ),
            )
        }
    private val square =
        goapAction<Squared>(name = "square", requires = setOf(Seed::class)) {
            Result.success(
                Squared(
                    it.get<Seed>().value * it.get<Seed>().value,
                ),
            )
        }
    private val combine =
        goapAction<Combined>(name = "combine", requires = setOf(Doubled::class, Squared::class)) {
            Result.success(Combined(it.get<Doubled>().value + it.get<Squared>().value))
        }

    @Test
    fun plan_whenGoalReachable_returnsActionsInDependencyOrder() {
        val plan = GoapPlanner.plan(state = setOf(Seed::class), goal = Combined::class, actions = listOf(combine, square, double))

        assertNotNull(plan)
        assertEquals(3, plan.cost)
        assertEquals("combine", plan.actions.last().name)
        assertEquals(
            setOf("double", "square"),
            plan.actions
                .dropLast(1)
                .map { it.name }
                .toSet(),
        )
    }

    @Test
    fun plan_whenTwoProducersExist_choosesCheaperOne() {
        val cheap = goapAction<Doubled>(name = "cheap", requires = setOf(Seed::class), cost = 1) { Result.success(Doubled(0)) }
        val expensive = goapAction<Doubled>(name = "expensive", requires = setOf(Seed::class), cost = 5) { Result.success(Doubled(0)) }

        val plan = GoapPlanner.plan(state = setOf(Seed::class), goal = Doubled::class, actions = listOf(expensive, cheap))

        assertNotNull(plan)
        assertEquals(listOf("cheap"), plan.actions.map { it.name })
    }

    @Test
    fun plan_whenGoalAlreadyPresent_returnsEmptyPlan() {
        val plan = GoapPlanner.plan(state = setOf(Seed::class, Doubled::class), goal = Doubled::class, actions = listOf(double))

        assertNotNull(plan)
        assertTrue(plan.isEmpty)
        assertEquals(0, plan.cost)
    }

    @Test
    fun plan_whenNoActionProducesGoal_returnsNull() {
        assertNull(GoapPlanner.plan(state = setOf(Seed::class), goal = Combined::class, actions = listOf(double)))
    }

    @Test
    fun plan_whenPreconditionUnsatisfiable_returnsNull() {
        assertNull(GoapPlanner.plan(state = emptySet(), goal = Doubled::class, actions = listOf(double)))
    }

    @Test
    fun goapAction_whenCostNotPositive_throws() {
        assertFailsWith<IllegalArgumentException> {
            goapAction<Doubled>(name = "free", requires = setOf(Seed::class), cost = 0) { Result.success(Doubled(0)) }
        }
    }

    @Test
    fun process_executesPlanAndPublishesEffects() =
        runTest {
            val outcome = GoapAgentProcess(listOf(double, square, combine)).run(initial = Blackboard.of(Seed(3)), goal = Combined::class)

            val achieved = assertIs<GoapAgentProcess.Outcome.Achieved>(outcome)
            assertEquals(Combined(6 + 9), achieved.blackboard.get<Combined>())
            assertEquals(3, achieved.history.size)
            assertTrue(achieved.history.all { it.succeeded })
        }

    @Test
    fun process_whenCheapActionFails_replansThroughAlternative() =
        runTest {
            val failing =
                goapAction<Doubled>(name = "failing", requires = setOf(Seed::class), cost = 1) {
                    Result.failure(IllegalStateException("unavailable"))
                }
            val fallback = goapAction<Doubled>(name = "fallback", requires = setOf(Seed::class), cost = 5) { Result.success(Doubled(42)) }

            val outcome = GoapAgentProcess(listOf(failing, fallback)).run(initial = Blackboard.of(Seed(1)), goal = Doubled::class)

            val achieved = assertIs<GoapAgentProcess.Outcome.Achieved>(outcome)
            assertEquals(Doubled(42), achieved.blackboard.get<Doubled>())
            assertEquals(listOf("failing" to false, "fallback" to true), achieved.history.map { it.action.name to it.succeeded })
        }

    @Test
    fun process_whenNoRouteToGoal_reportsStuck() =
        runTest {
            val outcome = GoapAgentProcess(listOf(double)).run(initial = Blackboard.of(Seed(1)), goal = Combined::class)

            val stuck = assertIs<GoapAgentProcess.Outcome.Stuck>(outcome)
            assertTrue(stuck.reason.contains("Combined"))
            assertTrue(stuck.history.isEmpty())
        }

    @Test
    fun blackboard_getMissingType_throws() {
        assertFailsWith<IllegalStateException> { Blackboard.empty().get<Seed>() }
    }
}
