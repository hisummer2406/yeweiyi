package com.sansen.yeweiyi.fragment;

import android.databinding.Observable;
import android.databinding.ObservableField;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.AdapterView;
import android.widget.AdapterView.OnItemClickListener;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;

import com.sansen.yeweiyi.R;
import com.sansen.yeweiyi.manager.ThingsBoardManager;
import com.sansen.yeweiyi.application.MyApplication;
import com.sansen.yeweiyi.base.BaseFragment;
import com.sansen.yeweiyi.bean.SerialPortEntrity;
import com.sansen.yeweiyi.db.User;
import com.sansen.yeweiyi.languageUtil.LanguageUtil;
import com.sansen.yeweiyi.manager.AppManager;
import com.sansen.yeweiyi.utils.Constans;
import com.sansen.yeweiyi.utils.KeyboardAdjustHelper;
import com.sansen.yeweiyi.utils.SharePrefUtil;
import com.sansen.yeweiyi.utils.Singleton;
import com.sansen.yeweiyi.utils.UnitPref;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;


/**
 * 权限管理
 */
public class SetBaseFragment extends BaseFragment implements OnItemClickListener, TextWatcher, AdapterView.OnItemSelectedListener {

    // 顺序必须跟 R.array.language（中文/英文/西班牙语/俄语/乌兹别克语/法语/葡萄牙语）的 item 顺序一一对应，
    // Spinner 的下标直接映射这里的下标
    private static final String[] LANGUAGE_CODES = {"zh", "en", "es", "ru", "uz", "fr", "pt"};

    private Spinner sp_yuyan, sp_yewei, sp_rongji, sp_zhiliang;
    private EditText et_outTime, et_outHight, et_inTime, et_inHight;
    private LinearLayout ly_saveData,ly_upper_page;

