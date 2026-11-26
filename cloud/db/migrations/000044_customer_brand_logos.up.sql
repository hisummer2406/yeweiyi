-- 客户品牌 Logo（#86）：平台管理员为客户配置小程序显示的 Logo，每个客户至多一张。
-- 首版把小尺寸 PNG 直接存在业务库，随业务库备份；客户删除时一并删除（不算历史记录，不阻止删除空客户）。
CREATE TABLE customer_brand_logos (
    customer_id  BIGINT PRIMARY KEY REFERENCES customers (id) ON DELETE CASCADE,
    content_type TEXT NOT NULL CHECK (content_type = 'image/png'),
    image        BYTEA NOT NULL CHECK (octet_length(image) BETWEEN 1 AND 262144),
    width        INT NOT NULL CHECK (width BETWEEN 64 AND 1024),
    height       INT NOT NULL CHECK (height BETWEEN 64 AND 1024),
    version      TEXT NOT NULL,
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by   BIGINT REFERENCES users (id) ON DELETE SET NULL
);

COMMENT ON TABLE customer_brand_logos IS '客户品牌 Logo（#86）。没有行 = 使用中成电气默认 Logo。';
COMMENT ON COLUMN customer_brand_logos.customer_id IS '所属客户，每个客户至多一张';
COMMENT ON COLUMN customer_brand_logos.content_type IS '图片类型，首版只收 PNG（按实际内容解码判定，不信扩展名）';
COMMENT ON COLUMN customer_brand_logos.image IS '图片原始字节，上限 256 KiB';
COMMENT ON COLUMN customer_brand_logos.width IS '图片宽度（像素），64–1024';
COMMENT ON COLUMN customer_brand_logos.height IS '图片高度（像素），64–1024';
COMMENT ON COLUMN customer_brand_logos.version IS '图片版本（内容 SHA-256 前 16 位十六进制），客户端据此判断缓存是否过期';
COMMENT ON COLUMN customer_brand_logos.updated_at IS '最近一次上传时间';
COMMENT ON COLUMN customer_brand_logos.updated_by IS '最近一次上传的平台管理员，账号删除后置空';
