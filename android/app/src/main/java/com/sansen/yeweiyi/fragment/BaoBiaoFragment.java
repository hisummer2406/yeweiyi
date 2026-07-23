package com.sansen.yeweiyi.fragment;

import android.databinding.Observable;
import android.databinding.ObservableField;
import android.os.Build;
import android.os.Bundle;
import android.support.annotation.RequiresApi;
import android.view.LayoutInflater;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;

import com.sansen.yeweiyi.R;
import com.sansen.yeweiyi.application.MyApplication;
import com.sansen.yeweiyi.base.BaseFragment;
import com.sansen.yeweiyi.bean.SerialPortEntrity;
import com.sansen.yeweiyi.fragment.SetPawFragment.SetType;
import com.sansen.yeweiyi.utils.Constans;
import com.sansen.yeweiyi.utils.SharePrefUtil;
import com.sansen.yeweiyi.utils.Singleton;


/**
 * 权限管理
 */
public class BaoBiaoFragment extends BaseFragment implements CompoundButton.OnCheckedChangeListener {

    private Switch sw_alarm, sw_out, sw_in, sw_shift, sw_stock;
    private LinearLayout ly_upper_page;


    @Override
    protected View initView(LayoutInflater inflater) {
        return inflater.inflate(R.layout.fragment_bao_biao, null);
    }

    @Override
    protected void initData(Bundle savedInstanceState) {
        findView();
    }


    @RequiresApi(api = Build.VERSION_CODES.O)
    @Override
    protected void processClick(View v) {
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
        sw_alarm = rootView.findViewById(R.id.sw_alarm);
        sw_out = rootView.findViewById(R.id.sw_out);
        sw_in = rootView.findViewById(R.id.sw_in);
        sw_shift = rootView.findViewById(R.id.sw_shift);
        sw_stock = rootView.findViewById(R.id.sw_stock);
        ly_upper_page=rootView.findViewById(R.id.ly_upper_page);
        ly_upper_page.setOnClickListener(this);
        sw_out.setOnCheckedChangeListener(this);
        sw_in.setOnCheckedChangeListener(this);
        sw_shift.setOnCheckedChangeListener(this);
        sw_stock.setOnCheckedChangeListener(this);
        sw_alarm.setOnCheckedChangeListener(this);
        sw_alarm.setChecked(SharePrefUtil.getInt(MyApplication.getInstance().getApplicationContext(), Constans.IsBaoJing,0)==0?false:true);
        sw_in.setChecked(SharePrefUtil.getInt(MyApplication.getInstance().getApplicationContext(), Constans.IsRuKu,0)==0?false:true);
        sw_out.setChecked(SharePrefUtil.getInt(MyApplication.getInstance().getApplicationContext(), Constans.IsChuKu,0)==0?false:true);
        sw_shift.setChecked(SharePrefUtil.getInt(MyApplication.getInstance().getApplicationContext(), Constans.IsJiaoBan,0)==0?false:true);
        sw_stock.setChecked(SharePrefUtil.getInt(MyApplication.getInstance().getApplicationContext(), Constans.IsKuCun,0)==0?false:true);

        View headerView = rootView.findViewById(R.id.title_bar);
        // 再从该 View 中 findViewById 获取内部的 TextView
        tvTitle = headerView.findViewById(R.id.tv_title);
        tv_time=headerView.findViewById(R.id.main_time);
        tvTitle.setText("报表设置");
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

                    if (main.setSeniorFragment == null) {
                        main.setSeniorFragment = new SetSeniorFragment();
                    }
                    main.switchContent(main.mContent, main.setSeniorFragment);

                }
            };
//            main.tv_left.setOnClickListener(backSetListener);
//            main.iv_right.setOnClickListener(backSetListener);

        }
    }

    @Override
    public void onReflushData() {


    }


    @Override
    public void onCheckedChanged(CompoundButton compoundButton, boolean b) {
        switch (compoundButton.getId()){
            case R.id.sw_alarm:
                SharePrefUtil.saveInt(MyApplication.getInstance().getApplicationContext(), Constans.IsBaoJing,b?1:0);
                Singleton.getInstance().getSetData(Constans.IsBaoJing);
                //Log.i("TAG","sw_alarm");
                break;
            case R.id.sw_in:
                SharePrefUtil.saveInt(MyApplication.getInstance().getApplicationContext(), Constans.IsRuKu,b?1:0);
                Singleton.getInstance().getSetData(Constans.IsRuKu);
                //Log.i("TAG","sw_in");
                break;
            case R.id.sw_out:
                SharePrefUtil.saveInt(MyApplication.getInstance().getApplicationContext(), Constans.IsChuKu,b?1:0);
                Singleton.getInstance().getSetData(Constans.IsChuKu);
                //Log.i("TAG","sw_out");
                break;
            case R.id.sw_shift:
                SharePrefUtil.saveInt(MyApplication.getInstance().getApplicationContext(), Constans.IsJiaoBan,b?1:0);
                Singleton.getInstance().getSetData(Constans.IsJiaoBan);
                //Log.i("TAG","sw_shift");
                break;
            case R.id.sw_stock:
                SharePrefUtil.saveInt(MyApplication.getInstance().getApplicationContext(), Constans.IsKuCun,b?1:0);
                Singleton.getInstance().getSetData(Constans.IsKuCun);
                //Log.i("TAG","sw_stock");
                break;
        }

    }
}
