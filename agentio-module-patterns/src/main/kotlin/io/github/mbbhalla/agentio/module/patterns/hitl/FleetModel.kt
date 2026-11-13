package io.github.mbbhalla.agentio.module.patterns.hitl

import io.github.mbbhalla.agentio.core.common.Description
import kotlinx.serialization.Serializable

/*
    An in-memory compute fleet the agent proposes changes to. Applying a proposal is a pure
    function from one Fleet to the next, so the "execution" step after approval has no hidden
    side effects and the before/after cost can be compared directly.
 */

enum class SizeTier(
    val monthlyCostUsd: Double,
) {
    SMALL(50.0),
    MEDIUM(100.0),
    LARGE(200.0),
    XLARGE(400.0),
    ;

    fun smaller(): SizeTier? = entries.getOrNull(ordinal - 1)
}

@Serializable
data class Resource(
    val id: String,
    val workload: String,
    val size: SizeTier,
    @field:Description("Average CPU utilisation over the last 30 days, in percent")
    val avgCpuPercent: Int,
    @field:Description("Whether the resource serves production traffic")
    val servesProduction: Boolean,
) {
    val monthlyCostUsd: Double get() = size.monthlyCostUsd
}

data class Fleet(
    val resources: List<Resource>,
) {
    val monthlyCostUsd: Double get() = resources.sumOf { it.monthlyCostUsd }

    /** Applies every change, ignoring KEEP; unknown resource ids and impossible downsizes are reported, not applied. */
    fun apply(proposal: RightsizingProposal): Result<Fleet> {
        val byId = resources.associateBy { it.id }
        val problems =
            proposal.changes.mapNotNull { change ->
                val resource = byId[change.resourceId]
                when {
                    resource == null -> "Unknown resource '${change.resourceId}'"
                    change.action == ChangeAction.DOWNSIZE && resource.size.smaller() == null ->
                        "'${resource.id}' is already the smallest size"
                    else -> null
                }
            }
        if (problems.isNotEmpty()) return Result.failure(IllegalArgumentException(problems.joinToString("; ")))

        val actionsById = proposal.changes.associateBy { it.resourceId }
        val next =
            resources.mapNotNull { resource ->
                when (actionsById[resource.id]?.action) {
                    ChangeAction.TERMINATE -> null
                    ChangeAction.DOWNSIZE -> resource.copy(size = checkNotNull(resource.size.smaller()))
                    ChangeAction.KEEP, null -> resource
                }
            }
        return Result.success(Fleet(next))
    }

    companion object {
        val SAMPLE =
            Fleet(
                listOf(
                    Resource("web-01", "customer web tier", SizeTier.LARGE, avgCpuPercent = 62, servesProduction = true),
                    Resource("web-02", "customer web tier", SizeTier.LARGE, avgCpuPercent = 58, servesProduction = true),
                    Resource("api-01", "public API", SizeTier.XLARGE, avgCpuPercent = 11, servesProduction = true),
                    Resource("batch-01", "nightly report batch", SizeTier.XLARGE, avgCpuPercent = 4, servesProduction = false),
                    Resource("dev-sandbox", "developer sandbox", SizeTier.MEDIUM, avgCpuPercent = 1, servesProduction = false),
                    Resource("ci-runner", "build agents", SizeTier.LARGE, avgCpuPercent = 35, servesProduction = false),
                ),
            )
    }
}

enum class ChangeAction { KEEP, DOWNSIZE, TERMINATE }

@Serializable
data class ProposedChange(
    @field:Description("Id of the resource to change")
    val resourceId: String,
    @field:Description("KEEP leaves it alone, DOWNSIZE moves it one size tier down, TERMINATE removes it")
    val action: ChangeAction,
    @field:Description("Why this change is safe and worthwhile, citing the utilisation figure")
    val justification: String,
)

@Serializable
data class RightsizingProposal(
    @field:Description("One entry per resource that should change; resources to keep may be omitted")
    val changes: List<ProposedChange>,
    @field:Description("Two or three sentences a reviewer can approve or reject from")
    val summary: String,
)
