package com.sansen.yeweiyi.fragment;

import android.app.AlertDialog;
import android.databinding.Observable;
import android.databinding.ObservableField;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.sansen.yeweiyi.R;
import com.sansen.yeweiyi.base.BaseFragment;
import com.sansen.yeweiyi.bean.SerialPortEntrity;
import com.sansen.yeweiyi.logic.thingsboard.DeviceCredentials;
import com.sansen.yeweiyi.manager.ThingsBoardCredentialStore;
import com.sansen.yeweiyi.manager.ThingsBoardManager;
import com.sansen.yeweiyi.sharepre.manager.SharePreManager;
import com.sansen.yeweiyi.utils.KeyboardAdjustHelper;
import com.sansen.yeweiyi.utils.SystemUtils;
import com.sansen.yeweiyi.utils.ToastUtils;

import bean.IPBean;

/**
 * 云平台（ThingsBoard）连接设置：broker 地址/端口 + 设备编号（贴纸上的，设备自己读不到）。
 * 访问令牌留空时由 {@link ThingsBoardManager} 用编号自动领取；手填令牌是兜底。
 * 页面文案通过字符串资源显示，入口向全部可选语言开放。
 */
public class ThingsBoardSetFragment extends BaseFragment {

    /** 贴纸编号的固定前缀（如 ZCT000001），未配置时预填，现场只需补数字。 */
    private static final String DEVICE_ID_PREFIX = "ZCT";

    private EditText et_broker_host, et_broker_port, et_device_id, et_access_token;
    private TextView tv_connection_status;
    private LinearLayout ly_saveData, ly_upper_page;
    private boolean credentialsLocked;

    @Override
    protected View initView(LayoutInflater inflater) {
        return inflater.inflate(R.layout.fragment_thingsboard_set, null);
    }

    @Override
    protected void initData(Bundle savedInstanceState) {
        findView();
        KeyboardAdjustHelper.applyPanWhileVisible(this);
    }

    @Override
    public void onHiddenChanged(boolean hidden) {
        super.onHiddenChanged(hidden);
        if (!hidden) {
            KeyboardAdjustHelper.applyPanWhileVisible(this);
        }
    }

    @Override
    public void findView() {
        et_broker_host = rootView.findViewById(R.id.et_broker_host);
        et_broker_port = rootView.findViewById(R.id.et_broker_port);
        et_device_id = rootView.findViewById(R.id.et_device_id);
        et_access_token = rootView.findViewById(R.id.et_access_token);
        tv_connection_status = rootView.findViewById(R.id.tv_connection_status);
        ly_saveData = rootView.findViewById(R.id.ly_saveData);
        ly_upper_page = rootView.findViewById(R.id.ly_upper_page);
        ly_saveData.setOnClickListener(this);
        ly_upper_page.setOnClickListener(this);
        KeyboardAdjustHelper.hideActionBarWhileKeyboardVisible(rootView, ly_saveData, ly_upper_page);

        IPBean ipBean = SharePreManager.getIpBean();
        et_broker_host.setText(ipBean.getIp());
        et_broker_port.setText(String.valueOf(ipBean.getPort()));
        DeviceCredentials credentials = new ThingsBoardCredentialStore(main.getApplicationContext()).load();
        et_device_id.setText(TextUtils.isEmpty(credentials.getDeviceId())
                ? DEVICE_ID_PREFIX : credentials.getDeviceId());
        et_access_token.setText(credentials.getAccessToken());
        updateConnectionStatus();

        View headerView = rootView.findViewById(R.id.title_bar);
        tvTitle = headerView.findViewById(R.id.tv_title);
        tv_time = headerView.findViewById(R.id.main_time);
        tvTitle.setText(R.string.tb_settings);
        SerialPortEntrity.time.addOnPropertyChangedCallback(callback);
    }

    private void updateConnectionStatus() {
        ThingsBoardManager manager = ThingsBoardManager.getInstance(main.getApplicationContext());
        String text = SystemUtils.getRsStr(manager.isConnected()
                ? R.string.tb_status_connected : R.string.tb_status_disconnected);
        ThingsBoardManager.ProvisionStatus status = manager.getProvisionStatus();
        String provision = describeProvisionStatus(status);
        tv_connection_status.setText(provision == null ? text : text + "\n" + provision);
        lockCredentialsIfConnectedBefore();
        if (status.state == ThingsBoardManager.ProvisionState.SUCCESS) {
            fillProvisionedToken();
        }
    }

    /** 领取成功后把令牌回填到令牌框，免得现场以为还是空的。 */
    private void fillProvisionedToken() {
        if (!TextUtils.isEmpty(et_access_token.getText())) {
            return;
        }
        DeviceCredentials stored = new ThingsBoardCredentialStore(main.getApplicationContext()).load();
        if (stored.isValid() && stored.getDeviceId().equals(et_device_id.getText().toString().trim())) {
            et_access_token.setText(stored.getAccessToken());
        }
    }

    /**
     * 当前编号连接成功过就锁定编号和令牌，断网、重启后同样锁：换编号会覆盖旧编号的令牌，而云端每个编号
     * 只允许领一次，换回去必然领取失败。令牌解不开时不锁，留给现场手填令牌恢复。
     */
    private void lockCredentialsIfConnectedBefore() {
        if (credentialsLocked) {
            return;
        }
        ThingsBoardCredentialStore store = new ThingsBoardCredentialStore(main.getApplicationContext());
        if (!store.hasConnected() || store.hasUnreadableToken()) {
            return;
        }
        credentialsLocked = true;
        for (EditText et : new EditText[]{et_device_id, et_access_token}) {
            et.setEnabled(false);
            et.setAlpha(0.5f);
        }
    }

