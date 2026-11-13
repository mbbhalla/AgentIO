package io.github.mbbhalla.agentio.module.patterns.hitl

import io.github.mbbhalla.agentio.core.common.Description
import io.github.mbbhalla.agentio.core.lib.server.AbstractMcpServer
import io.github.mbbhalla.agentio.core.lib.tool.AbstractMcpTool
import io.modelcontextprotocol.kotlin.sdk.server.RegisteredTool
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import kotlinx.serialization.Serializable

/** Read-only view of the fleet for the advisor. Changing the fleet is never a tool: it happens only after human approval. */
internal class FleetMcpServer(
    private val fleet: Fleet,
) : AbstractMcpServer() {
    override fun tools(): Set<RegisteredTool> = setOf(ListResourcesTool(fleet)())

    override fun capabilities() = ServerCapabilities(tools = ServerCapabilities.Tools(listChanged = false))

    override fun name() = "fleet"

    override fun version() = "1.0.0"
}

internal class ListResourcesTool(
    private val fleet: Fleet,
) : AbstractMcpTool<ListResourcesTool.Input, ListResourcesTool.Output>() {
    @Serializable
    class Input

    @Serializable
    data class ResourceView(
        val id: String,
        val workload: String,
        val size: SizeTier,
        val monthlyCostUsd: Double,
        val avgCpuPercent: Int,
        val servesProduction: Boolean,
    )

    @Serializable
    data class Output(
        @field:Description("Every resource in the fleet with its size, cost and 30-day utilisation")
        val resources: List<ResourceView>,
        @field:Description("Total monthly cost of the fleet in USD")
        val totalMonthlyCostUsd: Double,
        @field:Description("Available size tiers from smallest to largest with monthly cost")
        val sizeTiers: List<String>,
    )

    override fun name() = "list_resources"

    override fun description() = "List all compute resources with size, monthly cost, utilisation and whether they serve production"

    override fun getInputKClass() = Input::class

    override fun getOutputKClass() = Output::class

    override fun getToolConfig() = ToolConfig()

    override fun buildInput(callToolRequest: CallToolRequest) = Input()

    override fun invoke(input: Input) =
        Output(
            resources =
                fleet.resources.map {
                    ResourceView(it.id, it.workload, it.size, it.monthlyCostUsd, it.avgCpuPercent, it.servesProduction)
                },
            totalMonthlyCostUsd = fleet.monthlyCostUsd,
            sizeTiers = SizeTier.entries.map { "${it.name}: $${it.monthlyCostUsd}/month" },
        )
}
