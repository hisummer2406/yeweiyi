import { LockOutlined, UserOutlined } from '@ant-design/icons';
import { LoginForm, ProFormText } from '@ant-design/pro-components';
import {
  FormattedMessage,
  Helmet,
  SelectLang,
  useIntl,
  useModel,
} from '@umijs/max';
import { Alert, App } from 'antd';
import { createStyles } from 'antd-style';
import React, { startTransition, useState } from 'react';
import { Footer } from '@/components';
import { login } from '@/services/ant-design-pro/api';
import {
  classifyLoginError,
  clearToken,
  isStaffLogin,
  sessionLabel,
  setProfile,
  setToken,
} from '@/utils/auth';
import { buildLoginParams } from '@/utils/loginIdentifier';
import Settings from '../../../../config/defaultSettings';

/**
 * Validate redirect URL to prevent open redirect attacks.
 * Only allow same-origin relative paths starting with '/'.
 */
const getSafeRedirectUrl = (redirect: string | null): string => {
  if (!redirect?.startsWith('/')) return '/';

  if (redirect.startsWith('//')) return '/';

  try {
    const parsed = new URL(redirect, window.location.origin);
    if (parsed.origin !== window.location.origin) return '/';
    return `${parsed.pathname}${parsed.search}${parsed.hash}`;
  } catch {
    return '/';
  }
};

const useStyles = createStyles(({ token }) => {
  return {
    lang: {
      width: 42,
      height: 42,
      lineHeight: '42px',
      position: 'fixed',
      right: 16,
      borderRadius: token.borderRadius,
      ':hover': {
        backgroundColor: token.colorBgTextHover,
      },
    },
    container: {
      display: 'flex',
      flexDirection: 'column',
      height: '100vh',
      overflow: 'auto',
      backgroundImage:
        "url('https://mdn.alipayobjects.com/yuyan_qk0oxh/afts/img/V-_oS6r-i7wAAAAAAAAAAAAAFl94AQBr')",
      backgroundSize: '100% 100%',
    },
  };
});

const Lang = () => {
  const { styles } = useStyles();

  return (
    <div className={styles.lang} data-lang>
      {SelectLang && <SelectLang />}
    </div>
  );
};

const LoginMessage: React.FC<{
  content: string;
}> = ({ content }) => {
  return (
    <Alert
      style={{
        marginBottom: 24,
      }}
      title={content}
      type="error"
      showIcon
    />
  );
};

const Login: React.FC = () => {
  const [errorKind, setErrorKind] = useState<
    'credentials' | 'failure' | 'staff' | 'notOnboarded'
  >();
  const { initialState, setInitialState } = useModel('@@initialState');
  const { styles } = useStyles();
  const { message } = App.useApp();
  const intl = useIntl();

  const handleSubmit = async (values: {
    username: string;
    password: string;
  }) => {
    setErrorKind(undefined);
    try {
      const { token, user } = await login(
        buildLoginParams(values.username, values.password),
      );
      // 员工没有网页后台，但可以有密码（用于小程序／App），登录会成功：在这里拒绝，不保存令牌，
      // 并给明确提示，不能让人以为是密码错。
      if (isStaffLogin(token, user)) {
        clearToken();
        setErrorKind('staff');
        return;
      }
      setToken(token);
      if (user) setProfile(user.id, sessionLabel(user));
      const userInfo = await initialState?.fetchUserInfo?.();
      if (userInfo) {
        startTransition(() => {
          setInitialState((s) => ({ ...s, currentUser: userInfo }));
        });
      }
      message.success(
        intl.formatMessage({
          id: 'pages.login.success',
          defaultMessage: '登录成功！',
        }),
      );
      const urlParams = new URL(window.location.href).searchParams;
      window.location.href = getSafeRedirectUrl(urlParams.get('redirect'));
    } catch (error) {
      setErrorKind(classifyLoginError(error));
    }
  };

  return (
    <div className={styles.container}>
      <Helmet>
        <title>
          {intl.formatMessage({
            id: 'menu.login',
            defaultMessage: '登录页',
          })}
          {Settings.title && ` - ${Settings.title}`}
        </title>
      </Helmet>
      <Lang />
      <div
        style={{
          flex: '1',
          display: 'flex',
          flexDirection: 'column',
          padding: '32px 0',
        }}
      >
        <LoginForm
          // LoginForm 的 container 本身是占满父级的纵向 flex，内容默认贴顶；
          // 在它自己身上居中，标题与表单作为一个整体上下左右居中。
          containerStyle={{
            height: 'auto',
            justifyContent: 'center',
          }}
          contentStyle={{
            minWidth: 280,
            maxWidth: '75vw',
          }}
          // 公司 LOGO 自带中文名与拼音，直接作为标题展示，不再叠加文字标题。
          logo={false}
          title={
            <img
              alt="中成电气"
              src={Settings.logo}
              style={{ height: 48, verticalAlign: 'middle' }}
            />
          }
          subTitle={intl.formatMessage({
            id: 'pages.layouts.userLayout.title',
          })}
          onFinish={async (values) => {
            await handleSubmit(
              values as { username: string; password: string },
            );
          }}
        >
          {errorKind === 'credentials' && (
            <LoginMessage
              content={intl.formatMessage({
                id: 'pages.login.accountLogin.errorMessage',
                defaultMessage: '账号或密码错误',
              })}
            />
          )}
          {errorKind === 'notOnboarded' && (
            <LoginMessage
              content={intl.formatMessage({
                id: 'pages.login.customerNotOnboarded',
                defaultMessage: '所在客户尚未完成开通，请联系平台管理员。',
              })}
            />
          )}
          {errorKind === 'staff' && (
            <LoginMessage
              content={intl.formatMessage({
                id: 'pages.login.staffNoWeb',
                defaultMessage: '员工账号请使用小程序或 App 登录。',
              })}
            />
          )}
          {errorKind === 'failure' && (
            <LoginMessage
              content={intl.formatMessage({
                id: 'pages.login.failure',
                defaultMessage: '登录失败，请重试！',
              })}
            />
          )}
          <ProFormText
            name="username"
            fieldProps={{
              size: 'large',
              prefix: <UserOutlined />,
              autoComplete: 'username',
            }}
            placeholder={intl.formatMessage({
              id: 'pages.login.username.placeholder',
              defaultMessage: '手机号 / 用户名',
            })}
            rules={[
              {
                required: true,
                message: (
                  <FormattedMessage
                    id="pages.login.username.required"
                    defaultMessage="请输入手机号或用户名!"
                  />
                ),
              },
            ]}
          />
          <ProFormText.Password
            name="password"
            fieldProps={{
              size: 'large',
              prefix: <LockOutlined />,
              autoComplete: 'current-password',
            }}
            placeholder={intl.formatMessage({
              id: 'pages.login.password.placeholder',
              defaultMessage: '密码',
            })}
            rules={[
              {
                required: true,
                message: (
                  <FormattedMessage
                    id="pages.login.password.required"
                    defaultMessage="请输入密码！"
                  />
                ),
              },
            ]}
          />
        </LoginForm>
      </div>
      <Footer />
    </div>
  );
};

export default Login;
