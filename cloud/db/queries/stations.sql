-- name: CreateStation :one
INSERT INTO stations (customer_id, name, address) VALUES ($1, $2, $3) RETURNING *;

-- name: GetStation :one
SELECT * FROM stations WHERE id = $1;

-- name: UpdateStation :one
UPDATE stations SET name = $2, address = $3 WHERE id = $1 RETURNING *;

-- name: DeleteStation :execrows
-- A station that still has devices fails with 23503 (devices_station_customer_fk); the handler turns
-- that into 409.
DELETE FROM stations WHERE id = $1;

-- name: ListStationsForUser :many
-- The station list a caller is allowed to see, by the same three-way rule as ListDevicesForUser:
--   platform_admin  every station;
--   customer_admin  every station of their own customer — including ones with no devices yet, or a
--                   newly created station could never be seen and so never be given a device;
--   customer_user   only the stations of devices on which they hold a tank grant, within their own customer. A
--                   station none of their devices is on is invisible, and so is how many other
--                   devices it has.
SELECT s.*
FROM stations s
JOIN users u ON u.id = $1 AND u.disabled_at IS NULL
WHERE u.role = 'platform_admin'
   OR (
       s.customer_id = u.customer_id
       AND (
           u.role = 'customer_admin'
           OR EXISTS (
               SELECT 1
               FROM devices d
               WHERE d.station_id = s.id AND d.customer_id = u.customer_id
                 AND EXISTS (SELECT 1 FROM user_tank_grants g WHERE g.user_id = u.id AND g.device_name = d.device_name)
           )
       )
   )
ORDER BY s.customer_id, s.name, s.id;

-- name: GetStationForUpdate :one
-- Serializes everything that changes which device is on a station (ordinary assignment and device
-- replacement). Lock order is always station first, then device rows by device_name, so the two
-- paths cannot deadlock. The partial unique index devices_station_id_key is the actual guarantee;
-- this lock only lets a handler see the occupant and answer with a precise conflict.
SELECT * FROM stations WHERE id = $1 FOR UPDATE;

-- name: GetStationDevice :one
-- The one device currently on a station, if any (at most one: devices_station_id_key).
SELECT * FROM devices WHERE station_id = $1;
