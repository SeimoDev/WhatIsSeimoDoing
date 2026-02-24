-- Usage:
--   sqlite3 backend/data/whatis.db
--   .read ops/icon-sync-metrics.sql
--
-- Before running, replace all '__DEVICE_ID__' with the target device id.

.headers on
.mode column

-- 1) Latest usage day coverage: how many usage packages can be joined to app_catalog.
WITH latest_day AS (
  SELECT MAX(stat_date) AS stat_date
  FROM daily_app_usage_stats
  WHERE device_id = '__DEVICE_ID__'
)
SELECT
  a.stat_date,
  COUNT(*) AS usage_total,
  SUM(CASE WHEN c.package_name IS NOT NULL THEN 1 ELSE 0 END) AS matched_catalog,
  SUM(CASE WHEN c.package_name IS NULL THEN 1 ELSE 0 END) AS missing_catalog,
  ROUND(
    100.0 * SUM(CASE WHEN c.package_name IS NOT NULL THEN 1 ELSE 0 END) / COUNT(*),
    2
  ) AS catalog_coverage_pct
FROM daily_app_usage_stats a
LEFT JOIN app_catalog c
  ON c.device_id = a.device_id
 AND c.package_name = a.package_name
WHERE a.device_id = '__DEVICE_ID__'
  AND a.stat_date = (SELECT stat_date FROM latest_day)
GROUP BY a.stat_date;

-- 2) app_catalog rows with hash but without icon_base64.
SELECT
  COUNT(*) AS hash_without_base64
FROM app_catalog
WHERE device_id = '__DEVICE_ID__'
  AND icon_hash IS NOT NULL
  AND TRIM(icon_hash) <> ''
  AND (icon_base64 IS NULL OR TRIM(icon_base64) = '');

-- 3) Top usage packages that are still missing from app_catalog.
WITH latest_day AS (
  SELECT MAX(stat_date) AS stat_date
  FROM daily_app_usage_stats
  WHERE device_id = '__DEVICE_ID__'
)
SELECT
  a.package_name,
  a.usage_ms
FROM daily_app_usage_stats a
LEFT JOIN app_catalog c
  ON c.device_id = a.device_id
 AND c.package_name = a.package_name
WHERE a.device_id = '__DEVICE_ID__'
  AND a.stat_date = (SELECT stat_date FROM latest_day)
  AND c.package_name IS NULL
ORDER BY a.usage_ms DESC
LIMIT 30;

-- 4) Top usage packages where app_catalog exists but icon_base64 is still empty.
WITH latest_day AS (
  SELECT MAX(stat_date) AS stat_date
  FROM daily_app_usage_stats
  WHERE device_id = '__DEVICE_ID__'
)
SELECT
  a.package_name,
  c.app_name,
  a.usage_ms,
  c.icon_hash
FROM daily_app_usage_stats a
JOIN app_catalog c
  ON c.device_id = a.device_id
 AND c.package_name = a.package_name
WHERE a.device_id = '__DEVICE_ID__'
  AND a.stat_date = (SELECT stat_date FROM latest_day)
  AND (c.icon_base64 IS NULL OR TRIM(c.icon_base64) = '')
ORDER BY a.usage_ms DESC
LIMIT 30;
