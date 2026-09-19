package com.sansen.yeweiyi.fragment;

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
import com.sansen.yeweiyi.utils.ToastUtils;

import bean.IPBean;

/**
 * 云平台（ThingsBoard）连接设置：broker 地址/端口 + 设备 deviceId/accessToken 的现场录入入口。
 * <p>
 * 在这个类之前，三者都没有任何写入路径——{@code ThingsBoardCredentialStore.save()} 全仓库
 * 无调用方，全新设备连不上云端；{@code SharePreManager.getIpBean()} 的默认分支硬编码着一个
 * 旧协议遗留 IP，{@code saveIpBean()} 同样无调用方（PR #38 人工审核 item 6 + 后续复审新发现）。
 * <p>
 * deviceId/accessToken 由云端后台注册设备时生成，现场人员手动抄录/粘贴录入——这台终端没有
 * 摄像头模块，走不了扫码配网。accessToken 落盘前由 {@link ThingsBoardCredentialStore} 用
 * Android Keystore 加密，这个 Fragment 只管收集输入、校验、转交，不接触加密细节。
 */
public class ThingsBoardSetFragment extends BaseFragment {

    private EditText et_broker_host, et_broker_port, et_device_id, et_access_token;
    private TextView tv_connection_status;
    private LinearLayout ly_saveData, ly_upper_page;

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
        et_device_id.setText(credentials.getDeviceId());
        et_access_token.setText(credentials.getAccessToken());
        updateConnectionStatus();

        View headerView = rootView.findViewById(R.id.title_bar);
        tvTitle = headerView.findViewById(R.id.tv_title);
        tv_time = headerView.findViewById(R.id.main_time);
        tvTitle.setText("云平台设置");
        SerialPortEntrity.time.addOnPropertyChangedCallback(callback);
    }

    private void updateConnectionStatus() {
        boolean connected = ThingsBoardManager.getInstance(main.getApplicationContext()).isConnected();
        tv_connection_status.setText(connected ? "当前状态：已连接云平台" : "当前状态：未连接云平台");
    }

    Observable.OnPropertyChangedCallback callback = new Observable.OnPropertyChangedCallback() {
        @Override
        public void onPropertyChanged(Observable sender, int propertyId) {
            String newValue = ((ObservableField<String>) sender).get();
            tv_time.setText(newValue);
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
            ToastUtils.showToast("Broker地址不能为空", 5);
            return;
        }
        int port;
        try {
            port = Integer.parseInt(portText);
        } catch (NumberFormatException e) {
            ToastUtils.showToast("端口必须是数字", 5);
            return;
        }
        if (port <= 0 || port > 65535) {
            ToastUtils.showToast("端口必须在 1-65535 之间", 5);
            return;
        }
        if (TextUtils.isEmpty(deviceId) || TextUtils.isEmpty(accessToken)) {
            ToastUtils.showToast("设备ID和访问令牌不能为空", 5);
            return;
        }

        SharePreManager.saveIpBean(new IPBean(host, port, ""));
        new ThingsBoardCredentialStore(main.getApplicationContext())
                .save(new DeviceCredentials(deviceId, accessToken));
        ThingsBoardManager.getInstance(main.getApplicationContext()).applyUpdatedConnectionSettings(host, port);

        ToastUtils.showToast("已保存，正在使用新配置重新连接云平台", 5);
        updateConnectionStatus();
    }

    private void goBack() {
        if (main.setSeniorFragment == null) {
            main.setSeniorFragment = new SetSeniorFragment();
        }
        main.switchContent(main.mContent, main.setSeniorFragment);
    }

    @Override
    public void initTitle() {
    }

    @Override
    public void onReflushData() {

    }
}
