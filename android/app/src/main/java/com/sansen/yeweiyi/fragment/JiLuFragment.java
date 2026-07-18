package com.sansen.yeweiyi.fragment;

import android.app.AlertDialog;
import android.content.DialogInterface;
import android.os.Build;
import android.os.Bundle;
import android.support.annotation.RequiresApi;
import android.util.Log;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.sansen.yeweiyi.R;
import com.sansen.yeweiyi.application.MyApplication;
import com.sansen.yeweiyi.base.BaseFragment;
import com.sansen.yeweiyi.db.Alarm;
import com.sansen.yeweiyi.db.CommitWork;
import com.sansen.yeweiyi.db.OilInOut;
import com.sansen.yeweiyi.db.OutStock;
import com.sansen.yeweiyi.db.StockSave;
import com.sansen.yeweiyi.fragment.SetPawFragment.SetType;
import com.sansen.yeweiyi.utils.Constans;
import com.sansen.yeweiyi.utils.SharePrefUtil;
import com.sansen.yeweiyi.utils.Singleton;
import com.sansen.yeweiyi.utils.SystemUtils;


/**
 * 权限管理
 */
public class JiLuFragment extends BaseFragment implements CompoundButton.OnCheckedChangeListener {

    private CheckBox sw_create_alarm;
    private CheckBox sw_del_alarm, sw_clear_alarm;
    private CheckBox sw_create_out,sw_del_out,sw_clear_out;
    private CheckBox sw_create_stock,sw_del_stock,sw_clear_stock;
    private EditText et_time_minute;
    private CheckBox sw_create_in,sw_del_in,sw_clear_in;
    private CheckBox sw_create_shift,sw_del_shift,sw_clear_shift;
    private LinearLayout ly_upper_page;
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
    } @Override
    public void onCheckedChanged(CompoundButton compoundButton, boolean b) {
        switch(compoundButton.getId()){
            case R.id.sw_create_shift:
                SharePrefUtil.saveInt(MyApplication.getInstance().getApplicationContext(), Constans.IsCreatShift,b?1:0);
                Singleton.getInstance().getSetData(Constans.IsCreatShift);
                Log.e("TAG","sw_create_shift");
                break;
            case R.id.sw_del_shift:
                SharePrefUtil.saveInt(MyApplication.getInstance().getApplicationContext(), Constans.IsDelShift,b?1:0);
                Singleton.getInstance().getSetData(Constans.IsDelShift);
                Log.e("TAG","sw_del_shift");
                break;
            case R.id.sw_clear_shift:
                if (Singleton.getInstance().IsDelAlarm) {
                    initDialog(4);
                    builder.show();
                }
                else {
                    //SystemUtils.showRsToast(R.string.authorization_toast_noauth);
                }
                Log.e("TAG","sw_clear_shift");
                break;
            case R.id.sw_create_in:
                SharePrefUtil.saveInt(MyApplication.getInstance().getApplicationContext(), Constans.IsCreatIn,b?1:0);
                Singleton.getInstance().getSetData(Constans.IsCreatIn);
                Log.e("TAG","sw_create_in");
                break;
            case R.id.sw_del_in:
                SharePrefUtil.saveInt(MyApplication.getInstance().getApplicationContext(), Constans.IsDelIn,b?1:0);
                Singleton.getInstance().getSetData(Constans.IsDelIn);
                Log.e("TAG","sw_del_in");
                break;
            case R.id.sw_clear_in:
                if (Singleton.getInstance().IsDelAlarm) {
                    initDialog(0);
                    builder.show();
                }
                else {
                    //SystemUtils.showRsToast(R.string.authorization_toast_noauth);
                }


                Log.e("TAG","sw_clear_in");
                break;
            case R.id.sw_create_stock:
                SharePrefUtil.saveInt(MyApplication.getInstance().getApplicationContext(), Constans.IsCreatStock,b?1:0);
                Singleton.getInstance().getSetData(Constans.IsCreatStock);
                Log.e("TAG","sw_create_stock");
                break;
            case R.id.sw_del_stock:
                SharePrefUtil.saveInt(MyApplication.getInstance().getApplicationContext(), Constans.IsDelStock,b?1:0);
                Singleton.getInstance().getSetData(Constans.IsDelStock);
                Log.e("TAG","sw_del_stock");
                break;
            case R.id.sw_clear_stock:
                if (Singleton.getInstance().IsDelAlarm) {
                    initDialog(3);
                    builder.show();
                }
                else {
                    //SystemUtils.showRsToast(R.string.authorization_toast_noauth);
                }
                Log.e("TAG","sw_clear_stock");
                break;
            case R.id.sw_clear_out:
                if (Singleton.getInstance().IsDelAlarm) {
                    initDialog(2);
                    builder.show();
                }
                else {
                    //SystemUtils.showRsToast(R.string.authorization_toast_noauth);
                }
                Log.e("TAG","sw_clear_out");
                break;
            case R.id.sw_create_out:
                SharePrefUtil.saveInt(MyApplication.getInstance().getApplicationContext(), Constans.IsCreateOut,b?1:0);
                Singleton.getInstance().getSetData(Constans.IsCreateOut);
                Log.e("TAG","sw_create_out");
                break;
            case R.id.sw_del_out:
                SharePrefUtil.saveInt(MyApplication.getInstance().getApplicationContext(), Constans.IsDelOut,b?1:0);
                Singleton.getInstance().getSetData(Constans.IsDelOut);
                Log.e("TAG","sw_del_out");
                break;
            case R.id.sw_create_alarm:
                SharePrefUtil.saveInt(MyApplication.getInstance().getApplicationContext(), Constans.IsCreateAlarm,b?1:0);
                Singleton.getInstance().getSetData(Constans.IsCreateAlarm);
                Log.e("TAG","sw_create_alarm");
                break;
            case R.id.sw_del_alarm:
                SharePrefUtil.saveInt(MyApplication.getInstance().getApplicationContext(), Constans.IsDelAlarm,b?1:0);
                Singleton.getInstance().getSetData(Constans.IsDelAlarm);
                Log.e("TAG","sw_del_alarm");
                break;
            case R.id.sw_clear_alarm:
                if (Singleton.getInstance().IsDelAlarm) {
                    initDialog(1);
                    builder.show();
                }
                else {
                    //SystemUtils.showRsToast(R.string.authorization_toast_noauth);
                }

                Log.e("TAG","sw_clear_alarm");
                break;

        }

    }
    AlertDialog.Builder builder;
    private void initDialog( int position) {
        builder = new AlertDialog.Builder(ct);
        builder.setTitle(SystemUtils.getRsStr(R.string.isDeleteRecord));
        builder.create();
        builder.setPositiveButton(SystemUtils.getRsStr(R.string.confirm),
                new DialogInterface.OnClickListener() {

                    @Override
                    public void onClick(DialogInterface dialog, int which) {

                        if (position==0){
                            dbManager.delteAllInOilRecord(OilInOut.class);
                        }
                        else if (position==1){
                            dbManager.delteAllInOilRecord(Alarm.class);
                        }
                        else if (position==2){
                            dbManager.delteAllInOilRecord(OutStock.class);
                        }
                        else if (position==3){
                            dbManager.delteAllInOilRecord(StockSave.class);
                        }
                        else if (position==3){
                            dbManager.delteAllInOilRecord(CommitWork.class);
                        }


                    }
                });


        builder.setNegativeButton(SystemUtils.getRsStr(R.string.cancle),
                new DialogInterface.OnClickListener() {

                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        // TODO Auto-generated method stub

                    }
                });

        // 修改消息字体大小（需要 android.R.id.message）
        TextView msgView = builder.show().findViewById(android.R.id.message);
        if (msgView != null) {
            msgView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        }

