package io.github.mbbhalla.agentio.examples.text2sql.data

import io.github.mbbhalla.agentio.data.model.ColumnInfo
import io.github.mbbhalla.agentio.data.model.ColumnName
import io.github.mbbhalla.agentio.data.model.ColumnType
import io.github.mbbhalla.agentio.data.model.ForeignKeyRef
import io.github.mbbhalla.agentio.data.model.TableInfo
import io.github.mbbhalla.agentio.data.model.TableName

object RetailSchema {

    val TABLES: Set<TableInfo> = setOf(
        TableInfo(
            name = TableName("site"),
            description = "Physical locations (warehouses, stores) where inventory is held",
            columns = listOf(
                ColumnInfo(ColumnName("site_id"), ColumnType.VARCHAR, false, true, null, "Unique site identifier"),
                ColumnInfo(ColumnName("site_name"), ColumnType.VARCHAR, false, false, null, "Human-readable site name"),
                ColumnInfo(ColumnName("site_type"), ColumnType.VARCHAR, false, false, null, "Type: WAREHOUSE or STORE"),
                ColumnInfo(ColumnName("city"), ColumnType.VARCHAR, false, false, null, "City where site is located"),
                ColumnInfo(ColumnName("state"), ColumnType.VARCHAR, false, false, null, "State code"),
            ),
        ),
        TableInfo(
            name = TableName("product"),
            description = "Products available in the retail catalog",
            columns = listOf(
                ColumnInfo(ColumnName("product_id"), ColumnType.VARCHAR, false, true, null, "Unique product identifier (SKU)"),
                ColumnInfo(ColumnName("product_name"), ColumnType.VARCHAR, false, false, null, "Product display name"),
                ColumnInfo(ColumnName("category"), ColumnType.VARCHAR, false, false, null, "Product category"),
                ColumnInfo(ColumnName("unit_cost"), ColumnType.DOUBLE, false, false, null, "Cost per unit in USD"),
                ColumnInfo(ColumnName("unit_price"), ColumnType.DOUBLE, false, false, null, "Selling price per unit in USD"),
            ),
        ),
        TableInfo(
            name = TableName("inventory"),
            description = "Current inventory levels per product per site",
            columns = listOf(
                ColumnInfo(ColumnName("site_id"), ColumnType.VARCHAR, false, true, ForeignKeyRef(TableName("site"), ColumnName("site_id")), "Site holding the inventory"),
                ColumnInfo(ColumnName("product_id"), ColumnType.VARCHAR, false, true, ForeignKeyRef(TableName("product"), ColumnName("product_id")), "Product in inventory"),
                ColumnInfo(ColumnName("quantity_on_hand"), ColumnType.INTEGER, false, false, null, "Current stock quantity"),
                ColumnInfo(ColumnName("safety_stock"), ColumnType.INTEGER, false, false, null, "Minimum threshold before reorder"),
                ColumnInfo(ColumnName("last_updated"), ColumnType.TIMESTAMP, false, false, null, "Last inventory update timestamp"),
            ),
        ),
        TableInfo(
            name = TableName("supplier"),
            description = "Suppliers who provide products",
            columns = listOf(
                ColumnInfo(ColumnName("supplier_id"), ColumnType.VARCHAR, false, true, null, "Unique supplier identifier"),
                ColumnInfo(ColumnName("supplier_name"), ColumnType.VARCHAR, false, false, null, "Supplier company name"),
                ColumnInfo(ColumnName("lead_time_days"), ColumnType.INTEGER, false, false, null, "Average delivery lead time in days"),
                ColumnInfo(ColumnName("reliability_score"), ColumnType.DOUBLE, false, false, null, "Supplier reliability rating 0.0-1.0"),
            ),
        ),
        TableInfo(
            name = TableName("purchase_order"),
            description = "Purchase orders placed with suppliers for inventory replenishment",
            columns = listOf(
                ColumnInfo(ColumnName("po_id"), ColumnType.VARCHAR, false, true, null, "Unique purchase order identifier"),
                ColumnInfo(ColumnName("supplier_id"), ColumnType.VARCHAR, false, false, ForeignKeyRef(TableName("supplier"), ColumnName("supplier_id")), "Supplier fulfilling this order"),
                ColumnInfo(ColumnName("site_id"), ColumnType.VARCHAR, false, false, ForeignKeyRef(TableName("site"), ColumnName("site_id")), "Destination site for delivery"),
                ColumnInfo(ColumnName("product_id"), ColumnType.VARCHAR, false, false, ForeignKeyRef(TableName("product"), ColumnName("product_id")), "Product being ordered"),
                ColumnInfo(ColumnName("quantity"), ColumnType.INTEGER, false, false, null, "Number of units ordered"),
                ColumnInfo(ColumnName("status"), ColumnType.VARCHAR, false, false, null, "Order status: PENDING, IN_TRANSIT, DELIVERED, DELAYED"),
                ColumnInfo(ColumnName("order_date"), ColumnType.TIMESTAMP, false, false, null, "Date order was placed"),
                ColumnInfo(ColumnName("expected_date"), ColumnType.TIMESTAMP, false, false, null, "Expected delivery date"),
            ),
        ),
        TableInfo(
            name = TableName("sales_order"),
            description = "Customer sales orders",
            columns = listOf(
                ColumnInfo(ColumnName("order_id"), ColumnType.VARCHAR, false, true, null, "Unique sales order identifier"),
                ColumnInfo(ColumnName("site_id"), ColumnType.VARCHAR, false, false, ForeignKeyRef(TableName("site"), ColumnName("site_id")), "Site fulfilling the order"),
                ColumnInfo(ColumnName("product_id"), ColumnType.VARCHAR, false, false, ForeignKeyRef(TableName("product"), ColumnName("product_id")), "Product sold"),
                ColumnInfo(ColumnName("quantity"), ColumnType.INTEGER, false, false, null, "Units sold"),
                ColumnInfo(ColumnName("order_date"), ColumnType.TIMESTAMP, false, false, null, "Date order was placed"),
                ColumnInfo(ColumnName("customer_id"), ColumnType.VARCHAR, false, false, null, "Customer identifier"),
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
