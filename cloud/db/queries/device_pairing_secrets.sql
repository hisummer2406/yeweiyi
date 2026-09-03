-- name: UpsertDevicePairingSecret :exec
-- Minting always rotates: replaces any existing secret for this device outright. A leaked or lost
-- secret is fixed by minting a fresh one, not by keeping multiple valid secrets around.
INSERT INTO device_pairing_secrets (device_name, secret_hash)
VALUES ($1, $2)
ON CONFLICT (device_name) DO UPDATE SET secret_hash = EXCLUDED.secret_hash, created_at = now();

-- name: GetDevicePairingSecretHash :one
SELECT secret_hash FROM device_pairing_secrets WHERE device_name = $1;
