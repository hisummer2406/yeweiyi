-- name: UpsertDevicePairingSecretForCustomer :one
-- Minting always rotates: replaces any existing secret for this device outright. A leaked or lost
-- secret is fixed by minting a fresh one, not by keeping multiple valid secrets around.
--
-- The device must still belong to the given customer, checked in this same statement with its row
-- locked FOR SHARE. The handler checks ownership first and hashes the new secret (slow, on purpose)
-- before writing; a transfer that lands in between must not let the old customer's request rotate
-- the secret of a device that is now somebody else's — that would hand back a QR code the old
-- customer cannot use and silently invalidate the new owner's. Zero rows back means ownership
-- changed (or never held), and the handler answers 409.
WITH d AS (
    SELECT devices.device_name
    FROM devices
    WHERE devices.device_name = sqlc.arg('device_name')
      AND devices.customer_id = sqlc.arg('customer_id')
    FOR SHARE
)
INSERT INTO device_pairing_secrets (device_name, secret_hash)
SELECT d.device_name, sqlc.arg('secret_hash') FROM d
ON CONFLICT (device_name) DO UPDATE SET secret_hash = EXCLUDED.secret_hash, created_at = now()
RETURNING device_name;

-- name: GetDevicePairingSecretHash :one
SELECT secret_hash FROM device_pairing_secrets WHERE device_name = $1;
