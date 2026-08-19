package com.sansen.yeweiyi.fragment;

import android.app.AlertDialog;
import android.databinding.Observable;
import android.databinding.ObservableField;
import android.os.Build;
import android.os.Bundle;
import android.support.annotation.RequiresApi;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.Window;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.sansen.yeweiyi.R;
import com.sansen.yeweiyi.application.MyApplication;
import com.sansen.yeweiyi.base.BaseFragment;
import com.sansen.yeweiyi.bean.SerialPortEntrity;
import com.sansen.yeweiyi.fragment.SetPawFragment.SetType;
import com.sansen.yeweiyi.repository.AlarmRepository;
import com.sansen.yeweiyi.repository.AlarmRepositoryImpl;
import com.sansen.yeweiyi.repository.ShiftRepository;
import com.sansen.yeweiyi.repository.ShiftRepositoryImpl;
import com.sansen.yeweiyi.repository.StockRepository;
import com.sansen.yeweiyi.repository.StockRepositoryImpl;
import com.sansen.yeweiyi.utils.Constans;
import com.sansen.yeweiyi.utils.SharePrefUtil;
import com.sansen.yeweiyi.utils.Singleton;


/**
 * 权限管理
 */
public class JiLuFragment extends BaseFragment implements CompoundButton.OnCheckedChangeListener {

    private final ShiftRepository shiftRepository = ShiftRepositoryImpl.getInstance();
    private final StockRepository stockRepository = StockRepositoryImpl.getInstance();
    private final AlarmRepository alarmRepository = AlarmRepositoryImpl.getInstance();
    private CheckBox sw_create_alarm;
    private CheckBox sw_del_alarm;
    private CheckBox sw_create_out, sw_del_out;
    private CheckBox sw_create_stock, sw_del_stock;
    private EditText et_time_minute;
    private CheckBox sw_create_in, sw_del_in;
    private CheckBox sw_create_shift, sw_del_shift;
    private LinearLayout ly_upper_page;
    private TextView sw_clear_alarm, sw_clear_out, sw_clear_stock, sw_clear_in, sw_clear_shift;

    @Override
    protected View initView(LayoutInflater inflater) {
        return inflater.inflate(R.layout.fragment_ji_lu, null);
    }

    @Override
    protected void initData(Bundle savedInstanceState) {
        findView();
    }


    @RequiresApi(api = Build.VERSION_CODES.O)
    @Override
    protected void processClick(View v) {
//        if (v.getId()==R.id.ly_upper_page){
//            if (main.setFragment == null) {
//                main.setFragment = new SetFragment();
//            }
//            main.switchContent(main.mContent, main.setFragment);
//        }

        switch (v.getId()) {
            case R.id.ly_upper_page:
                if (main.setFragment == null) {
                    main.setFragment = new SetFragment();
                }
                main.switchContent(main.mContent, main.setFragment);
                break;
            case R.id.sw_clear_shift:

                initDialog(4);
                // builder.show();

                Log.e("TAG", "sw_clear_shift");
                break;
            case R.id.sw_clear_in:
                initDialog(0);
                // builder.show();


                Log.e("TAG", "sw_clear_in");
                break;
            case R.id.sw_clear_stock:

                initDialog(3);
                //builder.show();

                Log.e("TAG", "sw_clear_stock");
                break;
            case R.id.sw_clear_out:

                initDialog(2);
                // builder.show();

                Log.e("TAG", "sw_clear_out");
                break;
            case R.id.sw_clear_alarm:
                initDialog(1);
                //  builder.show();
                Log.e("TAG", "sw_clear_alarm");
                break;

        }

    }

    public void loading_gaoji_set(SetType setType) {

        SetPawFragment setPawFragment = new SetPawFragment(setType);
        main.switchContent(main.mContent, setPawFragment);
    }

