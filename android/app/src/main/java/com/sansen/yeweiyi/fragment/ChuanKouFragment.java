package com.sansen.yeweiyi.fragment;

import android.content.Intent;
import android.databinding.Observable;
import android.databinding.ObservableField;
import android.os.Build;
import android.os.Bundle;
import android.support.annotation.RequiresApi;
import android.view.LayoutInflater;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.RadioGroup;
import android.widget.TextView;

import com.sansen.yeweiyi.R;
import com.sansen.yeweiyi.activity.ActivityDialog;
import com.sansen.yeweiyi.application.MyApplication;
import com.sansen.yeweiyi.base.BaseFragment;
import com.sansen.yeweiyi.bean.SerialPortEntrity;
import com.sansen.yeweiyi.fragment.SetPawFragment.SetType;
import com.sansen.yeweiyi.manager.PumpIoManager;
import com.sansen.yeweiyi.logic.probe.Serial4Mode;
import com.sansen.yeweiyi.utils.SystemUtils;
import com.sansen.yeweiyi.utils.Constans;
import com.sansen.yeweiyi.utils.SharePrefUtil;


/**
 * 权限管理
 */
public class ChuanKouFragment extends BaseFragment  {

    private RadioGroup rg_serial2, rg_serial4, rg_serial4_proxy;
    private CheckBox cb_pump_link;
    private LinearLayout ly_upper_page;
    private TextView tv_pump_link_tank;
    /** ActivityDialog 罐号选择；返回值 = 下标 + 100（见 ActivityDialog.onItemClick） */
    private static final int REQUEST_PUMP_TANK = 0x31;
    /** 未选罐时打开开关会先弹罐号选择，选中后再打开，取消则开关退回关闭 */
    private boolean enableAfterPick;


    @Override
    protected View initView(LayoutInflater inflater) {
        return inflater.inflate(R.layout.fragment_chuan_kou, null);
    }

    @Override
    protected void initData(Bundle savedInstanceState) {
        findView();
    }


    @RequiresApi(api = Build.VERSION_CODES.O)
    @Override
    protected void processClick(View v) {
        if (v.getId() == R.id.tv_pump_link_tank) {
            openTankPicker(false);
            return;
        }
        if (v.getId()==R.id.ly_upper_page){
            if (main.setSeniorFragment == null) {
                main.setSeniorFragment = new SetSeniorFragment();
            }
            main.switchContent(main.mContent, main.setSeniorFragment);
        }

    }

    public void loading_gaoji_set(SetType setType) {

        SetPawFragment setPawFragment = new SetPawFragment(setType);
        main.switchContent(main.mContent, setPawFragment);
    }

    @Override
    public void findView() {
        rg_serial2 = rootView.findViewById(R.id.rg_serial2);
        rg_serial4 = rootView.findViewById(R.id.rg_serial4);
        rg_serial4_proxy = rootView.findViewById(R.id.rg_serial4_proxy);
        ly_upper_page=rootView.findViewById(R.id.ly_upper_page);
        ly_upper_page.setOnClickListener(this);
        showSerialSettings();
        rg_serial2.setOnCheckedChangeListener((group, checkedId) -> {
            if (restoringSerialSettings) return;
            int type = checkedId == R.id.rb_serial2_zhongcheng ? 0
                    : checkedId == R.id.rb_serial2_modbus ? 1 : 2;
            saveSerialSetting(Constans.ChuanKou2, type);
        });
        RadioGroup.OnCheckedChangeListener serial4Listener = (group, checkedId) -> {
            if (restoringSerialSettings || checkedId == -1) return;
            int mode = checkedId == R.id.rb_serial4_oil ? Serial4Mode.OVERFLOW
                    : checkedId == R.id.rb_serial4_modbus ? Serial4Mode.RELAY_MODBUS
                    : checkedId == R.id.rb_serial4_proxy_zhongcheng ? Serial4Mode.PROXY_ZHONGCHENG
                    : Serial4Mode.PROXY_MODBUS;
            saveSerialSetting(Constans.ChuanKou4, mode);
        };
        rg_serial4.setOnCheckedChangeListener(serial4Listener);
        rg_serial4_proxy.setOnCheckedChangeListener(serial4Listener);
        initPumpLink();

        View headerView = rootView.findViewById(R.id.title_bar);
        // 再从该 View 中 findViewById 获取内部的 TextView
        tvTitle = headerView.findViewById(R.id.tv_title);
        tv_time=headerView.findViewById(R.id.main_time);
        tvTitle.setText(R.string.serial_port_settings);
        SerialPortEntrity.time.addOnPropertyChangedCallback(callback);
    }
    private boolean restoringSerialSettings;

