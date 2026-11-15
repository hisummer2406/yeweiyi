package com.sansen.yeweiyi.fragment;

import android.content.Intent;
import android.databinding.Observable;
import android.databinding.ObservableField;
import android.os.Bundle;
import android.text.InputFilter;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.sansen.yeweiyi.R;
import com.sansen.yeweiyi.application.MyApplication;
import com.sansen.yeweiyi.base.BaseFragment;
import com.sansen.yeweiyi.bean.SerialPortEntrity;
import com.sansen.yeweiyi.db.OilTest;
import com.sansen.yeweiyi.logic.Const;
import com.sansen.yeweiyi.manager.OilcanDataManager;
import com.sansen.yeweiyi.utils.Constans;
import com.sansen.yeweiyi.utils.DateUtils;
import com.sansen.yeweiyi.utils.DecimalDigitsInputFilter;
import com.sansen.yeweiyi.utils.KeyboardAdjustHelper;
import com.sansen.yeweiyi.utils.SharePrefUtil;
import com.sansen.yeweiyi.utils.Singleton;

import java.lang.reflect.Method;
import java.util.List;

import static com.lzy.okgo.utils.HttpUtils.runOnUiThread;


public class OilTestFragment extends BaseFragment {
    private EditText et_yewei01;
   // private EditText et_shuiwei01;
    private EditText et_weiduwei01;

    private EditText et_yewei02;
   // private EditText et_shuiwei02;
    private EditText et_weiduwei02;

    private EditText et_yewei03;
   // private EditText et_shuiwei03;
    private EditText et_weiduwei03;

    private EditText et_yewei04;
   // private EditText et_shuiwei04;
    private EditText et_weiduwei04;

    private EditText et_yewei05;
   // private EditText et_shuiwei05;
    private EditText et_weiduwei05;

    private EditText et_yewei06;
   // private EditText et_shuiwei06;
    private EditText et_weiduwei06;

    private EditText et_yewei07;
   // private EditText et_shuiwei07;
    private EditText et_weiduwei07;

    private EditText et_yewei08;
   // private EditText et_shuiwei08;
    private EditText et_weiduwei08;

    private EditText et_yewei09;
   // private EditText et_shuiwei09;
    private EditText et_weiduwei09;


    private EditText et_yewei10;
   // private EditText et_shuiwei10;
    private EditText et_weiduwei10;


    private EditText et_yewei11;
   // private EditText et_shuiwei11;
    private EditText et_weiduwei11;

    private EditText et_yewei12;
   // private EditText et_shuiwei12;
    private EditText et_weiduwei12;
    private CheckBox cb_test_status;


    private List<OilTest> oilTests;
    private OilTest oilTest;
    private LinearLayout ly_baoCunData, ly_upper_page;

    @Override
    protected View initView(LayoutInflater inflater) {
        return inflater.inflate(R.layout.fragment_oiltest, null);
    }


    @Override
    protected void initData(Bundle savedInstanceState) {
        findView();

        oilTests = dbManager.findAllObject(OilTest.class);
        for (int i=0;i<oilTests.size();i++){
            Log.e("TAG",oilTests.get(i).getHeight()+"-------oilTests");
        }
//        initOilCanSetData();
    }