    @Override
    public void onCheckedChanged(CompoundButton compoundButton, boolean b) {
        switch (compoundButton.getId()) {
            case R.id.sw_create_shift:
                SharePrefUtil.saveInt(MyApplication.getInstance().getApplicationContext(), Constans.IsCreatShift, b ? 1 : 0);
                Singleton.getInstance().getSetData(Constans.IsCreatShift);
                Log.e("TAG", "sw_create_shift");
                break;
            case R.id.sw_del_shift:
                SharePrefUtil.saveInt(MyApplication.getInstance().getApplicationContext(), Constans.IsDelShift, b ? 1 : 0);
                Singleton.getInstance().getSetData(Constans.IsDelShift);
                Log.e("TAG", "sw_del_shift");
                break;

            case R.id.sw_create_in:
                SharePrefUtil.saveInt(MyApplication.getInstance().getApplicationContext(), Constans.IsCreatIn, b ? 1 : 0);
                Singleton.getInstance().getSetData(Constans.IsCreatIn);
                Log.e("TAG", "sw_create_in");
                break;
            case R.id.sw_del_in:
                SharePrefUtil.saveInt(MyApplication.getInstance().getApplicationContext(), Constans.IsDelIn, b ? 1 : 0);
                Singleton.getInstance().getSetData(Constans.IsDelIn);
                Log.e("TAG", "sw_del_in");
                break;

            case R.id.sw_create_stock:
                SharePrefUtil.saveInt(MyApplication.getInstance().getApplicationContext(), Constans.IsCreatStock, b ? 1 : 0);
                Singleton.getInstance().getSetData(Constans.IsCreatStock);
                Log.e("TAG", "sw_create_stock");
                break;
            case R.id.sw_del_stock:
                SharePrefUtil.saveInt(MyApplication.getInstance().getApplicationContext(), Constans.IsDelStock, b ? 1 : 0);
                Singleton.getInstance().getSetData(Constans.IsDelStock);
                Log.e("TAG", "sw_del_stock");
                break;

            case R.id.sw_create_out:
                SharePrefUtil.saveInt(MyApplication.getInstance().getApplicationContext(), Constans.IsCreateOut, b ? 1 : 0);
                Singleton.getInstance().getSetData(Constans.IsCreateOut);
                Log.e("TAG", "sw_create_out");
                break;
            case R.id.sw_del_out:
                SharePrefUtil.saveInt(MyApplication.getInstance().getApplicationContext(), Constans.IsDelOut, b ? 1 : 0);
                Singleton.getInstance().getSetData(Constans.IsDelOut);
                Log.e("TAG", "sw_del_out");
                break;
            case R.id.sw_create_alarm:
                SharePrefUtil.saveInt(MyApplication.getInstance().getApplicationContext(), Constans.IsCreateAlarm, b ? 1 : 0);

                Singleton.getInstance().getSetData(Constans.IsCreateAlarm);
                Log.e("TAG", "sw_create_alarm");
                break;
            case R.id.sw_del_alarm:
                SharePrefUtil.saveInt(MyApplication.getInstance().getApplicationContext(), Constans.IsDelAlarm, b ? 1 : 0);
                Singleton.getInstance().getSetData(Constans.IsDelAlarm);
                Log.e("TAG", "sw_del_alarm");
                break;


        }

    }

