DROP TABLE IF EXISTS device_settings;

ALTER TABLE devices
    DROP COLUMN IF EXISTS config_commands_enabled_by,
    DROP COLUMN IF EXISTS config_commands_enabled_at;

DROP INDEX IF EXISTS control_commands_config_in_flight_key;
DROP INDEX IF EXISTS control_commands_request_id_key;

ALTER TABLE control_commands
    DROP COLUMN IF EXISTS config_target,
    DROP COLUMN IF EXISTS expected_station_id,
    DROP COLUMN IF EXISTS request_id;
