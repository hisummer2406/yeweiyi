import { useIntl } from '@umijs/max';
import {
  Alert,
  Button,
  InputNumber,
  Select,
  Space,
  Table,
  Typography,
} from 'antd';
import type React from 'react';
import { useEffect, useMemo, useState } from 'react';
import {
  aboveTankHeight,
  changedArgs,
  checkArgs,
  type Draft,
  type FieldDef,
  hasSignToggle,
} from '@/utils/deviceConfig';
import { BUILTIN_OIL_MAX, mediumText } from '@/utils/oilType';
import { DASH } from '@/utils/records';

/** 旧固件把 18 起的编号当自定义位置（#11），未支持名称同步的设备不可选 */
const FIRST_NEW_BUILTIN_INDEX = 18;

type Props = {
  fields: FieldDef[];
  /** 设备当前回报；null = 从未上报 */
  current: Record<string, unknown> | null;
  /** 可以编辑（角色允许且设备已开放、没有在途命令） */
  canEdit: boolean;
  /** 罐设置时传入，用于"超过罐高"提示 */
  tank?: API.TankSettings;
  /** 只读字段的说明（文案 id），如密度单位未确认 */
  readOnlyNote?: string;
  onSubmit: (args: Record<string, number>, draft: Draft) => void;
  /** 平台管理员才传：该设备可选的介质（#27）。不传时介质只显示、不能改。 */
  media?: API.MediaOptions;
  /** 改变即清空草稿（换罐、读回新配置） */
  resetKey: string;
};

/** 某字段的显示值；null/undefined 一律「—」，0 照常显示。 */
export function useFormatValue() {
  const intl = useIntl();
  const t = (id: string, v?: Record<string, string | number>) =>
    intl.formatMessage({ id }, v);
  return (f: FieldDef, v: unknown, raw?: unknown): string => {
    if (v === null || v === undefined) return DASH;
    if (f.kind === 'oil') {
      if (v === 0) return t('pages.console.settings.oil.unset');
      if (typeof v === 'number' && v > BUILTIN_OIL_MAX) {
        return typeof raw === 'string' && raw.trim()
          ? raw
          : t('pages.console.settings.oil.custom', { n: v });
      }
      return mediumText(v, raw, (id) => t(id));
    }
    if (f.key === 'tankShape') {
      return v === 1
        ? t('pages.console.settings.shape.vertical')
        : v === 0
          ? t('pages.console.settings.shape.horizontal')
          : String(v);
    }
    return f.unit ? `${v} ${f.unit}` : String(v);
  };
}

/**
 * 当前回报 + 新值输入的对照表。只把改动过的可编辑字段交给 onSubmit；本地预检（范围、整数、四级液位顺序）不过就不让提交。
 * InputNumber 故意不传 min/max：传了会在失焦时把越界值静默夹到边界（#28），越界要保留原值、提示并拦截提交；
 * 也不传小数 step：step=0.1 会把 1000 显示成 1000.0。
 */
