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
import android.view.View.OnFocusChangeListener;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;

import com.sansen.yeweiyi.R;
import com.sansen.yeweiyi.activity.ActivityDialog;
import com.sansen.yeweiyi.activity.MainActivity;
import com.sansen.yeweiyi.application.MyApplication;
import com.sansen.yeweiyi.base.BaseFragment;
import com.sansen.yeweiyi.bean.SerialPortEntrity;
import com.sansen.yeweiyi.db.OilCanSet;
import com.sansen.yeweiyi.logic.Const;
import com.sansen.yeweiyi.manager.OilcanDataManager;
import com.sansen.yeweiyi.repository.OilTankRepository;
import com.sansen.yeweiyi.repository.OilTankRepositoryImpl;
import com.sansen.yeweiyi.utils.DataUtils;
import com.sansen.yeweiyi.utils.DateUtils;
import com.sansen.yeweiyi.utils.DecimalDigitsInputFilter;
import com.sansen.yeweiyi.utils.DialogUtil2;
import com.sansen.yeweiyi.utils.KeyboardAdjustHelper;
import com.sansen.yeweiyi.utils.Singleton;

import java.lang.reflect.Method;
import java.util.List;


public class OilCanSetFragment extends BaseFragment {
    private final OilTankRepository oilTankRepository = OilTankRepositoryImpl.getInstance();
    private int dialogSelectType = -1;
    private TextView item_tv_oilid;
    private EditText item_et_oilheight;
    private EditText item_et_oilhheightalarm;
    private EditText item_et_oilllowalarm;
    private EditText item_et_maxvolum;
    private EditText item_et_oilheightalarm;
    private EditText item_et_oillowalarm;
    private EditText item_et_waterheightalarm;
    private EditText item_et_tempheightalarm;
    private EditText item_et_templowalarm;
    private EditText item_et_oillevelcorrection;
    private EditText item_et_waterlevlecorrection;
    private EditText item_et_tempercorrection;
    private EditText item_et_oildensity;
    private EditText item_et_densityvar;
    private TextView item_tv_oiltype;
    //    private EditText mitem_et_oilHaight;
//    private TextView mitem_tv_oilHaight;
    private Button item_but_color;
    private List<OilCanSet> oilCanSets;
    private OilCanSet oilCanSet;
    private LinearLayout ly_baoCunData, ly_copyData, ly_upper_page;

    private int curPos = 0;
    private LinearLayout oilcanset_content;
    private String TAG = "OilCanSetFragment";
    // 去/回颜色选择页期间置位，避免 onHiddenChanged 的 onReflushData() 用旧库值覆盖未保存的输入框
    private boolean skipReflushOnColorReturn = false;

    //做一个隐藏dialog,主要功能是用于设置隐藏探针高度与设置默认升数，例：2.6的探针，设置隐藏0.6则
    //从0.6米之后才开始显示正常油量；如果设置默认高度则低于0.6显示默认升数。
    private TextView item_tv_oilheight;
    private EditText item_tv_tanzhenID;
    private int number = 0;

    @Override
    protected View initView(LayoutInflater inflater) {
        return inflater.inflate(R.layout.fragment_oilcanset, null);
    }


    @Override
    protected void initData(Bundle savedInstanceState) {
        findView();
        setTvPos(curPos);

        oilCanSets = oilTankRepository.findAllOilCanSet();
        oilCanSet = oilCanSets.get(curPos);
        item_but_color.setBackgroundColor(oilCanSet.getOilColor());
        item_but_color.setOnClickListener(this);
        initOilCanSetData();
        KeyboardAdjustHelper.applyPanWhileVisible(this);
    }

    private TextView item_tv_chuanGanQiLeng;//传感器长度
    private TextView item_et_chuanGanQiLeng;//传感器长度
    private Spinner item_sp_guanType;//储罐类型

