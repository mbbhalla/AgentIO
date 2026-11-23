package io.github.mbbhalla.agentio.examples.etl

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertTrue

class LoadPlanCheckTest {
    private val workspace = AdventureWorks.workspace()
    private val plan = AdventureWorks.referencePlan

    private fun problems(plan: LoadPlan): String =
        LoadPlanCheck
            .validate(plan, workspace)
            .exceptionOrNull()
            ?.message
            .orEmpty()

    @Test
    fun `the reference plan is valid`() = assertTrue(LoadPlanCheck.validate(plan, workspace).isSuccess, problems(plan))

    @Test
    fun `tables and sources must exist, each table once, and every source must feed one`() {
        val steps =
            listOf(
                LoadStep("employees", listOf("hris_roster.csv"), "x"),
                LoadStep("department", listOf("cost_centres.csv"), "x"),
                LoadStep("Department", listOf("cost_centers.csv"), " "),
            )
        val message = problems(LoadPlan(steps, emptyList()))
        assertContains(message, "Unknown target table 'employees'")
        assertContains(message, "unknown source 'cost_centres.csv'")
        assertContains(message, "'department' is planned twice")
        assertContains(message, "step 'Department' has no mapping")
        assertContains(message, "source 'payroll_register.csv' feeds no table")
    }

    @Test
    fun `a referenced table must be planned and loaded first`() {
        val history = plan.steps.first { it.targetTable == "employeedepartmenthistory" }
        val reordered = LoadPlan(listOf(history) + plan.steps.filter { it != history }, plan.references)
        assertContains(problems(reordered), "'employee' must be loaded no later than 'employeedepartmenthistory'")
        val withoutShift = LoadPlan(plan.steps.filter { it.targetTable != "shift" }, plan.references)
        assertContains(problems(withoutShift), "'shift' is not planned")
    }

    @Test
    fun `a reference must name existing columns of one type`() {
        val bad =
            plan.references +
                listOf(
                    Reference("employee", "managerid", "employee", "businessentityid"),
                    Reference("employee", "loginid", "person", "businessentityid"),
                )
        val message = problems(LoadPlan(plan.steps, bad))
        assertContains(message, "Table 'employee' has no column 'managerid'")
        assertContains(message, "VARCHAR and INTEGER columns cannot reference each other")
    }
}
