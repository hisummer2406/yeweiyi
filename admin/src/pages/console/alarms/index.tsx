import { PageContainer } from '@ant-design/pro-components';

/**
 * 报警记录——对接 yeweiyi-cloud 的 GET /api/alarms（读业务库 alarm_events），
 * 对应设备端底栏「报警记录」与小程序 records 的 alarms 页签。
 */
const ConsoleAlarms: React.FC = () => {
  return <PageContainer title="报警记录" />;
};

export default ConsoleAlarms;
