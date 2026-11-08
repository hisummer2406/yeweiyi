import { useIntl } from '@umijs/max';
import { Alert, Button, Space, Typography } from 'antd';
import type React from 'react';
import { commandPhase } from '@/utils/deviceConfig';
import type { TrackerState } from './useCommandTracker';

type Props = TrackerState & {
  onRefreshEcho: () => void;
  onDismiss: () => void;
};

/**
 * 一条配置命令的进度与结果。received 只是"设备已接收"；succeeded 之后还要看回显才说"已一致"；
 * expired 不表示没执行。
 */
const CommandProgress: React.FC<Props> = ({
  command,
  echo,
  echoTimedOut,
  pollFailing,
  onRefreshEcho,
  onDismiss,
}) => {
  const intl = useIntl();
  const t = (id: string, v?: Record<string, string | number>) =>
    intl.formatMessage({ id }, v);
  if (!command) return null;
  const phase = commandPhase(command);

  let type: 'info' | 'success' | 'warning' | 'error' = 'info';
  let message = t(`pages.console.settings.phase.${phase}`);
  let description: React.ReactNode;

  if (phase === 'succeeded') {
    if (echo === 'matched') {
      type = 'success';
      message = t('pages.console.settings.echo.matched');
    } else if (echo === 'mismatch') {
      type = 'warning';
      message = t('pages.console.settings.echo.mismatch');
    } else {
      message = t(
        echoTimedOut
          ? 'pages.console.settings.echo.timedOut'
          : 'pages.console.settings.echo.waiting',
      );
      type = echoTimedOut ? 'warning' : 'info';
    }
    const ignored = command.resultDetail?.ignored;
    if (Array.isArray(ignored) && ignored.length) {
      description = t('pages.console.settings.result.ignored', {
        fields: ignored
          .map((f) => t(`pages.console.settings.field.${f}`))
          .join('、'),
      });
    }
  } else if (phase === 'rejected' || phase === 'failed') {
    type = 'error';
    description = command.errorReason || command.errorCode;
  } else if (phase === 'expired') {
    type = 'warning';
  }

  return (
    <Alert
      style={{ marginBottom: 16 }}
      type={type}
      showIcon
      message={message}
      description={
        <Space direction="vertical" size={2}>
          {description && <span>{description}</span>}
          <Typography.Text type="secondary" style={{ fontSize: 12 }}>
            {t('pages.console.settings.commandId', { id: command.commandId })}
          </Typography.Text>
          {pollFailing && (
            <Typography.Text type="warning">
              {t('pages.console.settings.pollFailing')}
            </Typography.Text>
          )}
        </Space>
      }
      action={
        <Space direction="vertical">
          {phase === 'succeeded' && echo !== 'matched' && (
            <Button size="small" onClick={onRefreshEcho}>
              {t('pages.console.settings.echo.refresh')}
            </Button>
          )}
          {command.terminal && (
            <Button size="small" type="text" onClick={onDismiss}>
              {t('pages.console.settings.dismiss')}
            </Button>
          )}
        </Space>
      }
    />
  );
};

export default CommandProgress;
