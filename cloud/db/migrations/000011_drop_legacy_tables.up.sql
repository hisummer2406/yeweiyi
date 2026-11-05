-- Drops the tables that belonged to the retired Alink/Paho stack (GitHub issue #10).
--
-- Why they go rather than staying as harmless empty tables: under the ThingsBoard single stack the
-- data boundary is settled — telemetry, alarms and stock movements live in ThingsBoard and are read
-- back on demand. Keeping local copies of those tables would leave a standing invitation to
-- reintroduce the duplicate time-series store this architecture exists to avoid, and sqlc would keep
-- generating models for them that look usable.
--
-- Verified empty in the dev database before dropping. Nothing in the repository reads them: their
-- ingestion (internal/mqtt), their queries (db/queries/alarms.sql and friends), their REST handlers
-- and the write-back queue were all removed in the same change.
--
--   alarms / oil_in_out    time-partitioned event tables written only by the old MQTT ingestion;
--                          replaced by reading ThingsBoard (internal/api/alarms.go, stock.go)
--   commit_works           交班 records; 设备协议 V1 has no shift uplink on either side and the
--                          endpoint was removed rather than migrated
--   oil_can_sets           tank/probe configuration, written only by the old write-back path
--   write_back_commands    the old command queue; replaced by control_commands (migration 000010)
--
-- Deliberately NOT dropped here: river's own tables (river_job/river_leader/river_queue/
-- river_notification, migrations 000002–000007). river is no longer used, but dropping its tables
-- from a later migration while 000002–000007 still claim to create them makes the migration history
-- inconsistent in both directions. Removing river cleanly means retiring those six files together,
-- which is a schema-history change worth doing on its own rather than smuggling into this one.

DROP TABLE IF EXISTS write_back_commands;
DROP TABLE IF EXISTS oil_can_sets;
DROP TABLE IF EXISTS commit_works;

-- Postgres drops partitions along with their parent, so naming them is redundant to the database.
-- It is not redundant to sqlc, which tracks tables statement by statement and would otherwise keep
-- generating models for partitions it never saw dropped — dead types that look like live ones.
DROP TABLE IF EXISTS oil_in_out_2026_08;
DROP TABLE IF EXISTS oil_in_out_2026_09;
DROP TABLE IF EXISTS oil_in_out;
DROP TABLE IF EXISTS alarms_2026_08;
DROP TABLE IF EXISTS alarms_2026_09;
DROP TABLE IF EXISTS alarms;
