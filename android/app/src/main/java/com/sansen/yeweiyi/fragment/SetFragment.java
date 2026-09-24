package com.sansen.yeweiyi.fragment;

import android.databinding.Observable;
import android.databinding.ObservableField;
import android.os.Build;
import android.os.Bundle;
import android.support.annotation.RequiresApi;
import android.support.v7.app.AlertDialog;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.AdapterView;
import android.widget.AdapterView.OnItemClickListener;
import android.widget.GridView;
import android.widget.TextView;
import android.widget.Toast;

import com.sansen.yeweiyi.R;
import com.sansen.yeweiyi.activity.MainActivity;
import com.sansen.yeweiyi.adapter.SetAdapter;
import com.sansen.yeweiyi.application.MyApplication;
import com.sansen.yeweiyi.base.BaseFragment;
import com.sansen.yeweiyi.bean.SerialPortEntrity;
import com.sansen.yeweiyi.utils.Constans;
import com.sansen.yeweiyi.utils.PWDTools;
import com.sansen.yeweiyi.utils.SystemUtils;
import com.sansen.yeweiyi.utils.SharePrefUtil;

public class SetFragment extends BaseFragment {

    private GridView set_gridview_info;
    // 图片封装为一个数组SetAdapter
    private int[] sanSenIcon = {R.drawable.set_chuguan,
            R.drawable.set_guanbiao,
            R.drawable.set_mima,
            R.drawable.set_jichu,
            R.drawable.set_jilu,
            R.drawable.set_gaoji,

    };


    @Override
    protected View initView(LayoutInflater inflater) {

        return inflater.inflate(R.layout.fragment_set, null);
    }

    @Override
    public void onDestroy() {
        // Fragment 实例即将销毁
        super.onDestroy();
        SerialPortEntrity.time.removeOnPropertyChangedCallback(callback);
    }

