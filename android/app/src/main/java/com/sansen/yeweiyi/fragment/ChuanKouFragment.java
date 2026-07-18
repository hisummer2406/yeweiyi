package com.sansen.yeweiyi.fragment;

import android.os.Build;
import android.os.Bundle;
import android.support.annotation.RequiresApi;
import android.view.LayoutInflater;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.LinearLayout;
import android.widget.RadioGroup;

import com.sansen.yeweiyi.R;
import com.sansen.yeweiyi.application.MyApplication;
import com.sansen.yeweiyi.base.BaseFragment;
import com.sansen.yeweiyi.fragment.SetPawFragment.SetType;
import com.sansen.yeweiyi.utils.Constans;
import com.sansen.yeweiyi.utils.SharePrefUtil;


/**
 * 权限管理
 */
public class ChuanKouFragment extends BaseFragment  {

    private RadioGroup rg_serial2, rg_serial4, rg_serial5;
    private LinearLayout ly_upper_page;


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
        if (v.getId()==R.id.ly_upper_page){
            if (main.setFragment == null) {
                main.setFragment = new SetFragment();
            }
            main.switchContent(main.mContent, main.setFragment);
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
        rg_serial5 = rootView.findViewById(R.id.rg_serial5);
        ly_upper_page=rootView.findViewById(R.id.ly_upper_page);
        ly_upper_page.setOnClickListener(this);
        rg_serial2.setOnCheckedChangeListener((group, checkedId) -> {
            switch (checkedId) {
                case R.id.rb_serial2_zhongcheng:
                    SharePrefUtil.saveInt(MyApplication.getInstance().getApplicationContext(), Constans.ChuanKou2,  0);
                    // 选项 A
                    break;
                case R.id.rb_serial2_modbus:
                    SharePrefUtil.saveInt(MyApplication.getInstance().getApplicationContext(), Constans.ChuanKou2,  1 );
                    // 选项 B
                    break;
                case R.id.rb_serial2_mA:
                    SharePrefUtil.saveInt(MyApplication.getInstance().getApplicationContext(), Constans.ChuanKou2,  2 );
                    break;

            }
        });
        rg_serial4.setOnCheckedChangeListener((group, checkedId) -> {
            switch (checkedId) {
                case R.id.rb_serial4_oil:
                    SharePrefUtil.saveInt(MyApplication.getInstance().getApplicationContext(), Constans.ChuanKou4,  0);
                    // 选项 A
                    break;
                case R.id.rb_serial4_modbus:
                    SharePrefUtil.saveInt(MyApplication.getInstance().getApplicationContext(), Constans.ChuanKou4,  1);
                    // 选项 B
                    break;
            }
        });
        rg_serial5.setOnCheckedChangeListener((group, checkedId) -> {
            switch (checkedId) {
                case R.id.rb_serial5_vr:
                    SharePrefUtil.saveInt(MyApplication.getInstance().getApplicationContext(), Constans.ChuanKou5,  0);
                    //
                    // 选项 A
                    break;
                case R.id.rb_serial5_plc:
                    SharePrefUtil.saveInt(MyApplication.getInstance().getApplicationContext(), Constans.ChuanKou5,  1);
                    //
                    // 选项 B
                    break;
            }
        });
        switch (SharePrefUtil.getInt(MyApplication.getInstance().getApplicationContext(), Constans.ChuanKou2,0)){
            case 0 :
                rg_serial2.check(R.id.rb_serial2_zhongcheng);
                break;

            case 1:
                rg_serial2.check(R.id.rb_serial2_modbus);
                break;

            case 2:
                rg_serial2.check( R.id.rb_serial2_mA);
                break;
        }
        rg_serial4.check(SharePrefUtil.getInt(MyApplication.getInstance().getApplicationContext(), Constans.ChuanKou4, 0) == 0 ? R.id.rb_serial4_oil : R.id.rb_serial4_modbus);
        rg_serial5.check(SharePrefUtil.getInt(MyApplication.getInstance().getApplicationContext(), Constans.ChuanKou5, 0) == 0 ? R.id.rb_serial5_vr :  R.id.rb_serial5_plc);

    }


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
}
