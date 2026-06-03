package io.github.mbbhalla.agentio.module.solver

import io.github.mbbhalla.agentio.data.model.DataValue
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class Z3SolverFacadeTest {
    @Test
    fun `solves simple integer formula and returns expected variable values`() {
        val formula =
            SMTLIBv2Formula(
                smtlibv2Formula =
                    """
                    (declare-const x Int)
                    (declare-const y Int)
                    (declare-const z Int)
                    (assert (= x 5))
                    (assert (= y 7))
                    (assert (= z (+ x y)))
                    (check-sat)
                    """.trimIndent(),
            )

        val models = Z3SolverFacade.solve(argSmtlibv2Formula = formula, limit = 1, logic = Logic.QF_LIA)

        assertEquals(1, models.size)
        val values = models.single().variableValues
        assertEquals(DataValue.LongValue(5), values["x"])
        assertEquals(DataValue.LongValue(7), values["y"])
        assertEquals(DataValue.LongValue(12), values["z"])
    }

    @Test
    fun `returns empty set when formula is unsatisfiable`() {
        val formula =
            SMTLIBv2Formula(
                smtlibv2Formula =
                    """
                    (declare-const x Int)
                    (assert (= x 1))
                    (assert (= x 2))
                    """.trimIndent(),
            )

        val models = Z3SolverFacade.solve(argSmtlibv2Formula = formula, limit = 1, logic = Logic.QF_LIA)

        assertTrue(models.isEmpty())
    }

    @Test
    fun `enumerates multiple distinct models up to limit`() {
        val formula =
            SMTLIBv2Formula(
                smtlibv2Formula =
                    """
                    (declare-const x Int)
                    (assert (>= x 0))
                    (assert (<= x 4))
                    """.trimIndent(),
            )

        val models = Z3SolverFacade.solve(argSmtlibv2Formula = formula, limit = 3, logic = Logic.QF_LIA)

        assertEquals(3, models.size)
        val xValues = models.mapNotNull { (it.variableValues["x"] as? DataValue.LongValue)?.value }.toSet()
        assertEquals(3, xValues.size)
    }

    @Test
    fun `applies variableNameFilter to restrict reported variables`() {
        val formula =
            SMTLIBv2Formula(
                smtlibv2Formula =
                    """
                    (declare-const a Int)
                    (declare-const b Int)
                    (assert (= a 1))
                    (assert (= b 2))
                    """.trimIndent(),
            )

        val models =
            Z3SolverFacade.solve(
                argSmtlibv2Formula = formula,
                limit = 1,
                variableNameFilter = { it == "a" },
            )

        val values = models.single().variableValues
        assertEquals(setOf("a"), values.keys)
    }

    @Test
    fun `rejects blank formula`() {
        assertFailsWith<IllegalArgumentException> {
            SMTLIBv2Formula(smtlibv2Formula = "")
        }
    }

    @Test
    fun `rejects syntactically invalid formula`() {
        assertFailsWith<IllegalArgumentException> {
            SMTLIBv2Formula(smtlibv2Formula = "(this is not smtlib")
        }
    }

    @Test
    fun `accepts formula with explicit set-logic`() {
        val formula =
            SMTLIBv2Formula(
                smtlibv2Formula =
                    """
                    (set-logic QF_LIA)
                    (declare-const x Int)
                    (assert (= x 42))
                    """.trimIndent(),
            )
        val models = Z3SolverFacade.solve(argSmtlibv2Formula = formula, limit = 1)
        assertEquals(DataValue.LongValue(42), models.single().variableValues["x"])
    }
}
