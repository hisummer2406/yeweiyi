-- name: GetWechatIdentity :one
SELECT * FROM user_wechat_identities WHERE appid = $1 AND openid = $2;

-- name: GetWechatIdentityByUser :one
SELECT * FROM user_wechat_identities WHERE user_id = $1;

-- name: CreateWechatIdentity :one
-- A concurrent bind of the same openid or the same user trips a UNIQUE constraint (23505); the caller
-- re-reads and decides, so two requests can never produce two rows or move an identity.
INSERT INTO user_wechat_identities (user_id, appid, openid) VALUES ($1, $2, $3) RETURNING *;

-- name: TouchWechatLogin :exec
UPDATE user_wechat_identities SET last_login_at = now() WHERE id = $1;

-- name: DeleteWechatIdentityByUser :execrows
-- Changing a phone number drops the WeChat binding: the new number must be proven again through
-- /api/auth/wechat/phone-login, so a previous holder of the number keeps no way in.
DELETE FROM user_wechat_identities WHERE user_id = $1;
