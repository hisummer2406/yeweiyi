package com.sansen.yeweiyi.fragment;


import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.RadioGroup.OnCheckedChangeListener;

import com.sansen.yeweiyi.R;
import com.sansen.yeweiyi.activity.MainActivity;
import com.sansen.yeweiyi.base.BaseActivity;
import com.sansen.yeweiyi.base.BaseFragment;
import com.sansen.yeweiyi.utils.SharePrefUtil;

import java.util.Locale;


public class LanguageFragment extends BaseFragment implements OnCheckedChangeListener {


    private RadioGroup Language_rg_group;

    private RadioButton Language_rb_china;

    private RadioButton Language_rb_english;

    @Override
    protected View initView(LayoutInflater inflater) {
        return inflater.inflate(R.layout.fragment_language, null);
    }

    @Override
    protected void initData(Bundle savedInstanceState) {
        findView();
        String language = SharePrefUtil.getString(ct, SharePrefUtil.LANGUAGE, "zh");
        if (language.equals("en")) {
            Language_rg_group.check(R.id.Language_rb_english);
        } else if (language.equals("zh")) {
            Language_rg_group.check(R.id.Language_rb_china);
        } else if (language.equals("th")){
            Language_rg_group.check(R.id.Language_rb_th);
        }
        Language_rg_group.setOnCheckedChangeListener(this);
    }

    @Override
    public void findView() {
        Language_rg_group = (RadioGroup) rootView.findViewById(R.id.Language_rg_group);
        Language_rb_china = (RadioButton) rootView.findViewById(R.id.Language_rb_china);
        Language_rb_english = (RadioButton) rootView.findViewById(R.id.Language_rb_english);
    }

    @Override
    protected void processClick(View v) {

    }

    @Override
    public void onCheckedChanged(RadioGroup group, int checkedId) {

        switch (checkedId) {

            case R.id.Language_rb_china:
                Locale zh = new Locale("zh");
                myApplication.setLocale(zh);
                setLanguage(zh);
                SharePrefUtil.saveString(ct, SharePrefUtil.LANGUAGE, "zh");
                break;
            case R.id.Language_rb_english:
                Locale en = new Locale("en");
                myApplication.setLocale(en);
                setLanguage(en);
                SharePrefUtil.saveString(ct, SharePrefUtil.LANGUAGE, "en");
                break;
            case R.id.Language_rb_th:
                Locale th = new Locale("th");
                myApplication.setLocale(th);
                setLanguage(th);
                SharePrefUtil.saveString(ct, SharePrefUtil.LANGUAGE, "th");
                break;
        }

    }

    private void setLanguage(Locale locale) {
        BaseActivity.settingLanguage(locale);
        main.finish();
        Intent intent = new Intent(ct, MainActivity.class);
        startActivity(intent);
    }

    @Override
    public void initTitle() {
        // TODO Auto-generated method stub
        if (main != null) {
//            main.tv_left.setText(SystemUtils.getRsStr(R.string.back_page));
//            main.iv_right.setImageResource(R.drawable.laststep);
//            OnClickListener backSetListener = new OnClickListener() {
//
//                @Override
//                public void onClick(View v) {
//                    SystemSetFragment systemSetFragment = new SystemSetFragment();
//                    main.switchContent(main.mContent, systemSetFragment);
//
//                }
//            };
//            main.tv_left.setOnClickListener(backSetListener);
//            main.iv_right.setOnClickListener(backSetListener);
        }
    }
}
