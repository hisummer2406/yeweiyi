-- 公共介质库与客户专属介质库（#27）。这里只是字典：新增、改名、停用都不会改动任何油罐或设备配置。
-- 下发到设备的动态位置映射另行建表。

CREATE TABLE media (
    id            BIGSERIAL   PRIMARY KEY,
    name          TEXT        NOT NULL CHECK (char_length(btrim(name)) BETWEEN 1 AND 20),
    name_en       TEXT        CHECK (name_en IS NULL OR char_length(btrim(name_en)) BETWEEN 1 AND 40),
    scope         TEXT        NOT NULL CHECK (scope IN ('public', 'customer')),
    customer_id   BIGINT      REFERENCES customers (id) ON DELETE RESTRICT,
    builtin_index SMALLINT    UNIQUE CHECK (builtin_index BETWEEN 1 AND 31),
    enabled       BOOLEAN     NOT NULL DEFAULT true,
    updated_by    BIGINT      REFERENCES users (id) ON DELETE SET NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT media_scope_customer CHECK ((scope = 'public') = (customer_id IS NULL))
);
CREATE UNIQUE INDEX media_public_name_key ON media (lower(btrim(name))) WHERE scope = 'public';
CREATE UNIQUE INDEX media_customer_name_key ON media (customer_id, lower(btrim(name))) WHERE scope = 'customer';

COMMENT ON TABLE media IS '介质字典：公共介质可用于所有客户，客户专属介质只能用于所属客户的设备（#27）。被引用过的条目只停用、不物理删除。';
COMMENT ON COLUMN media.id IS '稳定 ID，独立于显示名称和设备数组下标。';
COMMENT ON COLUMN media.name IS '显示名称（中文或客户原文），最长 20 字符。';
COMMENT ON COLUMN media.name_en IS '英文显示名；没有翻译时为 NULL，界面显示 name 原文。';
COMMENT ON COLUMN media.scope IS 'public = 公共介质；customer = 客户专属介质。';
COMMENT ON COLUMN media.customer_id IS '专属介质的所属客户；公共介质为 NULL。有专属介质的客户不能删除。';
COMMENT ON COLUMN media.builtin_index IS '对应设备固定编号（oilTypeIndex 1–31）；手动新增的条目为 NULL。内置条目不可改名，否则与设备显示不一致。';
COMMENT ON COLUMN media.enabled IS '停用后不可新选，既有配置和历史仍可解释。';
COMMENT ON COLUMN media.updated_by IS '最后修改的平台管理员。';

-- 只初始化 1–24。25–31（二厂四种、排水三种）的公共/专属归属待业务核实，不在此猜测；其旧编号的显示兼容不受影响。
INSERT INTO media (builtin_index, name, name_en, scope) VALUES
    (1, '0#', '0# Diesel', 'public'),
    (2, '-10#', '-10# Diesel', 'public'),
    (3, '-20#', '-20# Diesel', 'public'),
    (4, '-35#', '-35# Diesel', 'public'),
    (5, '92#', '92# Gasoline', 'public'),
    (6, '95#', '95# Gasoline', 'public'),
    (7, '98#', '98# Gasoline', 'public'),
    (8, '甲醇', 'Methanol', 'public'),
    (9, '甲苯', 'Toluene', 'public'),
    (10, '米糠油', 'Rice Bran Oil', 'public'),
    (11, '硫酸', 'Sulfuric Acid', 'public'),
    (12, '二甲苯', 'Xylene', 'public'),
    (13, '正丁酯', 'n-Butyl', 'public'),
    (14, '仲丁酯', 'sec-Butyl', 'public'),
    (15, '甲酯', 'Methyl', 'public'),
    (16, '二甲酯', 'Dimethyl', 'public'),
    (17, '溶剂油', 'Solvent Oil', 'public'),
    (18, '丙二醇', 'PG', 'public'),
    (19, '乙醇', 'Ethanol', 'public'),
    (20, '大豆油', 'Soy Oil', 'public'),
    (21, '棕榈油', 'Palm Oil', 'public'),
    (22, '轮胎油', 'Tire Oil', 'public'),
    (23, '石蜡', 'Paraffin', 'public'),
    (24, '二氯甲烷', 'DCM', 'public');

-- 每台设备的动态介质位置：手动新增的介质没有固定编号，占用该设备 32–39 的一个位置。8 是单台设备的容量，不是字典上限。
CREATE TABLE device_media_slots (
    device_name TEXT        NOT NULL REFERENCES devices (device_name) ON DELETE CASCADE,
    slot        SMALLINT    NOT NULL CHECK (slot BETWEEN 32 AND 39),
    media_id    BIGINT      NOT NULL REFERENCES media (id) ON DELETE RESTRICT,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (device_name, slot),
    UNIQUE (device_name, media_id)
);
COMMENT ON TABLE device_media_slots IS '设备动态介质位置映射（#27）。设备换客户时清空；彻底删除设备时随设备行删除。';
COMMENT ON COLUMN device_media_slots.slot IS '设备上的 oilTypeIndex（32–39）。';
COMMENT ON COLUMN device_media_slots.media_id IS '占用该位置的介质。';

ALTER TABLE device_settings ADD COLUMN custom_media_sync INT;
COMMENT ON COLUMN device_settings.custom_media_sync IS '设备上报的介质名称同步能力；1 = 支持随 config.setTankSettings 写入名称，NULL = 旧固件。';