    private void showSerialSettings() {
        restoringSerialSettings = true;
        int type = SharePrefUtil.getInt(main, Constans.ChuanKou2, 0);
        int mode = SharePrefUtil.getInt(main, Constans.ChuanKou4, 0);
        rg_serial2.check(type == 0 ? R.id.rb_serial2_zhongcheng
                : type == 1 ? R.id.rb_serial2_modbus : R.id.rb_serial2_mA);
        rg_serial4.clearCheck();
        rg_serial4_proxy.clearCheck();
        switch (mode) {
            case Serial4Mode.OVERFLOW: rg_serial4.check(R.id.rb_serial4_oil); break;
            case Serial4Mode.RELAY_MODBUS: rg_serial4.check(R.id.rb_serial4_modbus); break;
            case Serial4Mode.PROXY_ZHONGCHENG: rg_serial4_proxy.check(R.id.rb_serial4_proxy_zhongcheng); break;
            case Serial4Mode.PROXY_MODBUS: rg_serial4_proxy.check(R.id.rb_serial4_proxy_modbus); break;
            default: rg_serial4.clearCheck();
        }
        restoringSerialSettings = false;
    }

    private void saveSerialSetting(String key, int value) {
        int type = SharePrefUtil.getInt(main, Constans.ChuanKou2, 0);
        int mode = SharePrefUtil.getInt(main, Constans.ChuanKou4, 0);
        if (Constans.ChuanKou2.equals(key)) type = value;
        else mode = value;
        if (!Serial4Mode.matches(mode, type)) {
            showSerialSettings();
            return;
        }
        if (SharePrefUtil.getInt(main, key, 0) != value) {
            boolean saved = SharePrefUtil.saveIntsChecked(main, java.util.Collections.singletonMap(key, value));
            SystemUtils.showRsToast(saved ? R.string.data_save_success : R.string.data_save_error);
        }
        showSerialSettings();
    }

    Observable.OnPropertyChangedCallback callback = new Observable.OnPropertyChangedCallback() {
        @Override
        public void onPropertyChanged(Observable sender, int propertyId) {
            // 如果需要区分是哪个字段，可通过 propertyId 判断
            // 直接获取当前值
            String newValue = ((ObservableField<String>) sender).get();
            // Log.d("监听", "新值：" + newValue);
            tv_time.setText(newValue);
        }
    };
    TextView tvTitle,tv_time;

    @Override
    public void initTitle() {

        if (main != null) {
            OnClickListener backSetListener = new OnClickListener() {

                @Override
                public void onClick(View v) {

                    if (main.setSeniorFragment == null) {
                        main.setSeniorFragment = new SetSeniorFragment();
                    }
                    main.switchContent(main.mContent, main.setSeniorFragment);

                }
            };

        }
    }

    /** 单罐开关量输出（IO2 联动）：先回显再挂监听，避免回显触发一次保存/复位。 */
    private void initPumpLink() {
        cb_pump_link = rootView.findViewById(R.id.cb_pump_link);
        tv_pump_link_tank = rootView.findViewById(R.id.tv_pump_link_tank);
        tv_pump_link_tank.setOnClickListener(this);
        showPumpTank(PumpIoManager.getTank());
        cb_pump_link.setChecked(PumpIoManager.isEnabled());
        cb_pump_link.setOnCheckedChangeListener((button, on) -> {
            if (on == PumpIoManager.isEnabled()) return;
            if (!PumpIoManager.setEnabled(on)) {
                openTankPicker(true);
            }
        });
    }

    private void openTankPicker(boolean enableAfter) {
        enableAfterPick = enableAfter;
        Intent intent = new Intent(main, ActivityDialog.class);
        intent.putExtra("value", 0);
        startActivityForResult(intent, REQUEST_PUMP_TANK);
    }

    private void showPumpTank(int tank) {
        String[] names = getResources().getStringArray(R.array.oilcanArray);
        if (tank <= 0 || tank > names.length) {
            tv_pump_link_tank.setText(tank <= 0 ? getString(R.string.tank_no) : String.valueOf(tank));
        } else {
            tv_pump_link_tank.setText(names[tank - 1]);
        }
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_PUMP_TANK) return;
        boolean enableAfter = enableAfterPick;
        enableAfterPick = false;
        // 点弹窗外面关闭时 resultCode 为 0
        if (resultCode >= 100) {
            int tank = resultCode - 100 + 1;
            if (tank != PumpIoManager.getTank()) {
                PumpIoManager.setTank(tank);
            }
            showPumpTank(tank);
            if (enableAfter) {
                PumpIoManager.setEnabled(true);
            }
        }
        cb_pump_link.setChecked(PumpIoManager.isEnabled());
    }

    @Override
    public void onReflushData() {


    }
}
