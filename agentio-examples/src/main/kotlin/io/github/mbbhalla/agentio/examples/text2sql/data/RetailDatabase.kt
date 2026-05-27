package io.github.mbbhalla.agentio.examples.text2sql.data

import io.github.mbbhalla.agentio.data.env.DatabaseEnvironment
import io.github.mbbhalla.agentio.data.env.DuckDbDatabaseEnvironment
import io.github.mbbhalla.agentio.data.model.ColumnInfo
import io.github.mbbhalla.agentio.data.model.ColumnName
import io.github.mbbhalla.agentio.data.model.ColumnType
import io.github.mbbhalla.agentio.data.model.ForeignKeyRef
import io.github.mbbhalla.agentio.data.model.TableInfo
import io.github.mbbhalla.agentio.data.model.TableName

object RetailDatabase {

    val environment: DatabaseEnvironment by lazy {
        DuckDbDatabaseEnvironment.fromStatements(
            ddl = DDL_STATEMENTS,
            dml = INSERTS,
            tableMetadata = TABLES,
        )
    }

    private val TABLES: Set<TableInfo> = setOf(
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

    private val DDL_STATEMENTS: List<String> = listOf(
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

    private val INSERTS: List<String> = listOf(
        """
        INSERT INTO site VALUES
            ('WH-NJ-001', 'Newark Warehouse', 'WAREHOUSE', 'Newark', 'NJ'),
            ('WH-CA-001', 'Los Angeles Warehouse', 'WAREHOUSE', 'Los Angeles', 'CA'),
            ('ST-NY-001', 'Manhattan Store', 'STORE', 'New York', 'NY'),
            ('ST-CA-001', 'San Francisco Store', 'STORE', 'San Francisco', 'CA')
        """.trimIndent(),
        """
        INSERT INTO product VALUES
            ('SKU-001', 'Organic Olive Oil 500ml', 'Grocery', 8.50, 14.99),
            ('SKU-002', 'Wireless Bluetooth Headphones', 'Electronics', 22.00, 49.99),
            ('SKU-003', 'Cotton T-Shirt Medium', 'Apparel', 5.00, 19.99),
            ('SKU-004', 'Stainless Steel Water Bottle', 'Kitchen', 7.25, 24.99),
            ('SKU-005', 'Running Shoes Size 10', 'Footwear', 35.00, 89.99)
        """.trimIndent(),
        """
        INSERT INTO supplier VALUES
            ('SUP-001', 'Mediterranean Foods Co', 7, 0.92),
            ('SUP-002', 'TechSource Electronics', 14, 0.85),
            ('SUP-003', 'TextilePro Manufacturing', 21, 0.78),
            ('SUP-004', 'HomeGoods Direct', 10, 0.88)
        """.trimIndent(),
        """
        INSERT INTO inventory VALUES
            ('WH-NJ-001', 'SKU-001', 150, 500, '2025-05-20 08:00:00'),
            ('WH-NJ-001', 'SKU-002', 300, 100, '2025-05-20 08:00:00'),
            ('WH-NJ-001', 'SKU-003', 1200, 200, '2025-05-19 16:00:00'),
            ('WH-CA-001', 'SKU-001', 400, 500, '2025-05-20 10:00:00'),
            ('WH-CA-001', 'SKU-004', 80, 150, '2025-05-18 12:00:00'),
            ('WH-CA-001', 'SKU-005', 45, 50, '2025-05-20 09:00:00'),
            ('ST-NY-001', 'SKU-001', 25, 30, '2025-05-20 07:00:00'),
            ('ST-NY-001', 'SKU-002', 60, 20, '2025-05-20 07:00:00'),
            ('ST-CA-001', 'SKU-003', 90, 40, '2025-05-19 18:00:00'),
            ('ST-CA-001', 'SKU-005', 12, 15, '2025-05-20 11:00:00')
        """.trimIndent(),
        """
        INSERT INTO purchase_order VALUES
            ('PO-001', 'SUP-001', 'WH-NJ-001', 'SKU-001', 500, 'IN_TRANSIT', '2025-05-15 10:00:00', '2025-05-22 10:00:00'),
            ('PO-002', 'SUP-002', 'WH-NJ-001', 'SKU-002', 200, 'PENDING', '2025-05-18 14:00:00', '2025-06-01 14:00:00'),
            ('PO-003', 'SUP-001', 'WH-CA-001', 'SKU-001', 600, 'DELAYED', '2025-05-10 09:00:00', '2025-05-17 09:00:00'),
            ('PO-004', 'SUP-003', 'WH-NJ-001', 'SKU-003', 1000, 'DELIVERED', '2025-05-01 08:00:00', '2025-05-21 08:00:00'),
            ('PO-005', 'SUP-004', 'WH-CA-001', 'SKU-004', 300, 'IN_TRANSIT', '2025-05-16 11:00:00', '2025-05-26 11:00:00')
        """.trimIndent(),
        """
        INSERT INTO sales_order VALUES
            ('SO-001', 'ST-NY-001', 'SKU-001', 5, '2025-05-20 09:30:00', 'CUST-101'),
            ('SO-002', 'ST-NY-001', 'SKU-002', 2, '2025-05-20 10:15:00', 'CUST-102'),
            ('SO-003', 'ST-CA-001', 'SKU-003', 3, '2025-05-20 11:00:00', 'CUST-103'),
            ('SO-004', 'ST-NY-001', 'SKU-001', 8, '2025-05-19 14:00:00', 'CUST-104'),
            ('SO-005', 'ST-CA-001', 'SKU-005', 1, '2025-05-19 16:30:00', 'CUST-105'),
            ('SO-006', 'ST-NY-001', 'SKU-002', 4, '2025-05-18 12:00:00', 'CUST-101'),
            ('SO-007', 'WH-NJ-001', 'SKU-001', 50, '2025-05-17 08:00:00', 'CUST-106'),
            ('SO-008', 'WH-CA-001', 'SKU-001', 100, '2025-05-16 09:00:00', 'CUST-107'),
            ('SO-009', 'ST-CA-001', 'SKU-005', 2, '2025-05-20 13:00:00', 'CUST-108'),
            ('SO-010', 'WH-NJ-001', 'SKU-003', 200, '2025-05-20 07:30:00', 'CUST-109')
        """.trimIndent(),
    )
}
