package com.sansen.yeweiyi.fragment;

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

import com.sansen.yeweiyi.R;
import com.sansen.yeweiyi.application.MyApplication;
import com.sansen.yeweiyi.base.BaseFragment;
import com.sansen.yeweiyi.db.User;
import com.sansen.yeweiyi.utils.Constans;
import com.sansen.yeweiyi.utils.SharePrefUtil;
import com.sansen.yeweiyi.utils.ToastUtils;

import java.util.ArrayList;
import java.util.List;


/**
 * 权限管理
 */
public class SetBaseFragment extends BaseFragment implements OnItemClickListener, TextWatcher, AdapterView.OnItemSelectedListener {


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

        sp_rongji.setOnItemSelectedListener(this);
        sp_yewei.setOnItemSelectedListener(this);
        sp_yuyan.setOnItemSelectedListener(this);
        sp_zhiliang.setOnItemSelectedListener(this);
        language = getResources().getStringArray(R.array.language);
        rongji_unit = getResources().getStringArray(R.array.rongji_unit);
        zhiliang_unit = getResources().getStringArray(R.array.zhiliang_unit);
        yewei_unit = getResources().getStringArray(R.array.yewei_unit);
        sp_zhiliang.setSelection("升".equals(SharePrefUtil.getString(MyApplication.getInstance().getApplicationContext(), Constans.RongJiUnit,"升" ))? 0:1);
        sp_yuyan.setSelection("中文".equals(SharePrefUtil.getString(MyApplication.getInstance().getApplicationContext(), Constans.Language,"中文" ))? 0:1);
        sp_yewei.setSelection("毫米".equals(SharePrefUtil.getString(MyApplication.getInstance().getApplicationContext(), Constans.YeWeiUnit,"毫米" ))? 0:1);
        sp_zhiliang.setSelection("千克".equals(SharePrefUtil.getString(MyApplication.getInstance().getApplicationContext(), Constans.ZhiLiangUnit,"千克" ))? 0:1);

        inHight=SharePrefUtil.getInt(MyApplication.getInstance().getApplicationContext(), Constans.InHight,0 );
        et_inHight.setText(inHight+"");
        et_inTime.setText(SharePrefUtil.getInt(MyApplication.getInstance().getApplicationContext(), Constans.InTime,0 )+"");
        et_outTime.setText(SharePrefUtil.getInt(MyApplication.getInstance().getApplicationContext(), Constans.OutTime,0 )+"");
//        outHight=SharePrefUtil.getInt(MyApplication.getInstance().getApplicationContext(), Constans.OutHight,0 );
        et_outHight.setText(SharePrefUtil.getInt(MyApplication.getInstance().getApplicationContext(), Constans.OutHight,0 )+"");
    }

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

    private int inHight,inTime,outHight,outTime;
    @Override
    protected void processClick(View v) {
        switch (v.getId()) {
            case R.id.ly_saveData:
                //Log.i("TAG", "点击了保存数据");
                if (inHight!=0){
                    SharePrefUtil.saveInt(MyApplication.getInstance().getApplicationContext(), Constans.InHight,inHight );
                }
                if (inTime!=0)
                {
                    SharePrefUtil.saveInt(MyApplication.getInstance().getApplicationContext(), Constans.InTime,inTime );
                }

                if (outHight!=0){
                    SharePrefUtil.saveInt(MyApplication.getInstance().getApplicationContext(), Constans.OutHight,outHight );
                }
                if (outTime!=0)
                {
                    SharePrefUtil.saveInt(MyApplication.getInstance().getApplicationContext(), Constans.OutTime ,outTime);
                }
                ToastUtils.showToast("保存成功",3);
                if (main.setFragment == null) {
                    main.setFragment = new SetFragment();
                }
                main.switchContent(main.mContent, main.setFragment);
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

    String[] language;
    String[] rongji_unit;
    String[] zhiliang_unit;
    String[] yewei_unit;


    @Override
    public void onItemSelected(AdapterView<?> adapterView, View view, int index, long l) {
        switch (adapterView.getId()) {
            case R.id.sp_rongji:
                SharePrefUtil.saveString(MyApplication.getInstance().getApplicationContext(), Constans.RongJiUnit, rongji_unit[index]);
                break;
            case R.id.sp_yewei:
                SharePrefUtil.saveString(MyApplication.getInstance().getApplicationContext(), Constans.YeWeiUnit, yewei_unit[index]);
                break;
            case R.id.sp_zhiliang:
                SharePrefUtil.saveString(MyApplication.getInstance().getApplicationContext(), Constans.ZhiLiangUnit, zhiliang_unit[index]);
                break;
            case R.id.sp_yuyan:
                SharePrefUtil.saveString(MyApplication.getInstance().getApplicationContext(), Constans.Language, language[index]);
                break;
        }
    }

    @Override
    public void onNothingSelected(AdapterView<?> adapterView) {

    }
}
