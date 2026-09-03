-- Recreates the legacy tables dropped by the up migration.
--
-- Structure only: the data is not recoverable, because dropping a table discards it. That is
-- acceptable here and was checked before writing this — all five tables were empty when they were
-- dropped, since the ingestion that used to fill them had already been removed. This exists so the
-- repository's down-then-up round-trip convention still holds and so the historical schema stays
-- readable, not because a rollback would restore anything.
--
-- The DDL below is reproduced from 000001_init_schema.up.sql.

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
CREATE INDEX alarms_device_name_time_idx ON alarms (device_name, time);

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
CREATE INDEX oil_in_out_device_name_time_idx ON oil_in_out (device_name, time);

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

CREATE INDEX commit_works_device_name_time_idx ON commit_works (device_name, time);

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

CREATE INDEX write_back_commands_device_status_idx
    ON write_back_commands (device_name, status, created_at);
