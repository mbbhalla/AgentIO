package io.github.mbbhalla.agentio.examples.etl

import io.github.mbbhalla.agentio.data.model.TableName

/**
 * Checks a [LoadPlan] against the workspace before any SQL is written: every name it uses exists, every
 * source file feeds some table, and the load order puts each referenced table before the tables that
 * reference it, whether the reference is a declared foreign key or one the planner read from a description.
 */
internal object LoadPlanCheck {
    fun validate(
        plan: LoadPlan,
        workspace: EtlWorkspace,
    ): Result<LoadPlan> =
        runCatching {
            val problems = problems(plan, workspace)
            require(problems.isEmpty()) { "The load plan is invalid:\n- ${problems.joinToString("\n- ")}" }
            plan
        }

    private fun problems(
        plan: LoadPlan,
        workspace: EtlWorkspace,
    ): List<String> =
        buildList {
            if (plan.steps.isEmpty()) add("it has no steps")
            val position = mutableMapOf<TableName, Int>()
            plan.steps.forEachIndexed { index, step ->
                val table = runCatching { workspace.targetTable(step.targetTable) }.getOrElse { add(it.message.orEmpty()) }
                if (table is TableName && position.putIfAbsent(table, index) != null) add("'${table.value}' is planned twice")
                if (step.sourceFiles.isEmpty()) add("step '${step.targetTable}' names no source file")
                step.sourceFiles.filterNot { it in workspace.sources.names }.forEach {
                    add("step '${step.targetTable}' names unknown source '$it'; the sources are ${workspace.sources.names}")
                }
                if (step.mapping.isBlank()) add("step '${step.targetTable}' has no mapping")
            }
            val used = plan.steps.flatMap { it.sourceFiles }.toSet()
            workspace.sources.names
                .filterNot { it in used }
                .forEach { add("source '$it' feeds no table") }
            plan.references.forEach { reference -> addAll(referenceProblems(reference, position, workspace)) }
            workspace.model.tables.values
                .filter { it.name in position }
                .flatMap { table -> table.foreignKeys.map { table.name to it.referencedTable } }
                .forEach { (from, to) ->
                    val toPosition = position[to]
                    if (toPosition == null) {
                        add("'${from.value}' has a foreign key to '${to.value}', which the plan does not load")
                    } else if (toPosition > position.getValue(from)) {
                        add("'${from.value}' is loaded before '${to.value}', which its foreign key references")
                    }
                }
            if (plan.references.distinct().size != plan.references.size) add("a reference is listed twice")
        }

    private fun referenceProblems(
        reference: Reference,
        position: Map<TableName, Int>,
        workspace: EtlWorkspace,
    ): List<String> =
        runCatching {
            val from = workspace.targetTable(reference.fromTable)
            val to = workspace.targetTable(reference.toTable)
            workspace.column(from, reference.fromColumn)
            workspace.column(to, reference.toColumn)
            val fromType =
                workspace.env
                    .getTableInfo(from)
                    .columns
                    .first { it.name.value == reference.fromColumn.lowercase() }
                    .type
            val toType =
                workspace.env
                    .getTableInfo(to)
                    .columns
                    .first { it.name.value == reference.toColumn.lowercase() }
                    .type
            val fromPosition = position[from]
            val toPosition = position[to]
            listOfNotNull(
                "reference $reference: '${from.value}' is not planned".takeIf { fromPosition == null },
                "reference $reference: '${to.value}' is not planned".takeIf { toPosition == null },
                "reference $reference: '${to.value}' must be loaded no later than '${from.value}'"
                    .takeIf { fromPosition != null && toPosition != null && toPosition > fromPosition },
                "reference $reference: $fromType and $toType columns cannot reference each other".takeIf { fromType != toType },
            )
        }.getOrElse { listOf("reference $reference: ${it.message}") }
}