    private void initDialog(int position) {

        View customView = LayoutInflater.from(main).inflate(R.layout.dialog_custom_alert, null);
        Button btn_confirm = customView.findViewById(R.id.btn_confirm);
        Button btn_cancel = customView.findViewById(R.id.btn_cancel);
        AlertDialog dialog = new AlertDialog.Builder(main)
                .setView(customView)
                .create();
        btn_cancel.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View view) {
                dialog.dismiss();
            }
        });
        btn_confirm.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View view) {
                if (position == 0) {
                    stockRepository.deleteAllIn();
                } else if (position == 1) {
                    alarmRepository.deleteAll();
                } else if (position == 2) {
                    stockRepository.deleteAllOut();
                } else if (position == 3) {
                    stockRepository.deleteAllStock();
                } else if (position == 4) {
                    shiftRepository.deleteAll();
                }
                dialog.dismiss();
            }
        });

        dialog.show();

        Window window = dialog.getWindow();
        if (window != null) {
            int width = (int) (540 + 2 * getResources().getDisplayMetrics().density);   // 300dp
            int height = (int) (230*1.3  * getResources().getDisplayMetrics().density);  // 400dp
            window.setLayout(width, height);
        }
    }

    @Override
    public void findView() {
        sw_create_shift = rootView.findViewById(R.id.sw_create_shift);
        sw_del_shift = rootView.findViewById(R.id.sw_del_shift);
        sw_clear_shift = rootView.findViewById(R.id.sw_clear_shift);
        sw_create_shift.setOnCheckedChangeListener(this);
        sw_del_shift.setOnCheckedChangeListener(this);
        sw_clear_shift.setOnClickListener(this);

        sw_create_in = rootView.findViewById(R.id.sw_create_in);
        sw_del_in = rootView.findViewById(R.id.sw_del_in);
        sw_clear_in = rootView.findViewById(R.id.sw_clear_in);
        sw_create_in.setOnCheckedChangeListener(this);
        sw_del_in.setOnCheckedChangeListener(this);
        sw_clear_in.setOnClickListener(this);


        et_time_minute = rootView.findViewById(R.id.et_time_minute);
        sw_create_stock = rootView.findViewById(R.id.sw_create_stock);
        sw_del_stock = rootView.findViewById(R.id.sw_del_stock);
        sw_clear_stock = rootView.findViewById(R.id.sw_clear_stock);
        sw_del_stock.setOnCheckedChangeListener(this);
        sw_create_stock.setOnCheckedChangeListener(this);
        sw_clear_stock.setOnClickListener(this);


        sw_create_alarm = rootView.findViewById(R.id.sw_create_alarm);
        sw_del_alarm = rootView.findViewById(R.id.sw_del_alarm);
        sw_clear_alarm = rootView.findViewById(R.id.sw_clear_alarm);
        sw_del_alarm.setOnCheckedChangeListener(this);
        sw_clear_alarm.setOnClickListener(this);
        sw_create_alarm.setOnCheckedChangeListener(this);

        sw_create_out = rootView.findViewById(R.id.sw_create_out);
        sw_del_out = rootView.findViewById(R.id.sw_del_out);
        sw_clear_out = rootView.findViewById(R.id.sw_clear_out);
        sw_create_out.setOnCheckedChangeListener(this);
        sw_del_out.setOnCheckedChangeListener(this);
        sw_clear_out.setOnClickListener(this);


        ly_upper_page = rootView.findViewById(R.id.ly_upper_page);
        ly_upper_page.setOnClickListener(this);
        initData();
        et_time_minute.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence charSequence, int i, int i1, int i2) {

            }

            @Override
            public void onTextChanged(CharSequence charSequence, int i, int i1, int i2) {

            }

            @Override
            public void afterTextChanged(Editable editable) {
                String et = editable.toString().trim();
                if (!et.isEmpty()) {
                    time = Integer.parseInt(editable.toString().trim());
                    if (time > 0) {

                        SharePrefUtil.saveInt(MyApplication.getInstance().getApplicationContext(), Constans.StockTime, time);
                        Singleton.getInstance().getSetData(Constans.StockTime);
                    }
                }


            }
        });
        View headerView = rootView.findViewById(R.id.title_bar);
        // 再从该 View 中 findViewById 获取内部的 TextView
        tvTitle = headerView.findViewById(R.id.tv_title);
        tv_time = headerView.findViewById(R.id.main_time);
        tvTitle.setText("记录设置");
        SerialPortEntrity.time.addOnPropertyChangedCallback(callback);


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
    TextView tvTitle, tv_time;
    private int time = 0;

    private void initData() {
        sw_create_shift.setChecked(SharePrefUtil.getInt(MyApplication.getInstance().getApplicationContext(), Constans.IsCreatShift, 0) == 0 ? false : true);
        sw_del_shift.setChecked(SharePrefUtil.getInt(MyApplication.getInstance().getApplicationContext(), Constans.IsDelShift, 0) == 0 ? false : true);
        sw_create_in.setChecked(SharePrefUtil.getInt(MyApplication.getInstance().getApplicationContext(), Constans.IsCreatIn, 0) == 0 ? false : true);
        sw_del_in.setChecked(SharePrefUtil.getInt(MyApplication.getInstance().getApplicationContext(), Constans.IsDelIn, 0) == 0 ? false : true);
        sw_del_stock.setChecked(SharePrefUtil.getInt(MyApplication.getInstance().getApplicationContext(), Constans.IsDelStock, 0) == 0 ? false : true);
        sw_create_stock.setChecked(SharePrefUtil.getInt(MyApplication.getInstance().getApplicationContext(), Constans.IsCreatStock, 0) == 0 ? false : true);
        sw_create_alarm.setChecked(SharePrefUtil.getInt(MyApplication.getInstance().getApplicationContext(), Constans.IsCreateAlarm, 0) == 0 ? false : true);
        sw_del_alarm.setChecked(SharePrefUtil.getInt(MyApplication.getInstance().getApplicationContext(), Constans.IsDelAlarm, 0) == 0 ? false : true);
        sw_create_out.setChecked(SharePrefUtil.getInt(MyApplication.getInstance().getApplicationContext(), Constans.IsCreateOut, 0) == 0 ? false : true);
        sw_del_out.setChecked(SharePrefUtil.getInt(MyApplication.getInstance().getApplicationContext(), Constans.IsDelOut, 0) == 0 ? false : true);
        et_time_minute.setText(SharePrefUtil.getInt(MyApplication.getInstance().getApplicationContext(), Constans.StockTime, 0) + "");

    }

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

    @Override
    public void onReflushData() {

        initData();
    }


}
