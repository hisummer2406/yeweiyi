import { PageContainer } from '@ant-design/pro-components';
import { useIntl } from '@umijs/max';
import AlarmRecords from '../components/AlarmRecords';
import DevicePicker from '../components/DevicePicker';

/**
 * 报警记录——对接 yeweiyi-cloud 的 GET /api/alarms（读业务库 alarm_events），
 * 对应设备端底栏「报警记录」与小程序 records 的 alarms 页签。云端接口必须带 device，
 * 所以顶部先选设备（?device= 可直达），选好后才请求。
 */
const ConsoleAlarms: React.FC = () => {
  const intl = useIntl();
  return (
    <PageContainer
      title={intl.formatMessage({ id: 'pages.console.records.title.alarms' })}
    >
      <DevicePicker>
        {(device) => <AlarmRecords device={device} />}
      </DevicePicker>
    </PageContainer>
  );
};

export default ConsoleAlarms;
