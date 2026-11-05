import { PageContainer } from '@ant-design/pro-components';
import { useIntl } from '@umijs/max';
import DevicePicker from '../components/DevicePicker';
import InventoryRecords from '../components/InventoryRecords';

/** 库存记录——对接 GET /api/stock-records（cloud#85），对应设备端底栏「库存记录」与小程序「我的记录」的库存页签。 */
const ConsoleInventory: React.FC = () => {
  const intl = useIntl();
  return (
    <PageContainer
      title={intl.formatMessage({
        id: 'pages.console.records.title.inventory',
      })}
    >
      <DevicePicker>
        {(device) => <InventoryRecords device={device} />}
      </DevicePicker>
    </PageContainer>
  );
};

export default ConsoleInventory;
