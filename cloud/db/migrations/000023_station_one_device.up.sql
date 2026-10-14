-- 油站与大屏一对一（cloud#61）：一个非空 station_id 至多关联一台设备。
-- 先决检查：库里已有一站多设备时迁移直接失败并列出冲突，不自动挑选、不清空、不删除任何设备或数据，
-- 由人工评审后处理（解除多余设备的油站关联，或确认保留哪一台）再重跑。
DO $$
DECLARE
    conflicts TEXT;
BEGIN
    SELECT string_agg(format('station_id=%s devices=[%s]', station_id, names), '; ' ORDER BY station_id)
      INTO conflicts
      FROM (
          SELECT station_id, string_agg(device_name, ', ' ORDER BY device_name) AS names
            FROM devices
           WHERE station_id IS NOT NULL
           GROUP BY station_id
          HAVING count(*) > 1
      ) dup;
    IF conflicts IS NOT NULL THEN
        RAISE EXCEPTION 'cannot enforce one device per station, resolve these first: %', conflicts;
    END IF;
END $$;

-- 唯一索引覆盖原来的普通索引（同一列、同样可用于按油站查设备）。
DROP INDEX IF EXISTS devices_station_id_idx;
CREATE UNIQUE INDEX devices_station_id_key ON devices (station_id) WHERE station_id IS NOT NULL;

COMMENT ON COLUMN devices.station_id IS '所属油站，空 = 未分组/待安装。必须属于 devices.customer_id 同一客户（复合外键 + CHECK 保证）；同一非空油站至多一台设备（devices_station_id_key，cloud#61）。设备换客户时由 SetDeviceCustomer 同时清空。';