    private static String describeProvisionStatus(ThingsBoardManager.ProvisionStatus status) {
        switch (status.state) {
            case IN_PROGRESS:
                return SystemUtils.getRsStr(R.string.tb_provision_in_progress);
            case SUCCESS:
                return SystemUtils.getRsStr(R.string.tb_provision_success);
            case REJECTED:
                return SystemUtils.getRsStr(R.string.tb_provision_rejected,
                        status.errorMessage == null ? "" : status.errorMessage);
            case NETWORK_ERROR:
                return SystemUtils.getRsStr(R.string.tb_provision_network_error);
            case NOT_CONFIGURED:
                return SystemUtils.getRsStr(R.string.tb_provision_not_configured);
            case TOKEN_UNREADABLE:
                return SystemUtils.getRsStr(R.string.tb_provision_token_unreadable);
            default:
                return null;
        }
    }

    Observable.OnPropertyChangedCallback callback = new Observable.OnPropertyChangedCallback() {
        @Override
        public void onPropertyChanged(Observable sender, int propertyId) {
            String newValue = ((ObservableField<String>) sender).get();
            tv_time.setText(newValue);
            // 时钟每秒在主线程刷新一次，顺带刷新连接/配网状态，不另起定时器
            updateConnectionStatus();
        }
    };
    TextView tvTitle, tv_time;

    @Override
    protected void processClick(View v) {
        if (v.getId() == R.id.ly_saveData) {
            save();
        } else if (v.getId() == R.id.ly_upper_page) {
            goBack();
        }
    }

    private void save() {
        String host = et_broker_host.getText().toString().trim();
        String portText = et_broker_port.getText().toString().trim();
        String deviceId = et_device_id.getText().toString().trim();
        String accessToken = et_access_token.getText().toString().trim();

        if (TextUtils.isEmpty(host)) {
            ToastUtils.showToast(SystemUtils.getRsStr(R.string.tb_broker_host_empty), 5);
            return;
        }
        int port;
        try {
            port = Integer.parseInt(portText);
        } catch (NumberFormatException e) {
            ToastUtils.showToast(SystemUtils.getRsStr(R.string.tb_port_not_number), 5);
            return;
        }
        if (port <= 0 || port > 65535) {
            ToastUtils.showToast(SystemUtils.getRsStr(R.string.tb_port_out_of_range), 5);
            return;
        }
        // 只有预填的前缀等于没填
        if (TextUtils.isEmpty(deviceId) || deviceId.equalsIgnoreCase(DEVICE_ID_PREFIX)) {
            ToastUtils.showToast(SystemUtils.getRsStr(R.string.tb_device_id_empty), 5);
            return;
        }

        DeviceCredentials stored = new ThingsBoardCredentialStore(main.getApplicationContext()).load();
        boolean sameDevice = deviceId.equals(stored.getDeviceId());
        if (!sameDevice && accessToken.equals(stored.getAccessToken())) {
            // 换了编号但令牌框还是回显的旧令牌：旧令牌属于旧编号，作废后按新编号重新领取
            accessToken = "";
        } else if (sameDevice && accessToken.isEmpty() && stored.isValid()) {
            // 编号没变、已有令牌：保留。清空会触发重新领取，而云端只允许领一次
            accessToken = stored.getAccessToken();
        }
        if (!accessToken.isEmpty()) {
            persist(host, port, deviceId, accessToken);
            return;
        }
        // 自动领取：先让现场核对编号，输错会领走另一台已登记设备的身份
        final String token = accessToken;
        new AlertDialog.Builder(main)
                .setTitle(SystemUtils.getRsStr(R.string.tb_confirm_device_id_title))
                .setMessage(SystemUtils.getRsStr(R.string.tb_confirm_device_id_message, deviceId))
                .setPositiveButton(SystemUtils.getRsStr(R.string.confirm),
                        (dialog, which) -> persist(host, port, deviceId, token))
                .setNegativeButton(SystemUtils.getRsStr(R.string.cancle), null)
                .show();
    }

    private void persist(String host, int port, String deviceId, String accessToken) {
        SharePreManager.saveIpBean(new IPBean(host, port, ""));
        new ThingsBoardCredentialStore(main.getApplicationContext())
                .save(new DeviceCredentials(deviceId, accessToken));
        ThingsBoardManager.getInstance(main.getApplicationContext()).applyUpdatedConnectionSettings(host, port);

        et_access_token.setText(accessToken);
        ToastUtils.showToast(SystemUtils.getRsStr(accessToken.isEmpty()
                ? R.string.tb_saved_provisioning : R.string.tb_saved), 5);
        updateConnectionStatus();
    }

    private void goBack() {
        if (main.setSeniorFragment == null) {
            main.setSeniorFragment = new SetSeniorFragment();
        }
        main.switchContent(main.mContent, main.setSeniorFragment);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        SerialPortEntrity.time.removeOnPropertyChangedCallback(callback);
    }

    @Override
    public void initTitle() {
    }

    @Override
    public void onReflushData() {

    }
}
