-- Enable pgcrypto for password hashing (crypt, gen_salt).
CREATE EXTENSION IF NOT EXISTS pgcrypto;

-- Shared auth for PC-remote and mini-program clients (CLAUDE.md "Tech stack" / auth decision).
CREATE TABLE users (
    id            BIGSERIAL PRIMARY KEY,
    username      TEXT NOT NULL UNIQUE,
    password_hash TEXT NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Tracks thing/event/isOline/post state per device (design doc §4.2).
CREATE TABLE devices (
    device_name     TEXT PRIMARY KEY,
    is_online       BOOLEAN NOT NULL DEFAULT false,
    last_online_at  TIMESTAMPTZ,
    last_offline_at TIMESTAMPTZ,
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Mirrors device Alarm table (design doc §2, Alarm). Partitioned by time per CLAUDE.md, which
-- requires the partition column in every unique index, hence the composite keys below.
CREATE TABLE alarms (
    id              BIGINT GENERATED ALWAYS AS IDENTITY,
    device_name     TEXT NOT NULL REFERENCES devices (device_name),
    device_local_id INTEGER NOT NULL,
    time            TIMESTAMPTZ NOT NULL,
    info            TEXT NOT NULL,
    guanhao         TEXT NOT NULL,
    jiezhi          TEXT NOT NULL,
    sync_status     TEXT NOT NULL DEFAULT 'synced',
    received_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (id, time),
    UNIQUE (device_name, device_local_id, time)
) PARTITION BY RANGE (time);

CREATE TABLE alarms_2026_08 PARTITION OF alarms FOR VALUES FROM ('2026-08-01') TO ('2026-09-01');
CREATE TABLE alarms_2026_09 PARTITION OF alarms FOR VALUES FROM ('2026-09-01') TO ('2026-10-01');

-- Supports GET /api/alarms' actual query shape (device_name = X AND time BETWEEN ... ORDER BY
-- time). The UNIQUE constraint above starts with device_name too, but sorts by device_local_id
-- before time, so it can't serve an ordered time-range scan for one device — this index can.
-- Declarative partitioning propagates this to alarms_2026_08/09 and any future partition.
CREATE INDEX alarms_device_name_time_idx ON alarms (device_name, time);

-- Mirrors device OilInOut table (design doc §2, OilInOut). Same partitioning rationale as alarms.
CREATE TABLE oil_in_out (
    id              BIGINT GENERATED ALWAYS AS IDENTITY,
    device_name     TEXT NOT NULL REFERENCES devices (device_name),
    device_local_id INTEGER NOT NULL,
    oil_id          INTEGER NOT NULL,
    jiezhi          TEXT NOT NULL,
    time            TIMESTAMPTZ NOT NULL,
    begin_height    DOUBLE PRECISION NOT NULL,
    end_height      DOUBLE PRECISION NOT NULL,
    begin_volume    DOUBLE PRECISION NOT NULL,
    end_volume      DOUBLE PRECISION NOT NULL,
    begin_weight    DOUBLE PRECISION NOT NULL,
    end_weight      DOUBLE PRECISION NOT NULL,
    sync_status     TEXT NOT NULL DEFAULT 'synced',
    received_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (id, time),
    UNIQUE (device_name, device_local_id, time)
) PARTITION BY RANGE (time);

CREATE TABLE oil_in_out_2026_08 PARTITION OF oil_in_out FOR VALUES FROM ('2026-08-01') TO ('2026-09-01');
CREATE TABLE oil_in_out_2026_09 PARTITION OF oil_in_out FOR VALUES FROM ('2026-09-01') TO ('2026-10-01');

-- Same reasoning as alarms_device_name_time_idx above, for GET /api/oil-in-out.
CREATE INDEX oil_in_out_device_name_time_idx ON oil_in_out (device_name, time);

-- Mirrors device CommitWork table (design doc §2, CommitWork). Not partitioned per CLAUDE.md
-- (only Alarm/OilInOut are called out as high-volume). `info` is stored as the raw JSON string
-- the device sends, unparsed — CLAUDE.md "Open product decisions" leaves raw-vs-structured as an
-- explicit open call; raw text is the reversible default that doesn't foreclose parsing later.
CREATE TABLE commit_works (
    id              BIGSERIAL PRIMARY KEY,
    device_name     TEXT NOT NULL REFERENCES devices (device_name),
    device_local_id INTEGER NOT NULL,
    work_id         INTEGER NOT NULL,
    time            TIMESTAMPTZ NOT NULL,
    end_time        TIMESTAMPTZ,
    info            TEXT NOT NULL,
    sync_status     TEXT NOT NULL DEFAULT 'synced',
    received_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (device_name, device_local_id)
);

-- Supports GET /api/shifts' query shape (device_name = X AND time BETWEEN ... ORDER BY time) —
-- the UNIQUE constraint above doesn't include time at all.
CREATE INDEX commit_works_device_name_time_idx ON commit_works (device_name, time);

-- Mirrors device OilCanSet.java field-for-field (design doc §2's OilCanSet table only listed a
-- subset — cross-checked directly against app/src/main/java/com/sansen/yeweiyi/db/OilCanSet.java
-- in the Android repo and found ~13 real device columns the doc's summary table omitted; those are
-- included below so this table doesn't silently drop data once remote config write-back reads real
-- device fields). pk_oilCanId is already scoped 1-9 per device, so (device_name, oil_can_id) is the
-- natural composite primary key. gmt_create/gmt_modified are kept as raw TEXT, not parsed to a
-- timestamp type — their on-device string format isn't documented anywhere and this table isn't
-- partitioned, so there's no forcing reason to guess at a format.
CREATE TABLE oil_can_sets (
    device_name          TEXT NOT NULL REFERENCES devices (device_name),
    oil_can_id           INTEGER NOT NULL,
    pk_d2d_id            INTEGER NOT NULL,
    pk_tan_zhen_id       INTEGER NOT NULL,
    oil_height           DOUBLE PRECISION NOT NULL,
    height               INTEGER NOT NULL,
    max_volume           INTEGER NOT NULL,
    height_oil_alarm     INTEGER NOT NULL,
    hheight_oil_alarm    INTEGER NOT NULL,
    low_oil_alarm        INTEGER NOT NULL,
    llow_oil_alarm       INTEGER NOT NULL,
    height_water_alarm   INTEGER NOT NULL,
    high_temp_alarm      INTEGER NOT NULL,
    low_temp_alarm       INTEGER NOT NULL,
    oil_correction       DOUBLE PRECISION NOT NULL,
    water_correction     DOUBLE PRECISION NOT NULL,
    temp_correction      DOUBLE PRECISION NOT NULL,
    density              DOUBLE PRECISION NOT NULL,
    dens_var             DOUBLE PRECISION NOT NULL,
    alarm_byte           INTEGER NOT NULL,
    oil_type             INTEGER NOT NULL,
    oil_color            INTEGER NOT NULL,
    gmt_create           TEXT,
    gmt_modified         TEXT,
    chuan_gan_qi_height  REAL NOT NULL,
    gun_type             INTEGER NOT NULL,
    enable               INTEGER NOT NULL DEFAULT 0,
    sync_status          TEXT NOT NULL DEFAULT 'synced',
    received_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (device_name, oil_can_id)
);

-- Cloud -> device write-back queue (design doc §5.2). Field list is exactly what that section
-- specifies: id, deviceName, commandType, payload, status, attempts, createdAt (+ updated_at for
-- tracking the pending/sent/acked/failed transitions described there).
CREATE TABLE write_back_commands (
    id           BIGSERIAL PRIMARY KEY,
    device_name  TEXT NOT NULL REFERENCES devices (device_name),
    command_type TEXT NOT NULL,
    payload      JSONB NOT NULL,
    status       TEXT NOT NULL DEFAULT 'pending'
                 CHECK (status IN ('pending', 'sent', 'acked', 'failed')),
    attempts     INTEGER NOT NULL DEFAULT 0,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Supports "取出该设备全部 pending 命令(按创建时间排序)" (design doc §5.2) and the TTL sweep (§5.3).
CREATE INDEX write_back_commands_device_status_idx
    ON write_back_commands (device_name, status, created_at);
