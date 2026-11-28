-- name: GetCustomerBrandLogo :one
SELECT * FROM customer_brand_logos WHERE customer_id = $1;

-- name: UpsertCustomerBrandLogo :one
INSERT INTO customer_brand_logos (customer_id, content_type, image, width, height, version, updated_by)
VALUES ($1, $2, $3, $4, $5, $6, $7)
ON CONFLICT (customer_id) DO UPDATE SET
    content_type = EXCLUDED.content_type,
    image        = EXCLUDED.image,
    width        = EXCLUDED.width,
    height       = EXCLUDED.height,
    version      = EXCLUDED.version,
    updated_at   = now(),
    updated_by   = EXCLUDED.updated_by
RETURNING *;

-- name: DeleteCustomerBrandLogo :execrows
DELETE FROM customer_brand_logos WHERE customer_id = $1;
