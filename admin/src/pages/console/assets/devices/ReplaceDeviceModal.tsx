import { useIntl } from '@umijs/max';
import { Alert, Descriptions, Form, Modal, message, Select } from 'antd';
import type React from 'react';
import { useRef, useState } from 'react';
import { ApiError } from '@/services/ant-design-pro/apiError';
import { replaceStationDevice } from '@/services/ant-design-pro/stations';
import { describeReplaceError } from '../stations/errors';

type Props = {
  /** 要被替换的旧设备（必须在某个油站里）；undefined 时弹窗关闭 */
  device?: API.DeviceRow;
  /** 当前可见的全部设备行，候选新设备从中挑 */
  devices: API.DeviceRow[];
  stations: API.Station[];
  onClose: () => void;
  /** 云端状态已变化（或可能已变化），调用方应刷新列表 */
  onDone: () => void;
};

/**
 * 同站换机（yeweiyi-admin#15，契约 yeweiyi-cloud docs/station-device-replacement-contract.md v1.0 §3.2）：
 * 油站与大屏一对一，换大屏 = 云端在一个事务里解除旧设备、关联新设备（POST /api/stations/:id/replace-device）。
 * - 新设备必须已经按完整出厂编码**独立登记**，这里只能从已登记设备里选；不提供编辑旧编码的入口。
 * - 请求带 expectedCurrentDeviceName = 页面看到的旧设备码：页面过期（别人已换）云端答 409 station_device_changed，
 *   不会盲替。
 * - 候选：同一客户、尚未分配油站（station_id 为 null）的其它设备，这只是不让用户选到；
 *   跨客户、已在别的油站、未登记等由云端按 code 裁决，界面如实提示。
 * - 明确显示旧/新编码，并提示新设备重新采集、不接续旧设备历史、授权不自动复制。
 * - 任何失败都会刷新列表并关闭弹窗，显示云端真实状态；失败不留半成品（云端事务）。
 */
const ReplaceDeviceModalInner: React.FC<Props> = ({
  device,
  devices,
  stations,
  onClose,
  onDone,
}) => {
  const intl = useIntl();
  const t = (id: string, values?: Record<string, string>) =>
    intl.formatMessage({ id }, values);
  const [form] = Form.useForm<{ newDevice?: string }>();
  const [submitting, setSubmitting] = useState(false);
  const busy = useRef(false);

  const stationId = device?.station_id;
  const stationName =
    typeof stationId === 'number'
      ? (stations.find((s) => s.id === stationId)?.name ?? `#${stationId}`)
      : '';
  const customerId = device?.customer_id;
  const candidates = devices
    .filter(
      (d) =>
        d.device_name !== device?.device_name &&
        d.station_id === null &&
        d.customer_id !== null &&
        (typeof customerId !== 'number' || d.customer_id === customerId),
    )
    .map((d) => ({ value: d.device_name, label: d.device_name }));

  const close = () => {
    onClose();
    form.resetFields();
  };

  const submit = async ({ newDevice }: { newDevice?: string }) => {
    if (!device || typeof stationId !== 'number' || !newDevice || busy.current)
      return;
    busy.current = true;
    setSubmitting(true);
    try {
      const res = await replaceStationDevice(stationId, {
        deviceName: newDevice,
        expectedCurrentDeviceName: device.device_name,
      });
      message.success(
        t(
          res.changed
            ? 'pages.console.stations.replace.success'
            : 'pages.console.stations.replace.unchanged',
          {
            station: stationName,
            oldDevice: device.device_name,
            newDevice,
          },
        ),
      );
      close();
      onDone();
    } catch (err) {
      const apiErr = err instanceof ApiError ? err : undefined;
      if (apiErr?.authHandled) return; // 鉴权失败，已在回登录页
      const m = describeReplaceError(
        apiErr?.status ?? -1,
        apiErr?.error ?? (err as Error)?.message,
        apiErr?.code,
        apiErr?.currentDeviceName,
      );
      message.error(t(m.id, { detail: m.detail ?? '' }));
      // 云端有明确回答（400/404/409）= 页面状态已过期或选择不合法：关弹窗并刷新，显示真实状态
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
      title={t('pages.console.stations.replace.title', {
        station: stationName,
      })}
      okText={t('pages.console.stations.replace.submit')}
      cancelText={t('pages.console.stations.form.cancel')}
      confirmLoading={submitting}
      okButtonProps={{ disabled: candidates.length === 0 }}
      onOk={() => form.submit()}
      onCancel={close}
      destroyOnHidden
    >
      <Descriptions
        column={1}
        size="small"
        style={{ marginBottom: 16 }}
        items={[
          {
            key: 'station',
            label: t('pages.console.stations.replace.station'),
            children: stationName,
          },
          {
            key: 'old',
            label: t('pages.console.stations.replace.old'),
            children: device?.device_name,
          },
        ]}
      />
      <Alert
        type="warning"
        showIcon
        style={{ marginBottom: 16 }}
        title={t('pages.console.stations.replace.notice')}
      />
      {candidates.length === 0 && (
        <Alert
          type="info"
          showIcon
          style={{ marginBottom: 16 }}
          title={t('pages.console.stations.replace.noCandidates')}
        />
      )}
      <Form form={form} layout="vertical" onFinish={submit}>
        <Form.Item
          name="newDevice"
          label={t('pages.console.stations.replace.new')}
          rules={[{ required: true }]}
        >
          <Select
            showSearch
            optionFilterProp="label"
            placeholder={t('pages.console.stations.replace.newPlaceholder')}
            options={candidates}
          />
        </Form.Item>
      </Form>
    </Modal>
  );
};

/** 目标设备或其油站一变就重建内部状态（表单），不把上一台的选择带到下一台。 */
const ReplaceDeviceModal: React.FC<Props> = (props) => (
  <ReplaceDeviceModalInner
    key={`${props.device?.device_name ?? ''}|${props.device?.station_id ?? ''}`}
    {...props}
  />
);

export default ReplaceDeviceModal;
