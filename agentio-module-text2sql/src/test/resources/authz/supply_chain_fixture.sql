-- Supply-chain fixture for SqlAuthorizerProductionQueriesSemanticTest (DuckDB).
CREATE SCHEMA supply;
CREATE SCHEMA analytics;

CREATE TABLE supply.geography (id VARCHAR, parent_geo_id VARCHAR, description VARCHAR);
CREATE TABLE supply.site (id VARCHAR, geo_id VARCHAR, site_type VARCHAR, description VARCHAR, latitude DOUBLE, longitude DOUBLE);
CREATE TABLE supply.product (id VARCHAR, product_group_id VARCHAR, description VARCHAR);
CREATE TABLE supply.producthierarchy (id VARCHAR, description VARCHAR);
CREATE TABLE analytics.projectedinventory (
  site_id VARCHAR, product_id VARCHAR, record_type VARCHAR, site_product_hash VARCHAR,
  window_end_time TIMESTAMP, baseline_time TIMESTAMP, projected_inventory_quantity DOUBLE,
  min_safety_stock_inventory_quantity DOUBLE, max_safety_stock_inventory_quantity DOUBLE,
  baseline_allocated_inventory_quantity DOUBLE, baseline_damaged_inventory_quantity DOUBLE,
  baseline_on_hand_inventory_quantity DOUBLE,
  window_inbound_shipment_inventory_quantity DOUBLE, window_outbound_shipment_inventory_quantity DOUBLE,
  window_inbound_on_order_inventory_quantity DOUBLE, window_outbound_on_order_inventory_quantity DOUBLE
);

-- Redshift DATEADD(unit, n, ts) shim; the test queries only use the day unit.
CREATE MACRO dateadd(part, n, ts) AS CAST(ts AS TIMESTAMP) + to_days(CAST(n AS INTEGER));

INSERT INTO supply.geography VALUES ('GEO-1', NULL, 'East'), ('GEO-2', NULL, 'West');
INSERT INTO supply.site VALUES
  ('NY-001', 'GEO-1', 'DC', 'New York', 1, 1),
  ('NJ-001', 'GEO-1', 'DC', 'Jersey', 2, 2),
  ('CH-001', 'GEO-2', 'DC', 'Chicago', 3, 3);
INSERT INTO supply.producthierarchy VALUES ('PH-1', 'Olives'), ('PH-2', 'Other');
INSERT INTO supply.product VALUES
  ('OLIVE-BL', 'PH-1', 'Black'), ('OLIVE-RD', 'PH-1', 'Red'), ('OLIVE-GR', 'PH-2', 'Green'), ('BREAD', 'PH-2', 'Bread');

-- Projected (WINDOW) records every 10 days for 160 days, varying quantity so risk classes differ.
INSERT INTO analytics.projectedinventory
SELECT s.id, p.id, 'WINDOW', s.id || p.id, TIMESTAMP '2024-01-15' + to_days(d), NULL,
       (10 + d) * CASE WHEN p.id = 'OLIVE-RD' THEN 0.1 ELSE 1 END, 8, 20,
       NULL, NULL, NULL, 1, 1, 1, 1
FROM supply.site s, supply.product p, range(0, 160, 10) r(d);

-- Baseline records for the trailing week.
INSERT INTO analytics.projectedinventory
SELECT s.id, p.id, 'BASELINE', s.id || p.id, NULL, TIMESTAMP '2024-01-15' - to_days(d), NULL,
       NULL, NULL, 5, 1, 7, NULL, NULL, NULL, NULL
FROM supply.site s, supply.product p, range(0, 8, 1) r(d);