// 修改按钮字体大小（需要 android.R.id.button1、button2...）
        Button btnPositive = builder.show().getButton(AlertDialog.BUTTON_POSITIVE);
        if (btnPositive != null) {
            btnPositive.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        }

    }

    @Override
    public void findView() {
        sw_create_shift = rootView.findViewById(R.id.sw_create_shift);
        sw_del_shift = rootView.findViewById(R.id.sw_del_shift);
        sw_clear_shift = rootView.findViewById(R.id.sw_clear_shift);
        sw_create_shift.setOnCheckedChangeListener(this);
        sw_del_shift.setOnCheckedChangeListener(this);
        sw_clear_shift.setOnCheckedChangeListener(this);
        sw_create_shift.setChecked(SharePrefUtil.getInt(MyApplication.getInstance().getApplicationContext(), Constans.IsCreatShift,0)==0?false:true);
        sw_del_shift.setChecked(SharePrefUtil.getInt(MyApplication.getInstance().getApplicationContext(), Constans.IsDelShift,0)==0?false:true);
        sw_clear_shift.setChecked(false);

        sw_create_in = rootView.findViewById(R.id.sw_create_in);
        sw_del_in = rootView.findViewById(R.id.sw_del_in);
        sw_clear_in = rootView.findViewById(R.id.sw_clear_in);
        sw_create_in.setOnCheckedChangeListener(this);
        sw_del_in.setOnCheckedChangeListener(this);
        sw_clear_in.setOnCheckedChangeListener(this);
        sw_create_in.setChecked(SharePrefUtil.getInt(MyApplication.getInstance().getApplicationContext(), Constans.IsCreatIn,0)==0?false:true);
        sw_del_in.setChecked(SharePrefUtil.getInt(MyApplication.getInstance().getApplicationContext(), Constans.IsDelIn,0)==0?false:true);
        sw_clear_in.setChecked(false);


        et_time_minute=rootView.findViewById(R.id.et_time_minute);
        sw_create_stock = rootView.findViewById(R.id.sw_create_stock);
        sw_del_stock = rootView.findViewById(R.id.sw_del_stock);
        sw_clear_stock = rootView.findViewById(R.id.sw_clear_stock);
        sw_del_stock.setOnCheckedChangeListener(this);
        sw_create_stock.setOnCheckedChangeListener(this);
        sw_clear_stock.setOnCheckedChangeListener(this);
        sw_del_stock.setChecked(SharePrefUtil.getInt(MyApplication.getInstance().getApplicationContext(), Constans.IsCreatStock,0)==0?false:true);
        sw_create_stock.setChecked(SharePrefUtil.getInt(MyApplication.getInstance().getApplicationContext(), Constans.IsDelStock,0)==0?false:true);
        sw_clear_stock.setChecked(false);


        sw_create_alarm = rootView.findViewById(R.id.sw_create_alarm);
        sw_del_alarm = rootView.findViewById(R.id.sw_del_alarm);
        sw_clear_alarm = rootView.findViewById(R.id.sw_clear_alarm);
        sw_del_alarm.setOnCheckedChangeListener(this);
        sw_clear_alarm.setOnCheckedChangeListener(this);
        sw_create_alarm.setOnCheckedChangeListener(this);
        sw_create_alarm.setChecked(SharePrefUtil.getInt(MyApplication.getInstance().getApplicationContext(), Constans.IsCreateAlarm,0)==0?false:true);
        sw_clear_alarm.setChecked(false);
        sw_del_alarm.setChecked(SharePrefUtil.getInt(MyApplication.getInstance().getApplicationContext(), Constans.IsDelAlarm,0)==0?false:true);

        sw_create_out = rootView.findViewById(R.id.sw_create_out);
        sw_del_out = rootView.findViewById(R.id.sw_del_out);
        sw_clear_out = rootView.findViewById(R.id.sw_clear_out);
        sw_create_out.setOnCheckedChangeListener(this);
        sw_del_out.setOnCheckedChangeListener(this);
        sw_clear_out.setOnCheckedChangeListener(this);
        sw_create_out.setChecked(SharePrefUtil.getInt(MyApplication.getInstance().getApplicationContext(), Constans.IsCreateOut,0)==0?false:true);
        sw_create_out.setChecked(SharePrefUtil.getInt(MyApplication.getInstance().getApplicationContext(), Constans.IsDelOut,0)==0?false:true);
        sw_clear_out.setChecked(false);

        ly_upper_page=rootView.findViewById(R.id.ly_upper_page);
        ly_upper_page.setOnClickListener(this);

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
