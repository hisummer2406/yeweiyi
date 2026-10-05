-- name: GetUserByUsername :one
SELECT * FROM users WHERE username = $1;

-- name: GetUserByID :one
-- Used where a decision must reflect the user's *current* role and customer rather than what their
-- token said when it was issued.
SELECT * FROM users WHERE id = $1;

-- name: CreateUser :one
INSERT INTO users (username, password_hash, role, customer_id) VALUES ($1, $2, $3, $4) RETURNING *;

-- name: ListUsers :many
-- customer_id NULL means no filter, which only a platform admin may ask for; the handler pins
-- everyone else to their own customer.
SELECT * FROM users
WHERE (sqlc.narg('customer_id')::BIGINT IS NULL OR customer_id = sqlc.narg('customer_id')::BIGINT)
ORDER BY id;

-- name: SetUserDisabled :one
-- Disabling keeps the original timestamp if already disabled; enabling clears it.
UPDATE users
SET disabled_at = CASE WHEN sqlc.arg('disabled')::bool THEN COALESCE(disabled_at, now()) ELSE NULL END
WHERE id = sqlc.arg('id')
RETURNING *;

-- name: SetUserPassword :execrows
UPDATE users SET password_hash = $2 WHERE id = $1;

-- name: MoveUserToCustomer :one
-- A platform admin belongs to no customer (users_customer_role_check), so it cannot be moved.
UPDATE users SET customer_id = $2 WHERE id = $1 AND role <> 'platform_admin' RETURNING *;