    @Override
    protected View initView(LayoutInflater inflater) {
        return inflater.inflate(R.layout.fragment_base_set, null);
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
        sp_yuyan = rootView.findViewById(R.id.sp_yuyan);
        sp_yewei = rootView.findViewById(R.id.sp_yewei);
        sp_rongji = rootView.findViewById(R.id.sp_rongji);
        sp_zhiliang = rootView.findViewById(R.id.sp_zhiliang);
        et_outTime = rootView.findViewById(R.id.et_outTime);
        et_outHight = rootView.findViewById(R.id.et_outHight);
        et_inTime = rootView.findViewById(R.id.et_inTime);
        et_inHight = rootView.findViewById(R.id.et_inHight);
        ly_saveData=rootView.findViewById(R.id.ly_saveData);
        ly_upper_page=rootView.findViewById(R.id.ly_upper_page);

        et_outHight.addTextChangedListener(this);
        et_outTime.addTextChangedListener(this);
        et_inTime.addTextChangedListener(this);
        et_inHight.addTextChangedListener(this);
        ly_saveData.setOnClickListener(this);
        ly_upper_page.setOnClickListener(this);
        KeyboardAdjustHelper.hideActionBarWhileKeyboardVisible(rootView, ly_saveData, ly_upper_page);

        sp_rongji.setOnItemSelectedListener(this);
        sp_yewei.setOnItemSelectedListener(this);
        sp_yuyan.setOnItemSelectedListener(this);
        sp_zhiliang.setOnItemSelectedListener(this);
        rongji_unit = getResources().getStringArray(R.array.rongji_unit);
        zhiliang_unit = getResources().getStringArray(R.array.zhiliang_unit);
        yewei_unit = getResources().getStringArray(R.array.yewei_unit);
        // 单位偏好统一按 UnitPref 解析：既认新写入的规范值（"L"/"MM"/"KG"...），也认历史遗留的
        // 中文原文和曾经被 bug 误存的英文，不会因为这次修复把用户已有的选择读错。
        // 顺带修掉一个已有笔误：这里原来把容积单位的初始选中状态设到了 sp_zhiliang（质量）上，
        // sp_rongji 反而从没被设置过。
        // Volume 枚举声明顺序（L, CUBIC, GALLON）必须跟 R.array.rongji_unit 的 item 顺序一一
        // 对应，直接拿 ordinal() 当下标；以后再加新容积单位，两边顺序要一起改。
        sp_rongji.setSelection(UnitPref.Volume.parse(SharePrefUtil.getString(MyApplication.getInstance().getApplicationContext(), Constans.RongJiUnit, UnitPref.Volume.L.name())).ordinal());
        sp_yuyan.setSelection(languageCodeToIndex(LanguageUtil.getCurrentLocale(MyApplication.getInstance().getApplicationContext()).getLanguage()));
        sp_yewei.setSelection(UnitPref.Length.parse(SharePrefUtil.getString(MyApplication.getInstance().getApplicationContext(), Constans.YeWeiUnit, UnitPref.Length.MM.name())) == UnitPref.Length.MM ? 0 : 1);
        sp_zhiliang.setSelection(UnitPref.Mass.parse(SharePrefUtil.getString(MyApplication.getInstance().getApplicationContext(), Constans.ZhiLiangUnit, UnitPref.Mass.KG.name())) == UnitPref.Mass.KG ? 0 : 1);

        inHight=SharePrefUtil.getInt(MyApplication.getInstance().getApplicationContext(), Constans.InHight,0 );
        et_inHight.setText(inHight+"");
        et_inTime.setText(SharePrefUtil.getInt(MyApplication.getInstance().getApplicationContext(), Constans.InTime,0 )+"");
        et_outTime.setText(SharePrefUtil.getInt(MyApplication.getInstance().getApplicationContext(), Constans.OutTime,0 )+"");
//        outHight=SharePrefUtil.getInt(MyApplication.getInstance().getApplicationContext(), Constans.OutHight,0 );
        et_outHight.setText(SharePrefUtil.getInt(MyApplication.getInstance().getApplicationContext(), Constans.OutHight,0 )+"");
        View headerView = rootView.findViewById(R.id.title_bar);
        // 再从该 View 中 findViewById 获取内部的 TextView
        tvTitle = headerView.findViewById(R.id.tv_title);
        tv_time=headerView.findViewById(R.id.main_time);
        tvTitle.setText(R.string.base_set_title);
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
    private List<User> getData() {
        List<User> lists = dbManager.findUser(User.class);
        User user;
        for (int i = 0; i < lists.size(); i++) {
            user = lists.get(i);
            if (user.getId().equals("admin")) {
                lists.remove(user);
                break;
            }
        }

        if (lists == null) {
            lists = new ArrayList<User>();
        }
        return lists;
    }

    // LANGUAGE_CODES 里找不到（理论上不会发生，未来万一系统语言不在这几种之内）时落回中文
    private static int languageCodeToIndex(String languageCode) {
        for (int i = 0; i < LANGUAGE_CODES.length; i++) {
            if (LANGUAGE_CODES[i].equals(languageCode)) {
                return i;
            }
        }
        return 0;
    }

    private int inHight,inTime,outHight,outTime;
    // 语言切换跟其他几个设置项一样，选中后先缓存，点击"保存"才真正生效并重启 app
    private Locale pendingLocale;
    @Override
    protected void processClick(View v) {
        switch (v.getId()) {
            case R.id.ly_saveData:
                //Log.i("TAG", "点击了保存数据");
                if (inHight!=0){
                    SharePrefUtil.saveInt(MyApplication.getInstance().getApplicationContext(), Constans.InHight,inHight );
                    Singleton.getInstance().getSetData(Constans.InHight);
                }
                if (inTime!=0)
                {
                    SharePrefUtil.saveInt(MyApplication.getInstance().getApplicationContext(), Constans.InTime,inTime );
                    Singleton.getInstance().getSetData(Constans.InTime);
                }

                if (outHight!=0){
                    SharePrefUtil.saveInt(MyApplication.getInstance().getApplicationContext(), Constans.OutHight,outHight );
                    Singleton.getInstance().getSetData(Constans.OutHight);
                }
                if (outTime!=0)
                {
                    SharePrefUtil.saveInt(MyApplication.getInstance().getApplicationContext(), Constans.OutTime ,outTime);
                    Singleton.getInstance().getSetData(Constans.OutTime);
                }
                // 出入库阈值属于云端客户端属性，保存后重发（切语言会重启 app，重启后连上时也会发）
                ThingsBoardManager.getInstance(MyApplication.getInstance()).requestClientAttributesPublish();
                if (pendingLocale != null && LanguageUtil.updateLocale(MyApplication.getInstance().getApplicationContext(), pendingLocale)) {
                    // 语言切换涉及大量已渲染文案，且 recreate() 会对本 app 里缺无参构造函数的
                    // 几个 Fragment 触发反射恢复崩溃（详见 AppManager.restartApp 注释），
                    // 改成整体重启 app；后面的保存成功提示已经没有意义，直接结束
                    AppManager.getAppManager().restartApp(MyApplication.getInstance());
                    return;
                }
               showWindDialog(2000);
                break;

            case R.id.ly_upper_page:
                if (main.setFragment == null) {
                    main.setFragment = new SetFragment();
                }
                main.switchContent(main.mContent, main.setFragment);
                break;
        }

    }

    @Override
    public void onItemClick(AdapterView<?> parent, View view, int position,
                            long id) {


    }

    @Override
    public void initTitle() {

        if (main != null) {
//            main.tv_left.setText(SystemUtils.getRsStr(R.string.back_page));
//            main.iv_right.setVisibility(View.VISIBLE);
//            main.tv_left.setVisibility(View.VISIBLE);
//            main.iv_right.setImageResource(R.drawable.laststep);
//            OnClickListener backSetListener = new OnClickListener() {
//
//                @Override
//                public void onClick(View v) {
//
//                    if (main.setFragment == null) {
//                        main.setFragment = new SetFragment();
//                    }
//                    main.switchContent(main.mContent, main.setFragment);
//
//                }
//            };
//            main.tv_left.setOnClickListener(backSetListener);
//            main.iv_right.setOnClickListener(backSetListener);

        }
    }

    @Override
    public void onReflushData() {


    }


    @Override
    public void beforeTextChanged(CharSequence charSequence, int i, int i1, int i2) {

    }

    @Override
    public void onTextChanged(CharSequence charSequence, int i, int i1, int i2) {

    }


    @Override
    public void afterTextChanged(Editable editable) {
        if (editable.toString().isEmpty()){
            return;
        }
        if (et_inHight.hasFocus()) {
            inHight=Integer.parseInt(editable.toString().trim());

        } else if (et_inTime.hasFocus()) {
            inTime=Integer.parseInt(editable.toString().trim());

        }  else if (et_outTime.hasFocus()) {
            outTime=Integer.parseInt(editable.toString().trim());

        } else if (et_outHight.hasFocus()) {
            outHight=Integer.parseInt(editable.toString().trim());
        }

    }

    String[] rongji_unit;
    String[] zhiliang_unit;
    String[] yewei_unit;


    @Override
    public void onItemSelected(AdapterView<?> adapterView, View view, int index, long l) {
        switch (adapterView.getId()) {
            case R.id.sp_rongji:
                // Spinner 展示的是本地化文案（rongji_unit[index]），但存进偏好的必须是跟语言无关
                // 的规范值，不然英文界面下选的选项会存成英文，后面按中文字面量比较全部失效。
                // Volume.values()[index] 依赖 Volume 声明顺序跟 R.array.rongji_unit 的 item
                // 顺序一一对应（见上面 setSelection 那句注释）。
                SharePrefUtil.saveString(MyApplication.getInstance().getApplicationContext(), Constans.RongJiUnit,
                        UnitPref.Volume.values()[index].name());
                break;
            case R.id.sp_yewei:
                SharePrefUtil.saveString(MyApplication.getInstance().getApplicationContext(), Constans.YeWeiUnit,
                        (index == 0 ? UnitPref.Length.MM : UnitPref.Length.M).name());
                break;
            case R.id.sp_zhiliang:
                SharePrefUtil.saveString(MyApplication.getInstance().getApplicationContext(), Constans.ZhiLiangUnit,
                        (index == 0 ? UnitPref.Mass.KG : UnitPref.Mass.TON).name());
                break;
            case R.id.sp_yuyan:
                // 跟 inHight/inTime/outHight/outTime 一样只是先缓存，点"保存"（ly_saveData）
                // 时才真正持久化并重启 app，不在选中的瞬间就有副作用
                pendingLocale = new Locale(LANGUAGE_CODES[index]);
                break;
        }
    }

    @Override
    public void onNothingSelected(AdapterView<?> adapterView) {

    }
}
