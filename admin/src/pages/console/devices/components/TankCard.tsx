import { dl } from '@/design-tokens';
import { RightOutlined } from '@ant-design/icons';
import React from 'react';

export type TankMetric = {
  label: string;
  value: number | string;
  unit: string;
};

export type TankShapeKind = 'horizontal' | 'vertical';

export type TankCardProps = {
  /** 罐号，如 "1号" */
  tankNo: string;
  /** 介质名，如 "甲醇" / "95#" */
  medium: string;
  /** 罐型——对应设计稿的卧罐/立罐（见 20/21 号屏），默认卧罐 */
  shape?: TankShapeKind;
  /** 是否离线/异常——影响标题条和罐体的视觉状态 */
  offline?: boolean;
  /** 液位/容积/水高/温度/质量，按设计稿顺序传入 */
  metrics: TankMetric[];
  onClick?: () => void;
};

/**
 * 罐体图形——简化还原设计稿 docs/design-exports/00_首页-卧罐.png / 20_首页-卧罐+立罐.png，
 * 不追求像素级美术还原，只保留可辨识的罐形轮廓（卧罐=横向圆柱+两端封头；立罐=竖向圆柱）。
 */
const TankShape: React.FC<{ shape: TankShapeKind; offline?: boolean }> = ({
  shape,
  offline,
}) => {
  const bodyFill = offline ? '#C8C8C8' : '#DCE3E6';
  const bodyStroke = offline ? '#A8A8A8' : '#B9C2C6';

  if (shape === 'vertical') {
    return (
      <svg viewBox="0 0 100 100" width="100%" height="100%" role="img" aria-label="立罐">
        <rect x="34" y="4" width="32" height="14" rx="3" fill="#9AA3A7" />
        <ellipse cx="50" cy="4" rx="16" ry="4" fill="#B4BCC0" />
        <rect
          x="18"
          y="16"
          width="64"
          height="72"
          rx="8"
          fill={bodyFill}
          stroke={bodyStroke}
          strokeWidth="1.5"
        />
        <line x1="34" y1="16" x2="34" y2="88" stroke={bodyStroke} strokeWidth="1.5" />
        <line x1="66" y1="16" x2="66" y2="88" stroke={bodyStroke} strokeWidth="1.5" />
        <rect x="14" y="88" width="72" height="8" rx="1" fill="#8C9498" />
      </svg>
    );
  }

  return (
    <svg viewBox="0 0 140 100" width="100%" height="100%" role="img" aria-label="卧罐">
      <rect x="10" y="60" width="18" height="8" rx="1" fill="#8C9498" />
      <rect x="112" y="60" width="18" height="8" rx="1" fill="#8C9498" />
      <rect
        x="12"
        y="28"
        width="116"
        height="42"
        rx="21"
        fill={bodyFill}
        stroke={bodyStroke}
        strokeWidth="1.5"
      />
      <line x1="42" y1="28" x2="42" y2="70" stroke={bodyStroke} strokeWidth="1.5" />
      <line x1="98" y1="28" x2="98" y2="70" stroke={bodyStroke} strokeWidth="1.5" />
      <rect x="60" y="14" width="14" height="16" rx="3" fill="#9AA3A7" />
      <ellipse cx="67" cy="14" rx="7" ry="3" fill="#B4BCC0" />
    </svg>
  );
};

const TankCard: React.FC<TankCardProps> = ({
  tankNo,
  medium,
  shape = 'horizontal',
  offline,
  metrics,
  onClick,
}) => {
  return (
    <div
      onClick={onClick}
      style={{
        width: '100%',
        background: dl.color.bgSurface,
        borderRadius: 6,
        overflow: 'hidden',
        cursor: onClick ? 'pointer' : 'default',
        boxShadow: '0 1px 3px rgba(0,0,0,0.08)',
        border: `1px solid ${dl.color.border}`,
      }}
    >
      <div
        style={{
          height: 32,
          padding: '0 12px',
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'space-between',
          background: offline ? dl.color.textTertiary : dl.color.primary,
          color: dl.color.textInverse,
          fontSize: dl.font.size.bodyLg,
        }}
      >
        <span>
          {tankNo} {medium}
        </span>
        {onClick && <RightOutlined style={{ fontSize: 12 }} />}
      </div>
      <div style={{ display: 'flex', padding: '14px 16px', gap: 12 }}>
        <div style={{ width: '38%', minWidth: 96 }}>
          <TankShape shape={shape} offline={offline} />
        </div>
        <div style={{ flex: 1 }}>
          {metrics.map((m) => (
            <div
              key={m.label}
              style={{
                display: 'flex',
                justifyContent: 'space-between',
                fontSize: dl.font.size.subtitle,
                color: dl.color.textPrimary,
                lineHeight: '22px',
              }}
            >
              <span style={{ color: '#252525' }}>{m.label}</span>
              <span>
                <span style={{ fontFamily: dl.font.familyNumeric, fontWeight: 500 }}>
                  {m.value}
                </span>
                <span style={{ color: dl.color.textSecondary, fontSize: dl.font.size.caption }}>
                  {' '}
                  {m.unit}
                </span>
              </span>
            </div>
          ))}
        </div>
      </div>
    </div>
  );
};

export default TankCard;
