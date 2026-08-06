-- DB 규모 기준선. run manifest에 그대로 붙여 결과의 해석 범위를 고정한다.
--
-- 데이터·인덱스가 buffer pool 안에 다 들어가는 소규모 DB에서 나온 수치는 production-like 용량이 아니다.
-- 합의된 production-like 기준에 못 미치면 그 run의 결과는 `small-db-baseline`으로만 보고한다.
--
-- 실행: mysql --defaults-extra-file=... <db> < 03-db-size-baseline.sql

SELECT
    table_name,
    table_rows,
    data_length,
    index_length,
    data_length + index_length AS total_bytes
FROM information_schema.tables
WHERE table_schema = DATABASE()
ORDER BY total_bytes DESC;

SELECT
    SUM(data_length + index_length)                      AS schema_total_bytes,
    @@innodb_buffer_pool_size                            AS innodb_buffer_pool_size,
    SUM(data_length + index_length) / @@innodb_buffer_pool_size AS schema_to_buffer_pool_ratio
FROM information_schema.tables
WHERE table_schema = DATABASE();

SELECT
    @@version              AS mysql_version,
    @@max_connections      AS max_connections,
    @@innodb_io_capacity   AS innodb_io_capacity,
    @@innodb_flush_log_at_trx_commit AS innodb_flush_log_at_trx_commit,
    @@system_time_zone     AS system_time_zone;
