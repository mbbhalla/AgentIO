package io.github.mbbhalla.agentio.module.patterns.handoff

import io.github.mbbhalla.agentio.core.common.Description
import io.github.mbbhalla.agentio.core.lib.server.AbstractMcpServer
import io.github.mbbhalla.agentio.core.lib.tool.AbstractMcpTool
import io.modelcontextprotocol.kotlin.sdk.server.RegisteredTool
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.jsonPrimitive

/** Fixed support data so the demo needs no external systems. */
internal object SupportData {
    @Serializable
    data class Invoice(
        val invoiceId: String,
        val customerId: String,
        val amountUsd: Double,
        val chargedOn: String,
        val description: String,
    )

    @Serializable
    data class ServiceStatus(
        val service: String,
        val status: String,
        val since: String,
        val note: String,
    )

    val INVOICES =
        listOf(
            Invoice("INV-2025-10-0007", "cust-1001", 120.0, "2025-10-03", "Pro plan, October"),
            Invoice("INV-2025-11-0007", "cust-1001", 120.0, "2025-11-02", "Pro plan, November"),
            Invoice("INV-2025-11-0007-R", "cust-1001", 120.0, "2025-11-02", "Pro plan, November (retry of a timed-out charge)"),
            Invoice("INV-2025-11-0042", "cust-2002", 40.0, "2025-11-05", "Starter plan, November"),
        )

    val SERVICES =
        listOf(
            ServiceStatus(
                "analytics-api",
                "DEGRADED",
                "2025-11-11T14:20:00Z",
                "Elevated 503 rate in us-west-2; a fix is rolling out, ETA 2 hours",
            ),
            ServiceStatus("billing-portal", "OPERATIONAL", "2025-09-01T00:00:00Z", "No incidents"),
            ServiceStatus("ingest-api", "OPERATIONAL", "2025-10-20T08:00:00Z", "No incidents"),
        )
}

/** Billing tools: only the billing agent gets these. */
internal class BillingMcpServer : AbstractMcpServer() {
    override fun tools(): Set<RegisteredTool> = setOf(LookupInvoicesTool()())

    override fun capabilities() = ServerCapabilities(tools = ServerCapabilities.Tools(listChanged = false))

    override fun name() = "billing"

    override fun version() = "1.0.0"
}

/** Technical tools: only the technical agent gets these. */
internal class TechnicalMcpServer : AbstractMcpServer() {
    override fun tools(): Set<RegisteredTool> = setOf(CheckServiceStatusTool()())

    override fun capabilities() = ServerCapabilities(tools = ServerCapabilities.Tools(listChanged = false))

    override fun name() = "technical"

    override fun version() = "1.0.0"
}

private fun stringArgument(
    request: CallToolRequest,
    name: String,
): String =
    requireNotNull(
        request.params.arguments
            ?.get(name)
            ?.jsonPrimitive
            ?.content,
    ) { "Missing argument '$name'" }

internal class LookupInvoicesTool : AbstractMcpTool<LookupInvoicesTool.Input, LookupInvoicesTool.Output>() {
    @Serializable
    data class Input(
        @field:Description("Customer identifier, e.g. cust-1001")
        val customerId: String,
    )

    @Serializable
    data class Output(
        @field:Description("All invoices charged to the customer, oldest first")
        val invoices: List<SupportData.Invoice>,
    )

    override fun name() = "lookup_invoices"

    override fun description() = "List every invoice charged to a customer"

    override fun getInputKClass() = Input::class

    override fun getOutputKClass() = Output::class

    override fun getToolConfig() = ToolConfig()

    override fun buildInput(callToolRequest: CallToolRequest) = Input(customerId = stringArgument(callToolRequest, "customerId"))

    override fun invoke(input: Input) = Output(invoices = SupportData.INVOICES.filter { it.customerId == input.customerId })
}

internal class CheckServiceStatusTool : AbstractMcpTool<CheckServiceStatusTool.Input, CheckServiceStatusTool.Output>() {
    @Serializable
    data class Input(
        @field:Description("Service name, e.g. analytics-api; use 'all' for every service")
        val service: String,
    )

    @Serializable
    data class Output(
        @field:Description("Current status of the matching services")
        val statuses: List<SupportData.ServiceStatus>,
    )

    override fun name() = "check_service_status"

    override fun description() = "Current operational status of a platform service"

    override fun getInputKClass() = Input::class

    override fun getOutputKClass() = Output::class

    override fun getToolConfig() = ToolConfig()

    override fun buildInput(callToolRequest: CallToolRequest) = Input(service = stringArgument(callToolRequest, "service"))

    override fun invoke(input: Input) =
        Output(
            statuses =
                if (input.service.equals("all", ignoreCase = true)) {
                    SupportData.SERVICES
                } else {
                    SupportData.SERVICES.filter { it.service.equals(input.service, ignoreCase = true) }
                },
        )
}
