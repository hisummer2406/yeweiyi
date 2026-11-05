-- name: GetCustomer :one
SELECT * FROM customers WHERE id = $1;

-- name: CreateCustomer :one
INSERT INTO customers (name) VALUES ($1) RETURNING *;

-- name: ListCustomers :many
SELECT * FROM customers ORDER BY id;

-- name: RenameCustomer :one
-- The `default` customer is protected: deployment docs and bootstrap SQL look it up by name.
UPDATE customers SET name = $2 WHERE id = $1 AND name <> 'default' RETURNING *;

-- name: DeleteCustomer :execrows
-- Refused by the foreign keys (ON DELETE RESTRICT) while any user, device, command or audit row still
-- references the customer; the handler turns that into 409. `default` is protected as above.
DELETE FROM customers WHERE id = $1 AND name <> 'default';

-- name: GetCustomerForUpdate :one
-- Completing onboarding locks the row so the readiness check and the status write see one state.
SELECT * FROM customers WHERE id = $1 FOR UPDATE;

-- name: CustomerOnboardingCounts :one
-- The three minimum conditions for onboarding (cloud#60). A device only counts when it sits in a
-- station of the same customer; the composite FK already guarantees that, the join keeps it explicit.
SELECT
    (SELECT count(*) FROM users u
      WHERE u.customer_id = sqlc.arg('customer_id') AND u.role = 'customer_admin'
        AND u.disabled_at IS NULL AND u.phone IS NOT NULL AND u.password_hash IS NOT NULL)::bigint AS customer_admins,
    (SELECT count(*) FROM stations s WHERE s.customer_id = sqlc.arg('customer_id'))::bigint AS stations,
    (SELECT count(*) FROM devices d JOIN stations s ON s.id = d.station_id AND s.customer_id = d.customer_id
      WHERE d.customer_id = sqlc.arg('customer_id'))::bigint AS devices_in_station;

-- name: MarkCustomerOnboarded :one
UPDATE customers SET onboarding_status = 'active', onboarded_at = now()
WHERE id = $1 AND onboarding_status = 'configuring' RETURNING *;
