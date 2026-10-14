import {
  LogoutOutlined,
  SettingOutlined,
  SkinOutlined,
  TeamOutlined,
} from '@ant-design/icons';
import { history, useModel } from '@umijs/max';
import type { MenuProps } from 'antd';
import { Spin } from 'antd';
import React, { startTransition } from 'react';
import { clearToken } from '@/utils/auth';
import HeaderDropdown from '../HeaderDropdown';

type GlobalHeaderRightProps = {
  children?: React.ReactNode;
};

const roleLabels: Record<string, string> = {
  platform_admin: '平台管理员',
  customer_admin: '客户管理员',
  customer_user: '客户用户',
};

const buildMenuItems = (user: API.CurrentUser): MenuProps['items'] => [
  {
    key: 'identity',
    disabled: true,
    label: `${user.username}（${roleLabels[user.access] ?? user.access}）`,
  },
  {
    type: 'divider' as const,
  },
  {
    key: 'settings',
    icon: <SettingOutlined />,
    label: '个人设置',
  },
  // 平台管理员账号管理的入口（只有平台管理员看得到）
  ...(user.access === 'platform_admin'
    ? [
        {
          key: 'platform-admins',
          icon: <TeamOutlined />,
          label: '平台管理员账号',
        },
      ]
    : []),
  ...(process.env.NODE_ENV === 'development'
    ? [{ key: 'theme', icon: <SkinOutlined />, label: '主题设置' }]
    : []),
  {
    type: 'divider' as const,
  },
  {
    key: 'logout',
    icon: <LogoutOutlined />,
    label: '退出登录',
  },
];

// 后端没有登出接口：退出只是清掉本地 token（token 在过期前对后端仍然有效）。
const loginOut = () => {
  clearToken();
  history.replace('/user/login');
};

export const AvatarDropdown: React.FC<GlobalHeaderRightProps> = ({
  children,
}) => {
  const { initialState, setInitialState } = useModel('@@initialState');

  const onMenuClick: MenuProps['onClick'] = (event) => {
    const { key } = event;
    if (key === 'logout') {
      startTransition(() => {
        setInitialState((s) => ({ ...s, currentUser: undefined }));
      });
      loginOut();
      return;
    }
    if (key === 'theme' && process.env.NODE_ENV === 'development') {
      setInitialState((s) => ({ ...s, settingDrawerOpen: true }));
      return;
    }
    if (key === 'platform-admins') {
      history.push('/ops/platform-admins');
      return;
    }
    history.push(`/account/${key}`);
  };

  if (!initialState) {
    return <Spin size="small" />;
  }

  const { currentUser } = initialState;

  if (!currentUser) {
    return <Spin size="small" />;
  }

  return (
    <HeaderDropdown
      placement="bottomRight"
      menu={{
        selectedKeys: [],
        onClick: onMenuClick,
        items: buildMenuItems(currentUser),
      }}
      arrow
    >
      {children}
    </HeaderDropdown>
  );
};
