package io.github.mbbhalla.agentio.module.patterns.routing

import io.github.mbbhalla.agentio.core.common.Description
import io.github.mbbhalla.agentio.core.lib.server.AbstractMcpServer
import io.github.mbbhalla.agentio.core.lib.tool.AbstractMcpTool
import io.modelcontextprotocol.kotlin.sdk.server.RegisteredTool
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.jsonPrimitive
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** Exact calendar arithmetic for the date specialist; LLMs are unreliable at this and need not try. */
internal class DateToolsMcpServer : AbstractMcpServer() {
    override fun tools(): Set<RegisteredTool> = setOf(DaysBetweenTool()(), DayOfWeekTool()(), AddDaysTool()())

    override fun capabilities() = ServerCapabilities(tools = ServerCapabilities.Tools(listChanged = false))

    override fun name() = "dates"

    override fun version() = "1.0.0"
}

private fun dateArgument(
    request: CallToolRequest,
    name: String,
): LocalDate {
    val raw =
        request.params.arguments
            ?.get(name)
            ?.jsonPrimitive
            ?.content
    requireNotNull(raw) { "Missing argument '$name'" }
    return LocalDate.parse(raw)
}

private fun intArgument(
    request: CallToolRequest,
    name: String,
): Int {
    val raw =
        request.params.arguments
            ?.get(name)
            ?.jsonPrimitive
            ?.content
    return requireNotNull(raw?.toIntOrNull()) { "Missing or non-integer argument '$name'" }
}

internal class DaysBetweenTool : AbstractMcpTool<DaysBetweenTool.Input, DaysBetweenTool.Output>() {
    @Serializable
    data class Input(
        @field:Description("Start date in ISO-8601 format, e.g. 2025-01-31")
        val from: String,
        @field:Description("End date in ISO-8601 format")
        val to: String,
    )

    @Serializable
    data class Output(
        @field:Description("Number of days from 'from' to 'to'; negative if 'to' is earlier")
        val days: Long,
    )

    override fun name() = "days_between"

    override fun description() = "Exact number of calendar days between two ISO-8601 dates"

    override fun getInputKClass() = Input::class

    override fun getOutputKClass() = Output::class

    override fun getToolConfig() = ToolConfig()

    override fun buildInput(callToolRequest: CallToolRequest) =
        Input(from = dateArgument(callToolRequest, "from").toString(), to = dateArgument(callToolRequest, "to").toString())

    override fun invoke(input: Input) = Output(days = ChronoUnit.DAYS.between(LocalDate.parse(input.from), LocalDate.parse(input.to)))
}

internal class DayOfWeekTool : AbstractMcpTool<DayOfWeekTool.Input, DayOfWeekTool.Output>() {
    @Serializable
    data class Input(
        @field:Description("Date in ISO-8601 format")
        val date: String,
    )

    @Serializable
    data class Output(
        @field:Description("Day of the week, e.g. MONDAY")
        val dayOfWeek: String,
    )

    override fun name() = "day_of_week"

    override fun description() = "Day of the week for an ISO-8601 date"

    override fun getInputKClass() = Input::class

    override fun getOutputKClass() = Output::class

    override fun getToolConfig() = ToolConfig()

    override fun buildInput(callToolRequest: CallToolRequest) = Input(date = dateArgument(callToolRequest, "date").toString())

    override fun invoke(input: Input) = Output(dayOfWeek = LocalDate.parse(input.date).dayOfWeek.name)
}

internal class AddDaysTool : AbstractMcpTool<AddDaysTool.Input, AddDaysTool.Output>() {
    @Serializable
    data class Input(
        @field:Description("Start date in ISO-8601 format")
        val date: String,
        @field:Description("Days to add; negative to subtract")
        val days: Int,
    )

    @Serializable
    data class Output(
        @field:Description("Resulting date in ISO-8601 format")
        val date: String,
    )

    override fun name() = "add_days"

    override fun description() = "Add (or subtract) a number of days to an ISO-8601 date"

    override fun getInputKClass() = Input::class

    override fun getOutputKClass() = Output::class

    override fun getToolConfig() = ToolConfig()

    override fun buildInput(callToolRequest: CallToolRequest) =
        Input(date = dateArgument(callToolRequest, "date").toString(), days = intArgument(callToolRequest, "days"))

    override fun invoke(input: Input) = Output(date = LocalDate.parse(input.date).plusDays(input.days.toLong()).toString())
}
