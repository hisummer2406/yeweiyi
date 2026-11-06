package com.sansen.yeweiyi.fragment;

import android.databinding.Observable;
import android.databinding.ObservableField;
import android.os.Build;
import android.os.Bundle;
import android.support.annotation.RequiresApi;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.sansen.yeweiyi.R;
import com.sansen.yeweiyi.application.MyApplication;
import com.sansen.yeweiyi.applog.LogExportUi;
import com.sansen.yeweiyi.base.BaseFragment;
import com.sansen.yeweiyi.bean.SerialPortEntrity;
import com.sansen.yeweiyi.fragment.SetPawFragment.SetType;
import com.sansen.yeweiyi.languageUtil.LanguageUtil;

public class SetSeniorFragment extends BaseFragment {

    private LinearLayout ly_mima, ly_baobiao, ly_bulu, ly_chuankou, ly_yanshi, ly_thingsboard, ly_export_log, ly_upper_page;


    @Override
    protected View initView(LayoutInflater inflater) {
        return inflater.inflate(R.layout.fragment_senior_set, null);
    }

    @Override
    protected void initData(Bundle savedInstanceState) {
        findView();
    }


    @RequiresApi(api = Build.VERSION_CODES.O)
    @Override
    protected void processClick(View v) {

        switch (v.getId()) {
            case R.id.ly_baobiao:
                BaoBiaoFragment biaoFragment = new BaoBiaoFragment();
                main.switchContent(main.mContent, biaoFragment);
                Log.e("TAG", "报表");
                break;
			case R.id.ly_mima:
                loading_gaoji_set(SetType.GAOJISET);


				Log.e("TAG", "ly_mima");
				break;
			case R.id.ly_bulu:
			    //
                BuLuFragment buLuFragment = new BuLuFragment();
                main.switchContent(main.mContent, buLuFragment);
				Log.e("TAG", "ly_bulu");
				break;
			case R.id.ly_yanshi:
                OilTestFragment oilTestFragment = new OilTestFragment();
                main.switchContent(main.mContent, oilTestFragment);
				Log.e("TAG", "ly_yanshi");
				break;
			case R.id.ly_chuankou:
                ChuanKouFragment chuanKouFragment = new ChuanKouFragment();
                main.switchContent(main.mContent, chuanKouFragment);
				break;
            case R.id.ly_thingsboard:
                ThingsBoardSetFragment thingsBoardSetFragment = new ThingsBoardSetFragment();
                main.switchContent(main.mContent, thingsBoardSetFragment);
                break;
            case R.id.ly_export_log:
                LogExportUi.exportAndToast();
                break;
            case R.id.ly_upper_page:
                if (main.setFragment == null) {
                    main.setFragment = new SetFragment();
                }
                main.switchContent(main.mContent, main.setFragment);
                break;
        }
    }
    public void loading_gaoji_set(SetType setType) {

        SetPawFragment setPawFragment = new SetPawFragment(setType);
        main.switchContent(main.mContent, setPawFragment);
    }
    @Override
    public void findView() {
        ly_mima = rootView.findViewById(R.id.ly_mima);
        ly_baobiao = rootView.findViewById(R.id.ly_baobiao);
        ly_bulu = rootView.findViewById(R.id.ly_bulu);
        ly_chuankou = rootView.findViewById(R.id.ly_chuankou);
        ly_yanshi = rootView.findViewById(R.id.ly_yanshi);
        ly_thingsboard = rootView.findViewById(R.id.ly_thingsboard);
        ly_upper_page=rootView.findViewById(R.id.ly_upper_page);
        ly_upper_page.setOnClickListener(this);
        ly_baobiao.setOnClickListener(this);
        ly_bulu.setOnClickListener(this);
        ly_chuankou.setOnClickListener(this);
        ly_yanshi.setOnClickListener(this);
        ly_thingsboard.setOnClickListener(this);
        // 云平台设置页（ThingsBoardSetFragment）的文案目前写死中文、还没有多语言版本，
        // 暂时只在中文界面下开放入口；GONE 让同行其余按钮按权重重新铺满，非中文界面布局和加这个入口之前一致
        ly_thingsboard.setVisibility(LanguageUtil.isChinese(MyApplication.getInstance().getApplicationContext()) ? View.VISIBLE : View.GONE);
        ly_export_log = rootView.findViewById(R.id.ly_export_log);
        ly_export_log.setOnClickListener(this);
        // 长按进入日志诊断页（改级别、清空），给技术支持用；现场人员只需要点按导出
        ly_export_log.setOnLongClickListener(v -> {
            LogSetFragment.open(main);
            return true;
        });
        ly_mima.setOnClickListener(this);

        View headerView = rootView.findViewById(R.id.title_bar);
        // 再从该 View 中 findViewById 获取内部的 TextView
        tvTitle = headerView.findViewById(R.id.tv_title);
        tv_time=headerView.findViewById(R.id.main_time);
        tvTitle.setText(R.string.advanced_settings);
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
    @Override
    public void initTitle() {

        if (main != null) {
//            main.tv_left.setText(SystemUtils.getRsStr(R.string.back_page));
//            main.iv_right.setVisibility(View.VISIBLE);
//            main.tv_left.setVisibility(View.VISIBLE);
//            main.iv_right.setImageResource(R.drawable.laststep);
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
    public void onReflushData() {


    }


}
