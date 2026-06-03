package io.github.mbbhalla.agentio.examples.compass

import io.github.mbbhalla.agentio.data.model.ColumnName
import io.github.mbbhalla.agentio.data.model.TableName
import io.github.mbbhalla.agentio.module.compass.model.SMTLIB2Variable
import io.github.mbbhalla.agentio.module.compass.model.VariableType

/**
 * Concrete supply-chain decision variables that the COMPASS Constraint Generator may emit.
 * These are domain-specific extensions of the [SMTLIB2Variable] base from agentio-module-compass.
 *
 * To use COMPASS in a different domain, define an analogous set of `data object` variables
 * (each anchored to a dataset table and a list of key columns) and pass them into
 * `ConstraintGeneratorAgenticFunction.create(env, variables, ...)`.
 */

data object VariableCreatePurchaseOrderConditional : SMTLIB2Variable() {
    override val variableNamePrefix = "V_CREATE_PO"
    override val type = VariableType.BOOLEAN
    override val description = "True if a new purchase order should be created, False otherwise"
    override val associatedDataTable = TableName("inbound_order")
    override val keyColumns = listOf(ColumnName("id"), ColumnName("to_site_id"), ColumnName("tpartner_id"))
}

data object VariableCreatePurchaseOrderLineItemConditional : SMTLIB2Variable() {
    override val variableNamePrefix = "V_CREATE_PO_LI"
    override val type = VariableType.BOOLEAN
    override val description = "True if a new purchase order line item should be created, False otherwise"
    override val associatedDataTable = TableName("inbound_order_line")
    override val keyColumns = listOf(ColumnName("id"), ColumnName("order_id"), ColumnName("product_id"))
}

data object VariableCreatePurchaseOrderLineItemQuantity : SMTLIB2Variable() {
    override val variableNamePrefix = "V_CREATE_PO_LI_QTY"
    override val type = VariableType.LONG
    override val description = "Quantity to submit on a newly created purchase order line item"
    override val associatedDataTable = TableName("inbound_order_line")
    override val keyColumns = listOf(ColumnName("id"), ColumnName("order_id"), ColumnName("product_id"))
}

data object VariableModifyPurchaseOrderLineItemQuantity : SMTLIB2Variable() {
    override val variableNamePrefix = "V_MODIFY_PO_LI_QTY"
    override val type = VariableType.LONG
    override val description =
        "Delta to apply to an existing purchase order line item quantity. " +
            "0 = no change; positive = increase; negative = decrease."
    override val associatedDataTable = TableName("inbound_order_line")
    override val keyColumns = listOf(ColumnName("id"), ColumnName("order_id"), ColumnName("product_id"))
}

data object VariableModifyPurchaseOrderLineItemDate : SMTLIB2Variable() {
    override val variableNamePrefix = "V_MODIFY_PO_LI_DATE"
    override val type = VariableType.LONG
    override val description =
        "Delta (in days) to shift the expected delivery date of an existing purchase order line item. " +
            "0 = no change; positive = move forward; negative = move backward."
    override val associatedDataTable = TableName("inbound_order_line")
    override val keyColumns = listOf(ColumnName("id"), ColumnName("order_id"), ColumnName("product_id"))
}

val ALL_SUPPLY_CHAIN_VARIABLES: Set<SMTLIB2Variable> =
    setOf(
        VariableCreatePurchaseOrderConditional,
        VariableCreatePurchaseOrderLineItemConditional,
        VariableCreatePurchaseOrderLineItemQuantity,
        VariableModifyPurchaseOrderLineItemQuantity,
        VariableModifyPurchaseOrderLineItemDate,
    )
