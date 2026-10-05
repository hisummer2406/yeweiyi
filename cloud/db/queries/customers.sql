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
