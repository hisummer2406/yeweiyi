-- 微信身份关联（#59）：把经微信验证的 (AppID, OpenID) 绑到一个业务账号。
-- 一个用户至多一个微信身份，一个微信身份至多一个用户；本期不自动换绑（见 docs/account-phone-wechat-contract.md §8 Q3）。
-- 不存 session_key，也不依赖 UnionID。
CREATE TABLE user_wechat_identities (
    id            BIGSERIAL PRIMARY KEY,
    user_id       BIGINT NOT NULL UNIQUE REFERENCES users (id) ON DELETE CASCADE,
    appid         TEXT NOT NULL CHECK (appid <> ''),
    openid        TEXT NOT NULL CHECK (openid <> ''),
    bound_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_login_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (appid, openid)
);

COMMENT ON TABLE user_wechat_identities IS
    '微信小程序身份与业务账号的一对一关联。账号删除时随之删除；停用不删除，停用账号的微信登录由业务层拒绝。';
COMMENT ON COLUMN user_wechat_identities.appid IS '小程序 AppID。同一 openid 在不同小程序下不同，所以与 openid 一起唯一。';
COMMENT ON COLUMN user_wechat_identities.openid IS '经微信 jscode2session 验证的 openid；绝不接受客户端直接提交。';
COMMENT ON COLUMN user_wechat_identities.last_login_at IS '最近一次经微信完成登录的时间，仅供排查。';