const SettingsForm: React.FC<Props> = ({
  fields,
  current,
  canEdit,
  tank,
  readOnlyNote,
  media,
  onSubmit,
  resetKey,
}) => {
  const intl = useIntl();
  const t = (id: string, v?: Record<string, string | number>) =>
    intl.formatMessage({ id }, v);
  const fmt = useFormatValue();
  const [draft, setDraft] = useState<Draft>({});
  useEffect(() => setDraft({}), [resetKey]);

  // 介质另算：选的是 mediaId，不能和设备回报的编号比较，云端负责换算（#27）
  const numericFields = useMemo(
    () => fields.filter((f) => f.kind !== 'oil'),
    [fields],
  );
  const args = useMemo(() => {
    const out = changedArgs(numericFields, current, draft);
    const mediumField = fields.find((f) => f.kind === 'oil');
    const mediaId = mediumField && draft[mediumField.key];
    return typeof mediaId === 'number' ? { ...out, mediaId } : out;
  }, [fields, numericFields, current, draft]);
  const problems = useMemo(
    () => checkArgs(numericFields, current, args),
    [numericFields, current, args],
  );
  const tooHigh = aboveTankHeight(tank, args);
  const problemOf = (key: string) => problems.find((p) => p.field === key);

  const canChoose = (m: API.Medium) =>
    media?.customMediaSync ||
    (m.builtinIndex !== null && m.builtinIndex < FIRST_NEW_BUILTIN_INDEX);
  const option = (m: API.Medium) => ({
    value: m.id,
    label: m.name,
    disabled: !canChoose(m),
  });
  const mediumOptions = media
    ? [
        {
          label: t('pages.console.settings.medium.public'),
          options: media.public.map(option),
        },
        {
          label: t('pages.console.settings.medium.customer'),
          options: media.customer.map(option),
        },
      ]
    : [];

  return (
    <>
      <Table<FieldDef>
        size="small"
        pagination={false}
        rowKey="key"
        dataSource={fields}
        columns={[
          {
            title: t('pages.console.settings.col.field'),
            render: (_, f) => (
              <Space size={4}>
                {t(`pages.console.settings.field.${f.key}`)}
                {!f.editable && (
                  <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                    {t('pages.console.settings.readOnly')}
                  </Typography.Text>
                )}
              </Space>
            ),
          },
          {
            title: t('pages.console.settings.col.current'),
            render: (_, f) =>
              fmt(
                f,
                current?.[f.key],
                f.kind === 'oil' ? current?.oilType : undefined,
              ),
          },
          ...(canEdit
            ? [
                {
                  title: t('pages.console.settings.col.new'),
                  render: (_: unknown, f: FieldDef) => {
                    if (!f.editable || (f.kind === 'oil' && !media))
                      return null;
                    const p = problemOf(f.key);
                    const status = p ? ('error' as const) : undefined;
                    const label = t(`pages.console.settings.field.${f.key}`);
                    const input =
                      f.kind === 'oil' ? (
                        <Select
                          aria-label={label}
                          style={{ width: 200 }}
                          allowClear
                          showSearch
                          optionFilterProp="label"
                          status={status}
                          value={draft[f.key] ?? undefined}
                          options={mediumOptions}
                          onChange={(v) =>
                            setDraft((d) => ({ ...d, [f.key]: v ?? undefined }))
                          }
                        />
                      ) : (
                        <InputNumber
                          aria-label={label}
                          style={{ width: hasSignToggle(f) ? 200 : 160 }}
                          status={status}
                          precision={f.kind === 'int' ? 0 : undefined}
                          addonBefore={
                            hasSignToggle(f) ? (
                              <Button
                                type="text"
                                size="small"
                                aria-label={`${label} ${t('pages.console.settings.toggleSign')}`}
                                disabled={typeof draft[f.key] !== 'number'}
                                onClick={() =>
                                  setDraft((d) => ({
                                    ...d,
                                    [f.key]: -(d[f.key] as number),
                                  }))
                                }
                              >
                                ±
                              </Button>
                            ) : undefined
                          }
                          addonAfter={f.unit}
                          value={draft[f.key] ?? null}
                          onChange={(v) =>
                            setDraft((d) => ({
                              ...d,
                              [f.key]: typeof v === 'number' ? v : undefined,
                            }))
                          }
                        />
                      );
                    return (
                      <Space direction="vertical" size={0}>
                        {input}
                        {p && (
                          <Typography.Text
                            type="danger"
                            style={{ fontSize: 12 }}
                          >
                            {t(`pages.console.settings.problem.${p.code}`, {
                              min: f.min,
                              max: f.max,
                            })}
                          </Typography.Text>
                        )}
                      </Space>
                    );
                  },
                },
              ]
            : []),
        ]}
      />
      {readOnlyNote && (
        <Typography.Paragraph
          type="secondary"
          style={{ marginTop: 8, fontSize: 12 }}
        >
          {t(readOnlyNote)}
        </Typography.Paragraph>
      )}
      {canEdit && (
        <Space direction="vertical" style={{ marginTop: 12, width: '100%' }}>
          {tooHigh.length > 0 && (
            <Alert
              type="warning"
              showIcon
              message={t('pages.console.settings.aboveTankHeight', {
                fields: tooHigh
                  .map((k) => t(`pages.console.settings.field.${k}`))
                  .join('、'),
              })}
            />
          )}
          <Space>
            <Button
              type="primary"
              disabled={Object.keys(args).length === 0 || problems.length > 0}
              onClick={() => onSubmit(args, draft)}
            >
              {t('pages.console.settings.send')}
            </Button>
            <Button
              disabled={Object.keys(draft).length === 0}
              onClick={() => setDraft({})}
            >
              {t('pages.console.settings.reset')}
            </Button>
          </Space>
        </Space>
      )}
    </>
  );
};

export default SettingsForm;