    public void findView() {
//        mitem_tv_oilHaight = rootView.findViewById(R.id.item_tv_oilHaight);
//        mitem_et_oilHaight = rootView.findViewById(R.id.item_et_oilHaight);
        item_sp_guanType = rootView.findViewById(R.id.item_sp_guanType);
        oilcanset_content = (LinearLayout) rootView.findViewById(R.id.oilcanset_content);
        item_tv_oilid = (TextView) rootView.findViewById(R.id.item_tv_oilid);
        item_tv_tanzhenID = (EditText)rootView.findViewById(R.id.item_tv_tanzhenID);
        item_et_oilheight = (EditText) rootView.findViewById(R.id.item_et_oilheight);
        item_et_maxvolum = (EditText) rootView.findViewById(R.id.item_et_maxvolum);
        item_et_chuanGanQiLeng = rootView.findViewById(R.id.item_et_chuanGanQiLeng);
        item_et_oilheightalarm = (EditText) rootView.findViewById(R.id.item_et_oilheightalarm);
        item_et_oilhheightalarm = (EditText) rootView.findViewById(R.id.item_et_oilhheightalarm);
        item_et_oilllowalarm = (EditText) rootView.findViewById(R.id.item_et_oilllowalarm);
        item_et_oillowalarm = (EditText) rootView.findViewById(R.id.item_et_oillowalarm);
        item_et_waterheightalarm = (EditText) rootView.findViewById(R.id.item_et_waterheightalarm);
        item_et_tempheightalarm = (EditText) rootView.findViewById(R.id.item_et_tempheightalarm);
        item_et_templowalarm = (EditText) rootView.findViewById(R.id.item_et_templowalarm);
        item_et_oillevelcorrection = (EditText) rootView.findViewById(R.id.item_et_oillevelcorrection);
        item_et_waterlevlecorrection = (EditText) rootView.findViewById(R.id.item_et_waterlevlecorrection);
        item_et_densityvar = (EditText) rootView.findViewById(R.id.item_et_densityvar);
        item_et_tempercorrection = (EditText) rootView.findViewById(R.id.item_et_tempercorrection);
        item_et_oildensity = (EditText) rootView.findViewById(R.id.item_et_oildensity);
        // 介质密度需要精确到小数点后 3 位，密度差维持 1 位小数
        InputFilter[] oneDecimalFilter = new InputFilter[]{new DecimalDigitsInputFilter(1)};
        item_et_oildensity.setFilters(new InputFilter[]{new DecimalDigitsInputFilter(3)});
        item_et_densityvar.setFilters(oneDecimalFilter);
        item_tv_oiltype = rootView.findViewById(R.id.item_sp_oiltype);
        item_but_color = (Button) rootView.findViewById(R.id.item_but_color);
        ly_baoCunData = rootView.findViewById(R.id.ly_baoCunData);
        ly_copyData = rootView.findViewById(R.id.ly_copyData);
        ly_upper_page = rootView.findViewById(R.id.ly_upper_page);
        item_tv_oiltype.setOnClickListener(this);
        item_tv_oilid.setOnClickListener(this);
        // item_tv_tanzhenID.setOnClickListener(this);
        oilcanset_content.setOnClickListener(this);
        ly_baoCunData.setOnClickListener(this);
        ly_copyData.setOnClickListener(this);
        ly_upper_page.setOnClickListener(this);
        KeyboardAdjustHelper.hideActionBarWhileKeyboardVisible(rootView, ly_baoCunData, ly_copyData, ly_upper_page);
//        mitem_tv_oilHaight.setVisibility(View.INVISIBLE);
//        mitem_et_oilHaight.setVisibility(View.INVISIBLE);//setOilHeight
        item_tv_oilheight = rootView.findViewById(R.id.item_tv_oilheight);
        item_tv_oilheight.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                number++;
                if (number % 9 == 0) {
                    DialogUtil2 dialogUtil2 = new DialogUtil2(ct, getActivity().getWindowManager());
                    dialogUtil2.saveTest(item_tv_oilid.getText().toString());
                    MainActivity.updateTestOil();
                }
            }
        });
        // 储罐类型不在选中时落库，随"保存数据"一起保存：首次布局和 setSelection() 也会回调
        // onItemSelected，在那里落库会让只是打开页面就置 Const.isUpdate，离开时触发 refresh()。
    }

    public void initTitle() {
        if (main != null) {
//            main.iv_right.setImageResource(R.drawable.laststep);
//            main.tv_left.setText(SystemUtils.getRsStr(R.string.back_page));
//            main.iv_right.setVisibility(View.VISIBLE);
//            main.tv_left.setVisibility(View.VISIBLE);
            OnClickListener backSetListener = new OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (main.setFragment == null) {
                        main.setFragment = new SetFragment();
                    }
                    main.switchContent(main.mContent, main.setFragment);
                }
            };