    @Override
    protected void initData(Bundle savedInstanceState) {
        findView();
        SetAdapter setAdapter;
        setAdapter = new SetAdapter(sanSenIcon);
        // 配置适配器
        set_gridview_info.setAdapter(setAdapter);

        set_gridview_info.setOnItemClickListener(new
                                                         OnItemClickListener() {

                                                             @RequiresApi(api = Build.VERSION_CODES.O)
                                                             @Override
                                                             public void onItemClick(AdapterView<?> arg0, View arg1, int index,
                                                                                     long arg3) {

                                                                 //Log.i("打开的索引", "onItemClick: " + index);
                                                                 MainActivity main = (MainActivity) ct;
                                                                 switch (index) {

                                                                     case 0:

                                                                         if (main.oilCanSetFragment == null) {
                                                                             main.oilCanSetFragment = new OilCanSetFragment();

                                                                         } else {
                                                                             main.oilCanSetFragment.onReflushData();
                                                                         }
                                                                         main.switchContent(main.mContent,
                                                                                 main.oilCanSetFragment);


                                                                         break;
//                                                                     case 1:
//                                                                         if (myApplication.getAuthorization().getProbeSet() == 1) {
//                                                                             ProbeSetFragment probeSetFragment = new ProbeSetFragment();
//                                                                             main.switchContent(main.mContent, probeSetFragment);
//                                                                         } else {
//                                                                             SystemUtils
//                                                                                     .showRsToast(R.string.authorization_toast_noauth);
//                                                                         }
//                                                                         break;
                                                                     case 1:
                                                                         if (main.loadingCreateTableFragment == null) {
                                                                             main.loadingCreateTableFragment = new LoadingCreateTableFragment();

                                                                         }
                                                                         main.switchContent(main.mContent,
                                                                                 main.loadingCreateTableFragment);

//                                                                     case 3:
//                                                                         if (myApplication.getAuthorization().getOilcanTable() == 1) {
//                                                                             if (main.oilCanTableFragment == null) {
//                                                                                 main.oilCanTableFragment = new OilCanTableFragment();
//                                                                             } else {
//                                                                                 main.oilCanTableFragment.onReflushData();
//                                                                             }
//                                                                             main.switchContent(main.mContent,
//                                                                                     main.oilCanTableFragment);
//                                                                         } else {
//                                                                             SystemUtils
//                                                                                     .showRsToast(R.string.authorization_toast_noauth);
//                                                                         }
//                                                                         break;
//                                                                     case 4:
//                                                                         if (myApplication.getAuthorization().getFyfManager() == 1) {
//                                                                             JiLuFragment jiLuFragment = new JiLuFragment();
//                                                                             main.switchContent(main.mContent,
//                                                                                     jiLuFragment);
//                                                                         } else {
//                                                                             SystemUtils
//                                                                                     .showRsToast(R.string.authorization_toast_noauth);
//                                                                         }
//                                                                         break;
//                                                                     case 5:
//                                                                         if (myApplication.getAuthorization().getSystemSet() == 1) {
//                                                                             //showInputPsw();
//                                                                             SystemSetFragment systemSetFragment = new SystemSetFragment();
//                                                                             main.switchContent(main.mContent,
//                                                                                     systemSetFragment);
//                                                                         } else {
//                                                                             SystemUtils
//                                                                                     .showRsToast(R.string.authorization_toast_noauth);
//                                                                         }
//                                                                         break;
//                                                                     case 6:
//                                                                         if (myApplication.getAuthorization().getFyfManager() == 1) {
//                                                                             AboutUsFragment aboutUsFragment = new AboutUsFragment();
//                                                                             main.switchContent(main.mContent,
//                                                                                     aboutUsFragment);
//                                                                         } else {
//                                                                             SystemUtils
//                                                                                     .showRsToast(R.string.authorization_toast_noauth);
//                                                                         }
//                                                                         break;
//                                                                     case 7:
//                                                                         if (myApplication.getAuthorization().getDataManager() == 1) {
//
//                                                                             DataManagerListFragment dataManagerListFragment = new DataManagerListFragment();
//
//                                                                             main.switchContent(main.mContent,
//                                                                                     dataManagerListFragment);
//                                                                         } else {
//                                                                             SystemUtils
//                                                                                     .showRsToast(R.string.authorization_toast_noauth);
//                                                                         }
                                                                         break;

                                                                     case 2:
                                                                         if (main.setPawFragment == null) {
                                                                             main.setPawFragment = new SetPawFragment(SetPawFragment.SetType.SET);
                                                                         } else {
                                                                             main.setPawFragment.onReflushData();
                                                                         }
                                                                         main.switchContent(main.mContent,
                                                                                 main.setPawFragment);

                                                                         break;
                                                                     case 3:
                                                                         if (main.setBaseFragment == null) {
                                                                             main.setBaseFragment = new SetBaseFragment();
                                                                         }
                                                                         main.switchContent(main.mContent, main.setBaseFragment);

                                                                         break;
                                                                     case 4:

                                                                         if (main.jiLuFragment == null) {
                                                                             main.jiLuFragment = new JiLuFragment();
                                                                         }
                                                                         main.switchContent(main.mContent, main.jiLuFragment);

                                                                         break;
                                                                     case 5:
                                                                         String pwd = SharePrefUtil.getString(MyApplication.getInstance().getApplicationContext(), Constans.GJPWD, "123456");
                                                                         LoginDialog dialog = new LoginDialog();
                                                                         // Log.i("TAG", "密码: " + PWDTools.getGaoJiSetPwd());
                                                                         if (!"123456".equals(pwd)) {
                                                                             dialog.setOnConfirmListener(input -> {
                                                                                 if (input.equals(pwd) || input.equals(PWDTools.getGaoJiSetPwd())) {

                                                                                     if (main.setSeniorFragment == null) {
                                                                                         main.setSeniorFragment = new SetSeniorFragment();
                                                                                     }
                                                                                     main.switchContent(main.mContent, main.setSeniorFragment);

                                                                                     dialog.dismiss();
                                                                                 } else {
                                                                                     Toast.makeText(main, SystemUtils.getRsStr(R.string.wrong_password_input, input), Toast.LENGTH_SHORT).show();
                                                                                 }

                                                                             });
                                                                             dialog.show(getActivity().getFragmentManager(), "LoginDialog");
                                                                         } else {

                                                                             if (main.setSeniorFragment == null) {
                                                                                 main.setSeniorFragment = new SetSeniorFragment();
                                                                             }
                                                                             main.switchContent(main.mContent, main.setSeniorFragment);

                                                                         }

                                                                         break;


//                                                                     case 11:
//                                                                         SetTCP setTCP = new SetTCP();
//                                                                         main.switchContent(main.mContent, setTCP);
//                                                                         break;

                                                                     default:
                                                                         break;
                                                                 }

                                                             }
                                                         });
    }

    TextView tvTitle, tv_time;

    public void findView() {
        set_gridview_info = (GridView) rootView.findViewById(R.id.set_gridview_info);
        View headerView = rootView.findViewById(R.id.title_bar);

// 再从该 View 中 findViewById 获取内部的 TextView
        tvTitle = headerView.findViewById(R.id.tv_title);
        tv_time = headerView.findViewById(R.id.main_time);
        tvTitle.setText(R.string.shezhi);
        SerialPortEntrity.time.addOnPropertyChangedCallback(callback);
        rootView.findViewById(R.id.ly_backHome).setOnClickListener(v -> main.main_but_main(v));

    }

    Observable.OnPropertyChangedCallback callback = new Observable.OnPropertyChangedCallback() {
        @Override
        public void onPropertyChanged(Observable sender, int propertyId) {
            // 如果需要区分是哪个字段，可通过 propertyId 判断
            // 直接获取当前值
            String newValue = ((ObservableField<String>) sender).get();
            //  Log.d("监听", "新值：" + newValue);
            tv_time.setText(newValue);
        }
    };
    AlertDialog dialog;

    @Override
    protected void processClick(View v) {

    }


    @Override
    public void initTitle() {
        if (main != null) {
        }
    }
}