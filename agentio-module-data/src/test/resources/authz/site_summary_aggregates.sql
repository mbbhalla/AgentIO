/* SiteSummaryAggregatesQuery */
WITH REGIONS_TO_FILTER AS (
  SELECT
    REGION.id as REGION_ID,
    REGION.parent_geo_id as REGION_PARENT_ID,
    REGION.description AS DESCRIPTION
  FROM
    supply.Geography AS REGION
),
SITES_TO_FILTER AS (
  SELECT
    SITE.id as SITE_ID,
    SITE.geo_id AS SITE_PARENT_ID,
    SITE.site_type as SITE_TYPE,
    SITE.description as SITE_NAME,
    RF.description AS REGION_NAME,
    SITE.latitude AS LATITUDE,
    SITE.longitude as LONGITUDE
  FROM
    supply.Site AS SITE
    JOIN REGIONS_TO_FILTER AS RF ON SITE.geo_id = RF.REGION_ID
),
PRODUCTS_TO_FILTER AS (
  SELECT
    PRODUCT.id as PRODUCT_ID,
    PRODUCT.product_group_id as PRODUCT_PARENT_GROUP_ID
  FROM
    supply.Product AS PRODUCT
),
PAGINATED_DATA AS (
  SELECT
    PI_SITES.SITE_ID
  FROM
    (
      SELECT
        DISTINCT PI.SITE_ID AS SITE_ID
      FROM
        analytics.ProjectedInventory AS PI
    ) AS PI_SITES
    JOIN SITES_TO_FILTER AS SF ON SF.SITE_ID = PI_SITES.SITE_ID
  ORDER BY
    SITE_ID
  LIMIT
    25 OFFSET 0
),
WINDOW_RECORD AS (
  SELECT
    PI.site_id as SITE_ID,
    PI.product_id AS PRODUCT_ID,
    DATE_TRUNC('day', PI.window_end_time) AS TIME_POINT,
    PI.projected_inventory_quantity,
    PI.min_safety_stock_inventory_quantity,
    PI.max_safety_stock_inventory_quantity
  FROM
    (
      SELECT
        *
      FROM
        analytics.ProjectedInventory
      WHERE
        record_type = 'WINDOW'
    ) AS PI
    JOIN SITES_TO_FILTER AS SF ON SF.SITE_ID = PI.site_id
    JOIN PAGINATED_DATA AS PD ON PD.SITE_ID = PI.site_id
    JOIN PRODUCTS_TO_FILTER AS PF ON PF.PRODUCT_ID = PI.product_id
),
SITE_SUMMARY AS (
  SELECT
    WR_D.SITE_ID,
    WR_D.TIME_POINT,
    'DAY' AS TIME_GRANULARITY,
    AVG(TIMEIND) AS TIMEIND,
    SUM(WR_D.STOCK_OUT) AS STOCK_OUT,
    SUM(WR_D.EXCESS_STOCK) AS EXCESS_STOCK,
    SUM(WR_D.BALANCE) AS BALANCE,
    COUNT(*) AS TOTAL
  FROM
    (
      SELECT
        WR.site_id as SITE_ID,
        WR.product_id AS PRODUCT_ID,
        DATE_TRUNC('day', WR.TIME_POINT) AS TIME_POINT,
        DATEDIFF(
          'day',
          DATE_TRUNC('day', DATEADD('day', 0, '2024-01-15 00:00:00')),
          TIME_POINT
        ) AS TIMEIND,
        WR.min_safety_stock_inventory_quantity AS MIN_SAFETY_STOCK,
        WR.max_safety_stock_inventory_quantity AS MAX_SAFETY_STOCK,
        WR.projected_inventory_quantity AS INVENTORY_QTY,
        CASE
          WHEN INVENTORY_QTY < MIN_SAFETY_STOCK
          AND (
            (MIN_SAFETY_STOCK - INVENTORY_QTY) > (0.4 * cast(MIN_SAFETY_STOCK as float))
          ) THEN 1
          ELSE 0
        END AS STOCK_OUT,
        CASE
          WHEN INVENTORY_QTY > MAX_SAFETY_STOCK
          AND (
            (INVENTORY_QTY - MAX_SAFETY_STOCK) > (0.1 * cast(MAX_SAFETY_STOCK as float))
          ) THEN 1
          ELSE 0
        END AS EXCESS_STOCK,
        CASE
          WHEN (
            NOT (
              INVENTORY_QTY < MIN_SAFETY_STOCK
              AND (
                (MIN_SAFETY_STOCK - INVENTORY_QTY) > (0.4 * cast(MIN_SAFETY_STOCK as float))
              )
            )
          )
          AND (
            NOT (
              INVENTORY_QTY > MAX_SAFETY_STOCK
              AND (
                (INVENTORY_QTY - MAX_SAFETY_STOCK) > (0.1 * cast(MAX_SAFETY_STOCK as float))
              )
            )
          ) THEN 1
          ELSE 0
        END AS BALANCE
      FROM
        WINDOW_RECORD AS WR
      WHERE
        DATE_TRUNC('day', TIME_POINT) >= DATE_TRUNC('day', DATEADD('day', 0, '2024-01-15 00:00:00'))
        AND DATE_TRUNC('day', TIME_POINT) < DATE_TRUNC('day', DATEADD('day', 7, '2024-01-15 00:00:00'))
    ) AS WR_D
  GROUP BY
    WR_D.SITE_ID,
    WR_D.TIME_POINT
  UNION
  SELECT
    WR_W.SITE_ID,
    WR_W.TIME_POINT,
    'WEEK' AS TIME_GRANULARITY,
    AVG(TIMEIND) AS TIMEIND,
    SUM(WR_W.STOCK_OUT) AS STOCK_OUT,
    SUM(WR_W.EXCESS_STOCK) AS EXCESS_STOCK,
    SUM(WR_W.BALANCE) AS BALANCE,
    COUNT(*) AS TOTAL
  FROM
    (
      SELECT
        WR.site_id as SITE_ID,
        WR.product_id AS PRODUCT_ID,
        MAX(DATE_TRUNC('day', WR.TIME_POINT)) AS TIME_POINT,
        DATEDIFF(
          'day',
          DATE_TRUNC('day', DATEADD('day', 7, '2024-01-15 00:00:00')),
          TIME_POINT
        ) / 7 AS TIMEIND,
        MIN(WR.min_safety_stock_inventory_quantity) AS MIN_SAFETY_STOCK_VAL,
        MAX(WR.max_safety_stock_inventory_quantity) AS MAX_SAFETY_STOCK_VAL,
        AVG(WR.projected_inventory_quantity) AS INVENTORY_QTY_VAL,
        CASE
          WHEN INVENTORY_QTY_VAL < MIN_SAFETY_STOCK_VAL
          AND (
            (MIN_SAFETY_STOCK_VAL - INVENTORY_QTY_VAL) > (0.4 * cast(MIN_SAFETY_STOCK_VAL as float))
          ) THEN 1
          ELSE 0
        END AS STOCK_OUT,
        CASE
          WHEN INVENTORY_QTY_VAL > MAX_SAFETY_STOCK_VAL
          AND (
            (INVENTORY_QTY_VAL - MAX_SAFETY_STOCK_VAL) > (0.1 * cast(MAX_SAFETY_STOCK_VAL as float))
          ) THEN 1
          ELSE 0
        END AS EXCESS_STOCK,
        CASE
          WHEN (
            NOT (
              INVENTORY_QTY_VAL < MIN_SAFETY_STOCK_VAL
              AND (
                (MIN_SAFETY_STOCK_VAL - INVENTORY_QTY_VAL) > (0.4 * cast(MIN_SAFETY_STOCK_VAL as float))
              )
            )
          )
          AND (
            NOT (
              INVENTORY_QTY_VAL > MAX_SAFETY_STOCK_VAL
              AND (
                (INVENTORY_QTY_VAL - MAX_SAFETY_STOCK_VAL) > (0.1 * cast(MAX_SAFETY_STOCK_VAL as float))
              )
            )
          ) THEN 1
          ELSE 0
        END AS BALANCE
      FROM
        WINDOW_RECORD AS WR
      WHERE
        DATE_TRUNC('day', TIME_POINT) >= DATE_TRUNC('day', DATEADD('day', 7, '2024-01-15 00:00:00'))
        AND DATE_TRUNC('day', TIME_POINT) < DATE_TRUNC('day', DATEADD('day', 63, '2024-01-15 00:00:00'))
      GROUP BY
        SITE_ID,
        PRODUCT_ID,
        TIMEIND
    ) AS WR_W
  GROUP BY
    WR_W.SITE_ID,
    WR_W.TIME_POINT
  UNION
  SELECT
    WR_M.SITE_ID,
    WR_M.TIME_POINT,
    'MONTH' AS TIME_GRANULARITY,
    AVG(TIMEIND) AS TIMEIND,
    SUM(WR_M.STOCK_OUT) AS STOCK_OUT,
    SUM(WR_M.EXCESS_STOCK) AS EXCESS_STOCK,
    SUM(WR_M.BALANCE) AS BALANCE,
    COUNT(*) AS TOTAL
  FROM
    (
      SELECT
        WR.site_id as SITE_ID,
        WR.product_id AS PRODUCT_ID,
        MAX(DATE_TRUNC('day', WR.TIME_POINT)) AS TIME_POINT,
        DATEDIFF(
          'day',
          DATE_TRUNC('day', DATEADD('day', 63, '2024-01-15 00:00:00')),
          TIME_POINT
        ) / 30 AS TIMEIND,
        MIN(WR.min_safety_stock_inventory_quantity) AS MIN_SAFETY_STOCK_VAL,
        MAX(WR.max_safety_stock_inventory_quantity) AS MAX_SAFETY_STOCK_VAL,
        AVG(WR.projected_inventory_quantity) AS INVENTORY_QTY_VAL,
        CASE
          WHEN INVENTORY_QTY_VAL < MIN_SAFETY_STOCK_VAL
          AND (
            (MIN_SAFETY_STOCK_VAL - INVENTORY_QTY_VAL) > (0.4 * cast(MIN_SAFETY_STOCK_VAL as float))
          ) THEN 1
          ELSE 0
        END AS STOCK_OUT,
        CASE
          WHEN INVENTORY_QTY_VAL > MAX_SAFETY_STOCK_VAL
          AND (
            (INVENTORY_QTY_VAL - MAX_SAFETY_STOCK_VAL) > (0.1 * cast(MAX_SAFETY_STOCK_VAL as float))
          ) THEN 1
          ELSE 0
        END AS EXCESS_STOCK,
        CASE
          WHEN (
            NOT (
              INVENTORY_QTY_VAL < MIN_SAFETY_STOCK_VAL
              AND (
                (MIN_SAFETY_STOCK_VAL - INVENTORY_QTY_VAL) > (0.4 * cast(MIN_SAFETY_STOCK_VAL as float))
              )
            )
          )
          AND (
            NOT (
              INVENTORY_QTY_VAL > MAX_SAFETY_STOCK_VAL
              AND (
                (INVENTORY_QTY_VAL - MAX_SAFETY_STOCK_VAL) > (0.1 * cast(MAX_SAFETY_STOCK_VAL as float))
              )
            )
          ) THEN 1
          ELSE 0
        END AS BALANCE
      FROM
        WINDOW_RECORD AS WR
      WHERE
        DATE_TRUNC('day', TIME_POINT) >= DATE_TRUNC('day', DATEADD('day', 63, '2024-01-15 00:00:00'))
        AND DATE_TRUNC('day', TIME_POINT) < DATE_TRUNC(
          'day',
          DATEADD('day', 153, '2024-01-15 00:00:00')
        )
      GROUP BY
        SITE_ID,
        PRODUCT_ID,
        TIMEIND
    ) AS WR_M
  GROUP BY
    WR_M.SITE_ID,
    WR_M.TIME_POINT
)
SELECT
  SITE_SUMMARY.SITE_ID AS SITE_ID,
  SITE_SUMMARY.TIME_POINT AS TIME_POINT,
  SITE_SUMMARY.TIME_GRANULARITY AS TIME_GRANULARITY,
  SITE_SUMMARY.TIMEIND AS TIMEIND,
  SITE_SUMMARY.STOCK_OUT AS STOCK_OUT,
  SITE_SUMMARY.EXCESS_STOCK AS EXCESS_STOCK,
  SITE_SUMMARY.BALANCE AS BALANCE,
  SITE_SUMMARY.TOTAL AS TOTAL,
  SITES_TO_FILTER.SITE_TYPE AS SITE_TYPE,
  SITES_TO_FILTER.SITE_NAME AS SITE_NAME,
  SITES_TO_FILTER.SITE_PARENT_ID AS GEO_ID,
  SITES_TO_FILTER.REGION_NAME AS REGION_NAME,
  SITES_TO_FILTER.LATITUDE AS LATITUDE,
  SITES_TO_FILTER.LONGITUDE AS LONGITUDE
FROM
  SITE_SUMMARY
  JOIN SITES_TO_FILTER ON SITE_SUMMARY.SITE_ID = SITES_TO_FILTER.SITE_ID
ORDER BY
  SITE_ID,
  TIME_POINT
