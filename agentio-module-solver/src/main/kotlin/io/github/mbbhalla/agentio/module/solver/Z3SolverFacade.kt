package io.github.mbbhalla.agentio.module.solver

import com.microsoft.z3.BoolExpr
import com.microsoft.z3.Context
import com.microsoft.z3.FuncDecl
import com.microsoft.z3.IntNum
import com.microsoft.z3.RatNum
import com.microsoft.z3.Sort
import com.microsoft.z3.Status
import com.microsoft.z3.Symbol
import io.github.mbbhalla.agentio.data.model.DataValue
import kotlinx.serialization.Serializable

/**
 * SMT-LIB2 logics — see https://smt-lib.org/logics.shtml
 */
enum class Logic {
    QF_LIA,
    ALL,
}

@Serializable
data class SolverModel(
    val variableValues: Map<String, DataValue>,
)

object Z3SolverFacade {
    const val NON_HANDLED_VALUE = "NON_HANDLED_VALUE"

    @Suppress("LongMethod", "CyclomaticComplexMethod")
    fun solve(
        argSmtlibv2Formula: SMTLIBv2Formula,
        limit: Int = 10,
        variableNameFilter: (String) -> Boolean = { true },
        logic: Logic = Logic.ALL,
    ): Set<SolverModel> {
        require(limit > 0) { "limit must be > 0, got $limit" }

        val formulaText =
            if (containsLogicCommand(argSmtlibv2Formula.smtlibv2Formula)) {
                argSmtlibv2Formula.smtlibv2Formula
            } else {
                buildString {
                    append(
                        when (logic) {
                            Logic.QF_LIA -> "(set-logic QF_LIA)"
                            Logic.ALL -> "(set-logic ALL)"
                        },
                    )
                    append('\n')
                    append(argSmtlibv2Formula.smtlibv2Formula)
                }
            }

        return Context(mapOf("model" to "true")).use { ctx ->
            val solver = ctx.mkSolver()

            val sortNames = emptyArray<Symbol>()
            val sorts = emptyArray<Sort>()
            val declNames = emptyArray<Symbol>()
            val decls = emptyArray<FuncDecl<*>>()
            val assertions = ctx.parseSMTLIB2String(formulaText, sortNames, sorts, declNames, decls)
            assertions.forEach { solver.add(it) }

            val modelSequence =
                generateSequence(
                    if (solver.check() == Status.SATISFIABLE) solver.model else null,
                ) { model ->
                    val blockingClause =
                        ctx.mkNot(
                            model.decls
                                .mapNotNull { decl -> ctx.mkEq(decl.apply(), model.getConstInterp(decl)) }
                                .reduce { acc, expr -> ctx.mkAnd(acc, expr) },
                        )
                    solver.add(blockingClause.simplify())
                    if (solver.check() == Status.SATISFIABLE) solver.model else null
                }

            modelSequence
                .map { model ->
                    SolverModel(
                        variableValues =
                            model.decls
                                .filterNotNull()
                                .filter { decl -> variableNameFilter(decl.name.toString()) }
                                .associate { decl ->
                                    val name = decl.name.toString()
                                    val value = model.eval(decl.apply(), false)
                                    name to toDataValue(value)
                                },
                    )
                }.take(limit)
                .toSet()
        }
    }

    private fun toDataValue(value: com.microsoft.z3.Expr<*>): DataValue =
        when (value) {
            is IntNum -> DataValue.LongValue(value.int64)
            is RatNum ->
                DataValue.DoubleValue(
                    value.numerator.int64.toDouble() / value.denominator.int64.toDouble(),
                )
            is BoolExpr ->
                when (value.toString()) {
                    "true" -> DataValue.BooleanValue(true)
                    "false" -> DataValue.BooleanValue(false)
                    else -> DataValue.StringValue("BOOLEAN EXPRESSION: $value")
                }
            else -> DataValue.StringValue("$NON_HANDLED_VALUE: $value")
        }

    private fun containsLogicCommand(formula: String): Boolean = LOGIC_COMMAND_REGEX.containsMatchIn(formula)

    private val LOGIC_COMMAND_REGEX = Regex("""\(\s*set-logic\s+""")
}
