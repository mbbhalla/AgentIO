package io.github.mbbhalla.agentio.examples.text2sql.data

object RetailSeedData {

    val INSERTS: List<String> = listOf(
        // Sites
        """
        INSERT INTO site VALUES
            ('WH-NJ-001', 'Newark Warehouse', 'WAREHOUSE', 'Newark', 'NJ'),
            ('WH-CA-001', 'Los Angeles Warehouse', 'WAREHOUSE', 'Los Angeles', 'CA'),
            ('ST-NY-001', 'Manhattan Store', 'STORE', 'New York', 'NY'),
            ('ST-CA-001', 'San Francisco Store', 'STORE', 'San Francisco', 'CA')
        """.trimIndent(),

        // Products
        """
        INSERT INTO product VALUES
            ('SKU-001', 'Organic Olive Oil 500ml', 'Grocery', 8.50, 14.99),
            ('SKU-002', 'Wireless Bluetooth Headphones', 'Electronics', 22.00, 49.99),
            ('SKU-003', 'Cotton T-Shirt Medium', 'Apparel', 5.00, 19.99),
            ('SKU-004', 'Stainless Steel Water Bottle', 'Kitchen', 7.25, 24.99),
            ('SKU-005', 'Running Shoes Size 10', 'Footwear', 35.00, 89.99)
        """.trimIndent(),

        // Suppliers
        """
        INSERT INTO supplier VALUES
            ('SUP-001', 'Mediterranean Foods Co', 7, 0.92),
            ('SUP-002', 'TechSource Electronics', 14, 0.85),
            ('SUP-003', 'TextilePro Manufacturing', 21, 0.78),
            ('SUP-004', 'HomeGoods Direct', 10, 0.88)
        """.trimIndent(),

        // Inventory
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

        // Purchase Orders
        """
        INSERT INTO purchase_order VALUES
            ('PO-001', 'SUP-001', 'WH-NJ-001', 'SKU-001', 500, 'IN_TRANSIT', '2025-05-15 10:00:00', '2025-05-22 10:00:00'),
            ('PO-002', 'SUP-002', 'WH-NJ-001', 'SKU-002', 200, 'PENDING', '2025-05-18 14:00:00', '2025-06-01 14:00:00'),
            ('PO-003', 'SUP-001', 'WH-CA-001', 'SKU-001', 600, 'DELAYED', '2025-05-10 09:00:00', '2025-05-17 09:00:00'),
            ('PO-004', 'SUP-003', 'WH-NJ-001', 'SKU-003', 1000, 'DELIVERED', '2025-05-01 08:00:00', '2025-05-21 08:00:00'),
            ('PO-005', 'SUP-004', 'WH-CA-001', 'SKU-004', 300, 'IN_TRANSIT', '2025-05-16 11:00:00', '2025-05-26 11:00:00')
        """.trimIndent(),

        // Sales Orders
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