    public void findView() {
        ly_upper_page = rootView.findViewById(R.id.ly_upper_page);
        ly_baoCunData = rootView.findViewById(R.id.ly_baoCunData);
        ly_baoCunData.setOnClickListener(this);
        ly_upper_page.setOnClickListener(this);
        KeyboardAdjustHelper.hideActionBarWhileKeyboardVisible(rootView, ly_baoCunData, ly_upper_page);
        //  et_shuiwei01 = rootView.findViewById(R.id.//  et_shuiwei01);
        et_yewei01 = rootView.findViewById(R.id.et_yewei01);
        et_weiduwei01 = rootView.findViewById(R.id.et_weiduwei01);

        //  et_shuiwei02 = rootView.findViewById(R.id.//  et_shuiwei02);
        et_yewei02 = rootView.findViewById(R.id.et_yewei02);
        et_weiduwei02 = rootView.findViewById(R.id.et_weiduwei02);

        //  et_shuiwei03 = rootView.findViewById(R.id.//  et_shuiwei03);
        et_yewei03 = rootView.findViewById(R.id.et_yewei03);
        et_weiduwei03 = rootView.findViewById(R.id.et_weiduwei03);

        //  et_shuiwei04 = rootView.findViewById(R.id.//  et_shuiwei04);
        et_yewei04 = rootView.findViewById(R.id.et_yewei04);
        et_weiduwei04 = rootView.findViewById(R.id.et_weiduwei04);

        //  et_shuiwei05 = rootView.findViewById(R.id.//  et_shuiwei05);
        et_yewei05 = rootView.findViewById(R.id.et_yewei05);
        et_weiduwei05 = rootView.findViewById(R.id.et_weiduwei05);

        //  et_shuiwei06 = rootView.findViewById(R.id.//  et_shuiwei06);
        et_yewei06 = rootView.findViewById(R.id.et_yewei06);
        et_weiduwei06 = rootView.findViewById(R.id.et_weiduwei06);

        //  et_shuiwei07 = rootView.findViewById(R.id.//  et_shuiwei07);
        et_yewei07 = rootView.findViewById(R.id.et_yewei07);
        et_weiduwei07 = rootView.findViewById(R.id.et_weiduwei07);

        //  et_shuiwei08 = rootView.findViewById(R.id.//  et_shuiwei08);
        et_yewei08 = rootView.findViewById(R.id.et_yewei08);
        et_weiduwei08 = rootView.findViewById(R.id.et_weiduwei08);

        //  et_shuiwei09 = rootView.findViewById(R.id.//  et_shuiwei09);
        et_yewei09 = rootView.findViewById(R.id.et_yewei09);
        et_weiduwei09 = rootView.findViewById(R.id.et_weiduwei09);

        //  et_shuiwei10 = rootView.findViewById(R.id.//  et_shuiwei01);
        et_yewei10 = rootView.findViewById(R.id.et_yewei10);
        et_weiduwei10 = rootView.findViewById(R.id.et_weiduwei10);

        //  et_shuiwei11 = rootView.findViewById(R.id.//  et_shuiwei01);
        et_yewei11 = rootView.findViewById(R.id.et_yewei11);
        et_weiduwei11 = rootView.findViewById(R.id.et_weiduwei11);

        //  et_shuiwei12 = rootView.findViewById(R.id.et_shuiwei01);
        et_yewei12 = rootView.findViewById(R.id.et_yewei12);
        et_weiduwei12 = rootView.findViewById(R.id.et_weiduwei12);

        // 液位、温度位都支持小数（保存时都按 double 处理），统一只限制到 1 位
        InputFilter[] oneDecimalFilter = new InputFilter[]{new DecimalDigitsInputFilter(1)};
        EditText[] correctionInputs = {
                et_yewei01, et_weiduwei01, et_yewei02, et_weiduwei02,
                et_yewei03, et_weiduwei03, et_yewei04, et_weiduwei04,
                et_yewei05, et_weiduwei05, et_yewei06, et_weiduwei06,
                et_yewei07, et_weiduwei07, et_yewei08, et_weiduwei08,
                et_yewei09, et_weiduwei09, et_yewei10, et_weiduwei10,
                et_yewei11, et_weiduwei11, et_yewei12, et_weiduwei12,
        };
        for (EditText editText : correctionInputs) {
            editText.setFilters(oneDecimalFilter);
        }

        cb_test_status=rootView.findViewById(R.id.cb_test_status);
        initData();
        cb_test_status.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton compoundButton, boolean b) {
                SharePrefUtil.saveInt(MyApplication.getInstance(), Constans.isTestStatus,b ? 1:0);
                // 两个切换方向都清掉旧采样；退出演示后等下一次真实采集再恢复双显。
                com.sansen.yeweiyi.manager.ProbeProxyManager.setSourceAvailable(false);
                Singleton.getInstance().getSetData(Constans.isTestStatus);
            }
        });
        View headerView = rootView.findViewById(R.id.title_bar);
        // 再从该 View 中 findViewById 获取内部的 TextView
        tvTitle = headerView.findViewById(R.id.tv_title);
        tv_time=headerView.findViewById(R.id.main_time);
        tvTitle.setText(R.string.demo_mode);
        SerialPortEntrity.time.addOnPropertyChangedCallback(callback);
        for (int i = 1; i < 13; i++) {

            readDB(i);
        }
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
    private void initData(){
        cb_test_status.setChecked(SharePrefUtil.getInt(MyApplication.getInstance(), Constans.isTestStatus,0)==1 ? true:false);
    }
    private void saveData(EditText vText, String methedName, Class clazz, int oilId) {

        String cur = vText.getText().toString();
        Log.e("TAG探针--: ", cur+" ----"+oilId);
        oilTest = (OilTest) dbManager.findObjectByID(OilTest.class,
                oilId);
        if (oilTest == null) {
            oilTest = new OilTest();
        }
        Class oilCanClazz = oilTest.getClass();
        Method method = null;
        try {
            method = oilCanClazz.getDeclaredMethod("set"
                    + methedName, clazz);
        } catch (NoSuchMethodException e) {
            Log.e("OilTestFragment", "saveData 执行失败", e);
        }
        try {

            if (clazz.equals(int.class)) {

                method.invoke(oilTest, Integer.parseInt(cur));
                if (method.getName().equals("setHeight")) {
                    if (Integer.parseInt(cur) > 0)
                        oilTest.setEnable(1);
                    else
                        oilTest.setEnable(0);
                    OilcanDataManager.getInstance(MyApplication.getInstance().getApplicationContext()).refresh();
                }
                //Log.i("TAG", method.getName());
            } else if (clazz.equals(double.class)) {
                method.invoke(oilTest, Double.parseDouble(cur));
            }
            oilTest.setGmt_modified(DateUtils.getYYYMMddHHmmss());
            dbManager.UpdateObject(oilTest);
            //  vText.setHint(cur);
            //  vText.setText("");
            Const.isUpdate = true;
            //  vText.setTag(R.id.tag_first, vText.getText().toString());
        } catch (Exception e) {
            Log.e("OilTestFragment", "saveData 执行失败", e);
        }
    }

    public void initTitle() {
        if (main != null) {

            OnClickListener backSetListener = new OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (main.setFragment == null) {
                        main.setFragment = new SetFragment();
                    }
                    main.switchContent(main.mContent, main.setFragment);
                }
            };
        }

    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        // TODO Auto-generated method stub
        super.onActivityResult(requestCode, resultCode, data);

    }

    /**
     * 设置所有控件属性
     */
    private void initOilCanSetData() {



    }


    protected void readDB(int postion) {
        runOnUiThread(() -> {
            // 主线程操作
            OilTest oilTest2 = (OilTest) dbManager.findObjectByID(OilTest.class, postion);
            if (oilTest2 == null) {
                oilTest2 = new OilTest();
                oilTest2.setEnable(postion);
                oilTest2.setTemp(0);
                oilTest2.setHeight(0);
                dbManager.saveObject(oilTest2);
            }
            switch (postion) {
                case 1:
                    Log.e("TAG1",oilTest2.getTemp()+"  "+oilTest2.getHeight()+"   "+oilTest2.getTemp());
                    et_weiduwei01.setText(oilTest2.getTemp() + "");
                    et_yewei01.setText(oilTest2.getHeight() + "");

                    break;
                case 2:
                    et_weiduwei02.setText(oilTest2.getTemp() + "");
                    et_yewei02.setText(oilTest2.getHeight() + "");


                    break;
                case 3:
                    et_weiduwei03.setText(oilTest2.getTemp() + "");
                    et_yewei03.setText(oilTest2.getHeight() + "");

                    break;
                case 4:
                    et_weiduwei04.setText(oilTest2.getTemp() + "");
                    et_yewei04.setText(oilTest2.getHeight() + "");

                    break;
                case 5:
                    et_weiduwei05.setText(oilTest2.getTemp() + "");
                    et_yewei05.setText(oilTest2.getHeight() + "");

                    break;
                case 6:
                    et_weiduwei06.setText(oilTest2.getTemp() + "");
                    et_yewei06.setText(oilTest2.getHeight() + "");

                    break;
                case 7:
                    et_weiduwei07.setText(oilTest2.getTemp() + "");
                    et_yewei07.setText(oilTest2.getHeight() + "");

                    break;
                case 8:
                    et_weiduwei08.setText(oilTest2.getTemp() + "");
                    et_yewei08.setText(oilTest2.getHeight() + "");

                    break;
                case 9:
                    et_weiduwei09.setText(oilTest2.getTemp() + "");
                    et_yewei09.setText(oilTest2.getHeight() + "");

                    break;
                case 10:
                    et_weiduwei10.setText(oilTest2.getTemp() + "");
                    et_yewei10.setText(oilTest2.getHeight() + "");

                    break;
                case 11:
                    et_weiduwei11.setText(oilTest2.getTemp() + "");
                    et_yewei11.setText(oilTest2.getHeight() + "");

                    break;
                case 12:
                    et_weiduwei12.setText(oilTest2.getTemp() + "");
                    et_yewei12.setText(oilTest2.getHeight() + "");

                    break;
            }
        });

    }

    @Override
    protected void processClick(View v) {
        switch (v.getId()) {
            case R.id.ly_baoCunData:
                //  saveData(et_shuiwei01, "Height", int.class, 1);
                saveData(et_yewei01, "Height", double.class, 1);
                saveData(et_weiduwei01, "Temp", double.class, 1);

                //  saveData(et_shuiwei02, "Height", int.class, 2);
                saveData(et_yewei02, "Height", double.class, 2);
                saveData(et_weiduwei02, "Temp", double.class, 2);

                //  saveData(et_shuiwei03, "Height", int.class, 3);
                saveData(et_yewei03, "Height", double.class, 3);
                saveData(et_weiduwei03, "Temp", double.class, 3);

                //  saveData(et_shuiwei04, "Height", int.class, 4);
                saveData(et_yewei04, "Height", double.class, 4);
                saveData(et_weiduwei04, "Temp", double.class, 4);

                //  saveData(et_shuiwei05, "Height", int.class, 5);
                saveData(et_yewei05, "Height", double.class, 5);
                saveData(et_weiduwei05, "Temp", double.class, 5);

                //  saveData(et_shuiwei06, "Height", int.class, 6);
                saveData(et_yewei06, "Height", double.class, 6);
                saveData(et_weiduwei06, "Temp", double.class, 6);

                //  saveData(et_shuiwei07, "Height", int.class, 7);
                saveData(et_yewei07, "Height", double.class, 7);
                saveData(et_weiduwei07, "Temp", double.class, 7);

                //  saveData(et_shuiwei08, "Height", int.class, 8);
                saveData(et_yewei08, "Height", double.class, 8);
                saveData(et_weiduwei08, "Temp", double.class, 8);

                //  saveData(et_shuiwei09, "Height", int.class, 9);
                saveData(et_yewei09, "Height", double.class, 9);
                saveData(et_weiduwei09, "Temp", double.class, 9);

                //  saveData(et_shuiwei10, "Height", int.class, 10);
                saveData(et_yewei10, "Height", double.class, 10);
                saveData(et_weiduwei10, "Temp", double.class, 10);

                //  saveData(et_shuiwei11, "Height", int.class, 11);
                saveData(et_yewei11, "Height", double.class, 11);
                saveData(et_weiduwei11, "Temp", double.class, 11);

                //  saveData(et_shuiwei12, "Height", int.class, 12);
                saveData(et_yewei12, "Height", double.class, 12);
                saveData(et_weiduwei12, "Temp", double.class, 12);

                showWindDialog(1000);
                break;

            case R.id.ly_upper_page:
                if (main.setFragment == null) {
                    main.setSeniorFragment = new SetSeniorFragment();
                }
                main.switchContent(main.mContent, main.setSeniorFragment);
                break;
        }
    }


    @Override
    public void onHiddenChanged(boolean hidden) {
        // TODO Auto-generated method stub
        super.onHiddenChanged(hidden);

        if (hidden & Const.isUpdate == true) {
            updateData();
            Const.isUpdate = false;
        } else {
            onReflushData();
        }
    }


    @Override
    public void onReflushData() {
        for (int i = 1; i < 13; i++) {
            readDB(i);
        }
        //  readDB(curPos);
        initData();
    }


}
