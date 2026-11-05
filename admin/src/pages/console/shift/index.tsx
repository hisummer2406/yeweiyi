import { PageContainer } from '@ant-design/pro-components';
import { useIntl } from '@umijs/max';
import DevicePicker from '../components/DevicePicker';
import ShiftRecords from '../components/ShiftRecords';

/** 交班记录——对接 GET /api/shift-records（cloud#85），对应设备端底栏「交班记录」与小程序「我的记录」的交班页签。 */
const ConsoleShift: React.FC = () => {
  const intl = useIntl();
  return (
    <PageContainer
      title={intl.formatMessage({ id: 'pages.console.records.title.shift' })}
    >
      <DevicePicker>
        {(device) => <ShiftRecords device={device} />}
      </DevicePicker>
    </PageContainer>
  );
};

export default ConsoleShift;
