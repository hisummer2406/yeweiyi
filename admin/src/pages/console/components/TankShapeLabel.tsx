import { useIntl } from '@umijs/max';
import type { tankShapeOf } from '@/utils/tankShape';
import { fmtTime } from '@/utils/telemetry';

export type TankShapeInfo = ReturnType<typeof tankShapeOf>;

export default function TankShapeLabel({ info }: { info: TankShapeInfo }) {
  const intl = useIntl();
  const text = (suffix: string) =>
    intl.formatMessage({ id: `pages.console.tank.shape.${suffix}` });
  return (
    <span
      title={
        info.reportedAt
          ? intl.formatMessage(
              { id: 'pages.console.tank.shape.reportedAt' },
              { time: fmtTime(info.reportedAt) },
            )
          : undefined
      }
    >
      {text(info.shape)}
    </span>
  );
}