//            main.tv_left.setOnClickListener(backSetListener);
//            main.iv_right.setOnClickListener(backSetListener);
        }

    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        // TODO Auto-generated method stub
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != 0) {
            resultCode -= 100;
            if (dialogSelectType == 0) {
                readDB(resultCode);
                curPos = resultCode;
                setTvPos(curPos);

            } else if (dialogSelectType == 1) {
                oilCanSet.setOilType(resultCode);
                //Log.i("onActivityResult", "onActivityResult: 保存油品类型"+resultCode);
                oilCanSet.setGmt_modified(DateUtils.getYYYMMddHHmmss());
                oilTankRepository.updateOilCanSet(oilCanSet);
                item_tv_oiltype.setText(DataUtils.getOilType()[resultCode]);
                Const.isUpdate = true;
            } else if (dialogSelectType == 8) {

                oilCanSet.setPk_oilCanId(resultCode + 1);
                //  initOilCanSetData();
                // item_tv_tanzhenID.setText("" + (resultCode + 1));
                //Log.i("onActivityResult", "onActivityResult: 保存油品类型"+resultCode);
                oilCanSet.setGmt_modified(DateUtils.getYYYMMddHHmmss());
                oilTankRepository.updateOilCanSet(oilCanSet);
                Const.isUpdate = true;
            }
        }
        dialogSelectType = -1;
    }

    /**
     * 设置所有控件属性
     */
    private void initOilCanSetData() {
        item_tv_tanzhenID.setText(String.valueOf(oilCanSet.getPk_tanZhenId()));
        item_tv_oilid.setText(String.valueOf(oilCanSet.getPk_oilCanId()));
        item_et_oilheight.setText(String.valueOf(oilCanSet.getHeight()));
       // item_et_oilheight.setTag(R.id.tag_first, oilCanSet.getHeight());
        item_et_maxvolum.setText(String.valueOf(oilCanSet.getMaxVolume()));
        //item_et_chuanGanQiLeng.setText(Singleton.getInstance().TanZhenLang[oilCanSet.getPk_tanZhenId()]+"");
        item_et_chuanGanQiLeng.setText(String.valueOf(oilCanSet.getChuanGanQiHight()));

        int tanZhenId = oilCanSet.getPk_tanZhenId();
        double[] tanZhenLang = Singleton.getInstance().TanZhenLang;
        if (tanZhenId >= 0 && tanZhenId < tanZhenLang.length && tanZhenLang[tanZhenId] != 0) {
            // 只显示探针读到的长度，点"保存数据"时才落库
            item_et_chuanGanQiLeng.setText(tanZhenLang[tanZhenId] + "");
        }
       // TODO  设置传感器长度
        item_sp_guanType.setSelection(oilCanSet.getGunType());

       // item_et_maxvolum.setTag(R.id.tag_first, oilCanSet.getMaxVolume());
        item_et_oilheightalarm.setText(String.valueOf(oilCanSet.getHeightOilAlarm()));
        //item_et_oilheightalarm.setTag(R.id.tag_first, oilCanSet.getHeightOilAlarm());
        //mitem_et_oilHaight.setText(String.valueOf(oilCanSet.getOilHeight()));

        item_et_oilhheightalarm.setText(String.valueOf(oilCanSet.getHheightOilAlarm()));
      //  item_et_oilhheightalarm.setTag(R.id.tag_first, oilCanSet.getHheightOilAlarm());

        item_et_oillowalarm.setText(String.valueOf(oilCanSet.getLowOilAlarm()));
       // item_et_oillowalarm.setTag(R.id.tag_first, oilCanSet.getLowOilAlarm());
        item_et_oilllowalarm.setText(String.valueOf(oilCanSet.getLlowOilAlarm()));
      //  item_et_oilllowalarm.setTag(R.id.tag_first, oilCanSet.getLlowOilAlarm());
        item_et_waterheightalarm.setText(String.valueOf(oilCanSet.getHeightWaterAlarm()));
      //  item_et_waterheightalarm.setTag(R.id.tag_first, oilCanSet.getHeightWaterAlarm());
        item_et_tempheightalarm.setText(String.valueOf(oilCanSet.getHighTempAlarm()));
      //  item_et_tempheightalarm.setTag(R.id.tag_first, oilCanSet.getHighTempAlarm());
        item_et_templowalarm.setText(String.valueOf(oilCanSet.getLowTempAlarm()));
     //   item_et_templowalarm.setTag(R.id.tag_first, oilCanSet.getLowTempAlarm());
        item_et_oillevelcorrection.setText(String.valueOf(Math.round(oilCanSet.getOilCorrection())));
      //  item_et_oillevelcorrection.setTag(R.id.tag_first, oilCanSet.getOilCorrection());
        item_et_waterlevlecorrection.setText(String.valueOf(Math.round(oilCanSet.getWaterCorrection())));
      //  item_et_waterlevlecorrection.setTag(R.id.tag_first, oilCanSet.getWaterCorrection());
        item_et_tempercorrection.setText(String.valueOf(Math.round(oilCanSet.getTempCorrection())));
       // item_et_tempercorrection.setTag(R.id.tag_first, oilCanSet.getTempCorrection());
        item_et_oildensity.setText(String.valueOf(oilCanSet.getDensity()));
       // item_et_oildensity.setTag(R.id.tag_first, oilCanSet.getDensity());
        item_et_densityvar.setText(String.valueOf(oilCanSet.getDensVar()));
       // item_et_densityvar.setTag(R.id.tag_first, oilCanSet.getDensVar());
        item_tv_oiltype.setText(DataUtils.getOilType()[oilCanSet.getOilType()]);
//        item_tv_tanzhenID.setOnFocusChangeListener(new MyOnFocusChangeLis1tener(
//                "Pk_tanZhenId", int.class));
//        item_et_oilheight
//                .setOnFocusChangeListener(new MyOnFocusChangeLis1tener(
//                        "Height", int.class));
//        item_et_maxvolum.setOnFocusChangeListener(new MyOnFocusChangeLis1tener(
//                "MaxVolume", int.class));
////        item_et_chuanGanQiLeng.setOnFocusChangeListener(new MyOnFocusChangeLis1tener(
////                "ChuanGanQiHight", float.class
////        ));
//        item_et_oilheightalarm
//                .setOnFocusChangeListener(new MyOnFocusChangeLis1tener(
//                        "HeightOilAlarm", int.class));
//        item_et_oilhheightalarm
//                .setOnFocusChangeListener(new MyOnFocusChangeLis1tener(
//                        "HheightOilAlarm", int.class));
//        item_et_oillowalarm
//                .setOnFocusChangeListener(new MyOnFocusChangeLis1tener(
//                        "LowOilAlarm", int.class));
//        item_et_oilllowalarm
//                .setOnFocusChangeListener(new MyOnFocusChangeLis1tener(
//                        "LlowOilAlarm", int.class));
//        item_et_waterheightalarm
//                .setOnFocusChangeListener(new MyOnFocusChangeLis1tener(
//                        "HeightWaterAlarm", int.class));
//        item_et_tempheightalarm
//                .setOnFocusChangeListener(new MyOnFocusChangeLis1tener(
//                        "HighTempAlarm", int.class));
//        item_et_templowalarm
//                .setOnFocusChangeListener(new MyOnFocusChangeLis1tener(
//                        "LowTempAlarm", int.class));
//        item_et_oillevelcorrection
//                .setOnFocusChangeListener(new MyOnFocusChangeLis1tener(
//                        "OilCorrection", double.class));
//        item_et_waterlevlecorrection
//                .setOnFocusChangeListener(new MyOnFocusChangeLis1tener(
//                        "WaterCorrection", double.class));
//        item_et_tempercorrection.setOnFocusChangeListener(new MyOnFocusChangeLis1tener(
//                "TempCorrection", double.class));
//        item_et_oildensity
//                .setOnFocusChangeListener(new MyOnFocusChangeLis1tener(
//                        "Density", double.class));
//        item_et_densityvar
//                .setOnFocusChangeListener(new MyOnFocusChangeLis1tener(
//                        "DensVar", double.class));
        item_but_color.setBackgroundColor(oilCanSet.getOilColor());
        View headerView = rootView.findViewById(R.id.title_bar);
        // 再从该 View 中 findViewById 获取内部的 TextView
        tvTitle = headerView.findViewById(R.id.tv_title);
        tv_time=headerView.findViewById(R.id.main_time);
        tvTitle.setText(R.string.oilcanset);
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
    TextView tvTitle,tv_time;

    public void setTvPos(int pos) {
        item_tv_oilid.setText("" + (pos + 1));
    }


    protected void readDB(int postion) {

        oilCanSet = oilTankRepository.findOilCanSetById(postion + 1);
        if (oilCanSet == null) {
            oilCanSet = new OilCanSet();
        }
        initOilCanSetData();
    }

    final static int COUNTS = 5;//点击次数
    final static long DURATION = 3 * 1000;//规定有效时间
    long[] mHits = new long[COUNTS];

    @Override
    protected void processClick(View v) {
        View view = rootView.findFocus();
        if (view != null) {
            view.clearFocus();
        }

        switch (v.getId()) {
            case R.id.item_but_color:
                item_but_color(v);
                break;
            case R.id.item_tv_oilid:
                Intent intent_1 = new Intent(main, ActivityDialog.class);
                intent_1.putExtra("value", 0);
                startActivityForResult(intent_1, 0);
                dialogSelectType = 0;
                break;
            case R.id.item_sp_oiltype:
                Intent intent_2 = new Intent(main, ActivityDialog.class);
                intent_2.putExtra("value", 1);
                startActivityForResult(intent_2, 0);
                dialogSelectType = 1;
                break;
            case R.id.oilcanset_content:
                //Log.i(TAG, "processClick: 滚动条被点击了");
                oilcanset_content.requestFocus();
                break;

            case R.id.ly_baoCunData:
                Log.e("TAG",item_tv_tanzhenID.getText().toString());
                // 探针ID 即协议地址字节（十进制输入），只接受 1-255；越界不保存并恢复原值。
                if (isValidTanZhenId(item_tv_tanzhenID.getText().toString())) {
                    saveData(item_tv_tanzhenID.getText().toString(),"Pk_tanZhenId", int.class);
                } else {
                    Log.w(TAG, "探针ID不在 1-255 范围内，不保存: " + item_tv_tanzhenID.getText());
                    item_tv_tanzhenID.setText(String.valueOf(oilCanSet.getPk_tanZhenId()));
                }
                saveData(item_et_oilheight.getText().toString(),"Height", int.class);
                saveData(item_et_maxvolum.getText().toString(),"MaxVolume", int.class);
                saveData(item_et_chuanGanQiLeng.getText().toString(), "ChuanGanQiHight", float.class);
                saveData(String.valueOf(item_sp_guanType.getSelectedItemPosition()), "GunType", int.class);
                saveData(item_et_oilheightalarm.getText().toString(),"HeightOilAlarm", int.class);
                saveData(item_et_oilhheightalarm.getText().toString(), "HheightOilAlarm", int.class);
                saveData(item_et_oillowalarm.getText().toString(), "LowOilAlarm", int.class);
                saveData(item_et_oilllowalarm.getText().toString(), "LlowOilAlarm", int.class);
                saveData(item_et_waterheightalarm.getText().toString(), "HeightWaterAlarm", int.class);
                saveData(item_et_tempheightalarm.getText().toString(),"HighTempAlarm", int.class);
                saveData(item_et_templowalarm.getText().toString(), "LowTempAlarm", int.class);
                saveData(item_et_oillevelcorrection.getText().toString(),"OilCorrection", double.class);
                saveData(item_et_waterlevlecorrection.getText().toString(),"WaterCorrection", double.class);
                saveData(item_et_tempercorrection.getText().toString(), "TempCorrection", double.class);
                saveData(item_et_oildensity.getText().toString(),"Density", double.class);
                saveData(item_et_densityvar.getText().toString(),"DensVar", double.class);


                showWindDialog(1000);
                break;

            case R.id.ly_copyData:
                //Log.i("TAG", "复制数据");
                Intent intent_4 = new Intent(main, ActivityDialog.class);
                intent_4.putExtra("value", 0);
                startActivityForResult(intent_4, 0);
                dialogSelectType = 8;
                break;
            case R.id.ly_upper_page:
                if (main.setFragment == null) {
                    main.setFragment = new SetFragment();
                }
                main.switchContent(main.mContent, main.setFragment);
                break;
        }
    }

    private void item_but_color(View v) {
        MainActivity main = (MainActivity) getActivity();
        if (main.colorFragment == null) {
            main.colorFragment = new ColorFragment();
        }
        main.colorFragment.setCurrOilCan(oilCanSet);
        // 去颜色选择页之前不保存，也不希望回来时用旧库值把还没保存的输入框冲掉
        skipReflushOnColorReturn = true;
        main.switchContent(main.mContent, main.colorFragment);

    }

    @Override
    public void onHiddenChanged(boolean hidden) {
        // TODO Auto-generated method stub
        super.onHiddenChanged(hidden);

        if (!hidden) {
            KeyboardAdjustHelper.applyPanWhileVisible(this);
        }

        if (skipReflushOnColorReturn) {
            // 去/回颜色选择页期间：不落库也不重新读库覆盖输入框，只在回来时同步颜色按钮背景
            if (!hidden) {
                item_but_color.setBackgroundColor(oilCanSet.getOilColor());
                skipReflushOnColorReturn = false;
            }
            return;
        }

        if (hidden & Const.isUpdate == true) {
            updateData();
            Const.isUpdate = false;
        } else {
            onReflushData();
        }
    }

    private static boolean isValidTanZhenId(String text) {
        try {
            int id = Integer.parseInt(text.trim());
            return id >= 1 && id <= 255;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private void saveData(String cur, String methedName, Class clazz) {

       //String cur = vText.getText().toString();
        Log.e("TAG探针--: ",cur);
        Class oilCanClazz = oilCanSet.getClass();
        Method method = null;
        try {
            method = oilCanClazz.getDeclaredMethod("set"
                    + methedName, clazz);
        } catch (NoSuchMethodException e) {
            Log.e(TAG, "saveData 执行失败", e);
        }
        try {

            if (clazz.equals(int.class)) {

                method.invoke(oilCanSet, Integer.parseInt(cur));
                if (method.getName().equals("setHeight")) {
                    if (Integer.parseInt(cur) > 0)
                        oilCanSet.setEnable(1);
                    else
                        oilCanSet.setEnable(0);
                    OilcanDataManager.getInstance(MyApplication.getInstance().getApplicationContext()).refresh();
                }
                //Log.i("TAG", method.getName());
            } else if (clazz.equals(double.class)) {
                method.invoke(oilCanSet, Double.parseDouble(cur));
            } else if (clazz.equals(float.class)) {
                method.invoke(oilCanSet, Float.parseFloat(cur));
            }
            oilCanSet.setGmt_modified(DateUtils.getYYYMMddHHmmss());
            oilTankRepository.updateOilCanSet(oilCanSet);
          //  vText.setText(cur);
          //  vText.setText("");
            Const.isUpdate = true;
          //  vText.setTag(R.id.tag_first, vText.getText().toString());
        } catch (Exception e) {
            Log.e(TAG, "saveData 执行失败", e);
        }
    }


    class MyOnFocusChangeLis1tener implements OnFocusChangeListener {
        private String methedName;
        private Class clazz;

        public MyOnFocusChangeLis1tener(String methedName, Class clazz) {
            this.methedName = methedName;
            this.clazz = clazz;
        }

        @Override
        public void onFocusChange(View v, boolean hasFocus) {
            EditText vText = (EditText) v;

            String lastInfo = null;
            if (vText.getTag(R.id.tag_first) == null) {
                lastInfo = vText.getText().toString();
                vText.setTag(R.id.tag_first, lastInfo);
            } else {
                lastInfo = vText.getTag(R.id.tag_first).toString();
            }
            if (!hasFocus) {
                String cur = vText.getText().toString();
                Log.e("TAG探针",cur);
                if (!cur.equals(lastInfo)) {
                    Class oilCanClazz = oilCanSet.getClass();

                    Method method = null;
                    try {
                        method = oilCanClazz.getDeclaredMethod("set"
                                + methedName, clazz);
                    } catch (NoSuchMethodException e) {
                        Log.e(TAG, "onFocusChange 执行失败", e);
                    }

                    try {

                        if (clazz.equals(int.class)) {

                            method.invoke(oilCanSet, Integer.parseInt(cur));
                            if (method.getName().equals("setHeight")) {
                                if (Integer.parseInt(cur) > 0)
                                    oilCanSet.setEnable(1);
                                else
                                    oilCanSet.setEnable(0);
                                OilcanDataManager.getInstance(MyApplication.getInstance().getApplicationContext()).refresh();
                            }
                            //Log.i("TAG", method.getName());
                        } else if (clazz.equals(double.class)) {
                            method.invoke(oilCanSet, Double.parseDouble(cur));
                        }
                        oilCanSet.setGmt_modified(DateUtils.getYYYMMddHHmmss());
                      //  oilTankRepository.updateOilCanSet(oilCanSet);
                        vText.setText(cur);
                        vText.setText("");
                        Const.isUpdate = true;
                        vText.setTag(R.id.tag_first, vText.getText().toString());
                    } catch (Exception e) {
                        Log.e(TAG, "onFocusChange 执行失败", e);
                    }
                } else {
                    vText.setText("");
                }
            }

        }

    }

    @Override
    public void onReflushData() {

        readDB(curPos);

    }


}
