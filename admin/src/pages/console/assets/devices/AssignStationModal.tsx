import { useIntl } from '@umijs/max';
import { Alert, Form, Modal, message, Select } from 'antd';
import type React from 'react';
import { useRef, useState } from 'react';
import { ApiError } from '@/services/ant-design-pro/apiError';
import { assignDeviceStation } from '@/services/ant-design-pro/stations';
import { occupantOf } from '@/utils/stationOccupancy';
import { describeAssignStationError } from '../stations/errors';

type Props = {
  /** 要分配油站的设备；undefined 时弹窗关闭 */
  device?: API.DeviceRow;
  stations: API.Station[];
  /**
   * 当前可见的设备行，用来预提示「油站已有设备」（一个油站同时只有一台大屏，yeweiyi-admin#15）。
   * 这只是预提示，列表可能已过期：占用冲突与并发失败仍以云端的响应为准。缺省则不预提示。
   */
  devices?: API.DeviceRow[];
  onClose: () => void;
  onDone: () => void;
};

/**
 * 把设备分配到油站，或清空（未分组）。候选只含设备所属客户的油站：平台管理员的设备行带 customer_id，据此过滤；
 * 客户管理员的行没有这个字段，但它的油站列表本来就只有本客户的，不需要过滤。云端对"油站不属于设备所属客户"
 * 返回 400，这里的过滤只是不让用户选到它，数据库才是最后防线。
 */
const AssignStationModalInner: React.FC<Props> = ({
  device,
  stations,
  devices,
  onClose,
  onDone,
}) => {
  const intl = useIntl();
  const t = (id: string, values?: Record<string, string>) =>
    intl.formatMessage({ id }, values);
  const [form] = Form.useForm<{ stationId?: number | null }>();
  const [submitting, setSubmitting] = useState(false);
  const busy = useRef(false);

  const customerId = device?.customer_id;
  const options = stations
    .filter(
      (s) => typeof customerId !== 'number' || s.customerId === customerId,
    )
    .map((s) => {
      // 已被别的设备占用的油站不能静默覆盖：禁用并标出占用者；设备自己所在的油站不算占用
      const occupant = devices
        ? occupantOf(devices, s.id, device?.device_name)
        : undefined;
      return {
        value: s.id,
        label: occupant
          ? t('pages.console.stations.assign.occupied', {
              station: s.name,
              device: occupant,
            })
          : s.name,
        disabled: !!occupant,
      };
    });
  const hasOccupied = options.some((o) => o.disabled);

  const close = () => {
    onClose();
    form.resetFields();
  };

  const submit = async ({ stationId }: { stationId?: number | null }) => {
    if (!device || busy.current) return;
    busy.current = true;
    setSubmitting(true);
    try {
      // 清空时选择框给 undefined/null，统一成显式的 null（云端要求这个键必须存在）
      const target = stationId ?? null;
      const res = await assignDeviceStation(device.device_name, target);
      if (!res.changed) {
        message.success(t('pages.console.stations.assign.unchanged'));
      } else if (target === null) {
        message.success(
          t('pages.console.stations.assign.cleared', {
            device: device.device_name,
          }),
        );
      } else {
        message.success(
          t('pages.console.stations.assign.success', {
            device: device.device_name,
            station:
              stations.find((s) => s.id === target)?.name ?? String(target),
          }),
        );
      }
      close();
      onDone();
    } catch (err) {
      const apiErr = err instanceof ApiError ? err : undefined;
      if (apiErr?.authHandled) return; // 鉴权失败，已在回登录页
      const m = describeAssignStationError(
        apiErr?.status ?? -1,
        apiErr?.error ?? (err as Error)?.message,
        apiErr?.code,
        apiErr?.occupiedBy,
      );
      message.error(t(m.id, { detail: m.detail ?? '' }));
      // 400/404/409 说明页面上的设备、油站或占用关系已过期（别的管理员刚改过，或并发分配落败）：
      // 关掉弹窗并刷新，让用户看到真实状态再决定，而不是在过期选项上反复重试
      if (apiErr && [400, 404, 409].includes(apiErr.status)) {
        close();
        onDone();
      }
    } finally {
      busy.current = false;
      setSubmitting(false);
    }
  };

  return (
    <Modal
      open={!!device}
      title={`${t('pages.console.stations.assign.title')}：${device?.device_name ?? ''}`}
      okText={t('pages.console.stations.assign.submit')}
      cancelText={t('pages.console.stations.form.cancel')}
      confirmLoading={submitting}
      // 没有候选油站时唯一能提交的是「清空」，而设备本来就没有可清的油站，所以不让提交
      okButtonProps={{ disabled: options.length === 0 }}
      onOk={() => form.submit()}
      onCancel={close}
      destroyOnHidden
    >
      {options.length === 0 && (
        <Alert
          type="info"
          showIcon
          style={{ marginBottom: 16 }}
          title={t('pages.console.stations.assign.noStations')}
        />
      )}
      {hasOccupied && (
        <Alert
          type="warning"
          showIcon
          style={{ marginBottom: 16 }}
          title={t('pages.console.stations.assign.occupiedHint')}
        />
      )}
      <Form
        form={form}
        layout="vertical"
        initialValues={{ stationId: device?.station_id ?? undefined }}
        onFinish={submit}
      >
        <Form.Item
          name="stationId"
          label={t('pages.console.stations.assign.target')}
        >
          <Select
            allowClear
            showSearch
            optionFilterProp="label"
            placeholder={t('pages.console.stations.assign.placeholder')}
            options={options}
          />
        </Form.Item>
      </Form>
    </Modal>
  );
};

/**
 * 目标设备一变就整体重建内部的表单实例。Form.useForm 的字段仓库会在弹窗关闭后保留（destroyOnHidden 只销毁内容），
 * 而 initialValues 只在首次挂载时生效，close() 里的 resetFields 又会重置回「上一台设备」的油站——
 * 不重建的话，给下一台设备打开弹窗会显示并提交上一台的油站，把它静默分错（Codex 评审 P2）。
 */
const AssignStationModal: React.FC<Props> = (props) => (
  <AssignStationModalInner
    key={`${props.device?.device_name ?? ''}|${props.device?.station_id ?? ''}`}
    {...props}
  />
);

export default AssignStationModal;
