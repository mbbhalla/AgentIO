package io.github.mbbhalla.agentio.examples.text2sql.data

import io.github.mbbhalla.agentio.examples.text2sql.model.ColumnInfo
import io.github.mbbhalla.agentio.examples.text2sql.model.ColumnType

object RetailSchema {

    data class TableMeta(
        val description: String,
        val columns: List<ColumnInfo>,
    )

    val TABLE_METADATA: Map<String, TableMeta> = mapOf(
        "site" to TableMeta(
            description = "Physical locations (warehouses, stores) where inventory is held",
            columns = listOf(
                ColumnInfo("site_id", ColumnType.VARCHAR, false, true, null, "Unique site identifier"),
                ColumnInfo("site_name", ColumnType.VARCHAR, false, false, null, "Human-readable site name"),
                ColumnInfo("site_type", ColumnType.VARCHAR, false, false, null, "Type: WAREHOUSE or STORE"),
                ColumnInfo("city", ColumnType.VARCHAR, false, false, null, "City where site is located"),
                ColumnInfo("state", ColumnType.VARCHAR, false, false, null, "State code"),
            ),
        ),
        "product" to TableMeta(
            description = "Products available in the retail catalog",
            columns = listOf(
                ColumnInfo("product_id", ColumnType.VARCHAR, false, true, null, "Unique product identifier (SKU)"),
                ColumnInfo("product_name", ColumnType.VARCHAR, false, false, null, "Product display name"),
                ColumnInfo("category", ColumnType.VARCHAR, false, false, null, "Product category"),
                ColumnInfo("unit_cost", ColumnType.DOUBLE, false, false, null, "Cost per unit in USD"),
                ColumnInfo("unit_price", ColumnType.DOUBLE, false, false, null, "Selling price per unit in USD"),
            ),
        ),
        "inventory" to TableMeta(
            description = "Current inventory levels per product per site",
            columns = listOf(
                ColumnInfo("site_id", ColumnType.VARCHAR, false, true, "site.site_id", "Site holding the inventory"),
                ColumnInfo("product_id", ColumnType.VARCHAR, false, true, "product.product_id", "Product in inventory"),
                ColumnInfo("quantity_on_hand", ColumnType.INTEGER, false, false, null, "Current stock quantity"),
                ColumnInfo("safety_stock", ColumnType.INTEGER, false, false, null, "Minimum threshold before reorder"),
                ColumnInfo("last_updated", ColumnType.TIMESTAMP, false, false, null, "Last inventory update timestamp"),
            ),
        ),
        "supplier" to TableMeta(
            description = "Suppliers who provide products",
            columns = listOf(
                ColumnInfo("supplier_id", ColumnType.VARCHAR, false, true, null, "Unique supplier identifier"),
                ColumnInfo("supplier_name", ColumnType.VARCHAR, false, false, null, "Supplier company name"),
                ColumnInfo("lead_time_days", ColumnType.INTEGER, false, false, null, "Average delivery lead time in days"),
                ColumnInfo("reliability_score", ColumnType.DOUBLE, false, false, null, "Supplier reliability rating 0.0-1.0"),
            ),
        ),
        "purchase_order" to TableMeta(
            description = "Purchase orders placed with suppliers for inventory replenishment",
            columns = listOf(
                ColumnInfo("po_id", ColumnType.VARCHAR, false, true, null, "Unique purchase order identifier"),
                ColumnInfo("supplier_id", ColumnType.VARCHAR, false, false, "supplier.supplier_id", "Supplier fulfilling this order"),
                ColumnInfo("site_id", ColumnType.VARCHAR, false, false, "site.site_id", "Destination site for delivery"),
                ColumnInfo("product_id", ColumnType.VARCHAR, false, false, "product.product_id", "Product being ordered"),
                ColumnInfo("quantity", ColumnType.INTEGER, false, false, null, "Number of units ordered"),
                ColumnInfo("status", ColumnType.VARCHAR, false, false, null, "Order status: PENDING, IN_TRANSIT, DELIVERED, DELAYED"),
                ColumnInfo("order_date", ColumnType.TIMESTAMP, false, false, null, "Date order was placed"),
                ColumnInfo("expected_date", ColumnType.TIMESTAMP, false, false, null, "Expected delivery date"),
            ),
        ),
        "sales_order" to TableMeta(
            description = "Customer sales orders",
            columns = listOf(
                ColumnInfo("order_id", ColumnType.VARCHAR, false, true, null, "Unique sales order identifier"),
                ColumnInfo("site_id", ColumnType.VARCHAR, false, false, "site.site_id", "Site fulfilling the order"),
                ColumnInfo("product_id", ColumnType.VARCHAR, false, false, "product.product_id", "Product sold"),
                ColumnInfo("quantity", ColumnType.INTEGER, false, false, null, "Units sold"),
                ColumnInfo("order_date", ColumnType.TIMESTAMP, false, false, null, "Date order was placed"),
                ColumnInfo("customer_id", ColumnType.VARCHAR, false, false, null, "Customer identifier"),
            ),
        ),
    )

    val DDL_STATEMENTS: List<String> = listOf(
        """
        CREATE TABLE site (
            site_id VARCHAR NOT NULL PRIMARY KEY,
            site_name VARCHAR NOT NULL,
            site_type VARCHAR NOT NULL,
            city VARCHAR NOT NULL,
            state VARCHAR NOT NULL
        )
        """.trimIndent(),
        """
        CREATE TABLE product (
            product_id VARCHAR NOT NULL PRIMARY KEY,
            product_name VARCHAR NOT NULL,
            category VARCHAR NOT NULL,
            unit_cost DOUBLE NOT NULL,
            unit_price DOUBLE NOT NULL
        )
        """.trimIndent(),
        """
        CREATE TABLE supplier (
            supplier_id VARCHAR NOT NULL PRIMARY KEY,
            supplier_name VARCHAR NOT NULL,
            lead_time_days INTEGER NOT NULL,
            reliability_score DOUBLE NOT NULL
        )
        """.trimIndent(),
        """
        CREATE TABLE inventory (
            site_id VARCHAR NOT NULL REFERENCES site(site_id),
            product_id VARCHAR NOT NULL REFERENCES product(product_id),
            quantity_on_hand INTEGER NOT NULL,
            safety_stock INTEGER NOT NULL,
            last_updated TIMESTAMP NOT NULL,
            PRIMARY KEY (site_id, product_id)
        )
        """.trimIndent(),
        """
        CREATE TABLE purchase_order (
            po_id VARCHAR NOT NULL PRIMARY KEY,
            supplier_id VARCHAR NOT NULL REFERENCES supplier(supplier_id),
            site_id VARCHAR NOT NULL REFERENCES site(site_id),
            product_id VARCHAR NOT NULL REFERENCES product(product_id),
            quantity INTEGER NOT NULL,
            status VARCHAR NOT NULL,
            order_date TIMESTAMP NOT NULL,
            expected_date TIMESTAMP NOT NULL
        )
        """.trimIndent(),
        """
        CREATE TABLE sales_order (
            order_id VARCHAR NOT NULL PRIMARY KEY,
            site_id VARCHAR NOT NULL REFERENCES site(site_id),
            product_id VARCHAR NOT NULL REFERENCES product(product_id),
            quantity INTEGER NOT NULL,
            order_date TIMESTAMP NOT NULL,
            customer_id VARCHAR NOT NULL
        )
        """.trimIndent(),
    )
}
