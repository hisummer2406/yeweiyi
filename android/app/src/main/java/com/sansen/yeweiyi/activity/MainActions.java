package com.sansen.yeweiyi.activity;

import android.os.Build;
import android.support.annotation.RequiresApi;
import android.util.SparseArray;
import android.view.View;
import android.widget.Toast;

import com.sansen.yeweiyi.R;
import com.sansen.yeweiyi.application.MyApplication;
import com.sansen.yeweiyi.base.BaseFragment;
import com.sansen.yeweiyi.bean.OilCan;
import com.sansen.yeweiyi.fragment.LoginDialog;
import com.sansen.yeweiyi.fragment.SetFragment;
import com.sansen.yeweiyi.manager.CommitWorkManager;
import com.sansen.yeweiyi.utils.Constans;
import com.sansen.yeweiyi.utils.PWDTools;
import com.sansen.yeweiyi.utils.SharePrefUtil;
import com.sansen.yeweiyi.utils.SystemUtils;

/**
 * 出入库/交班等业务动作按钮处理与密码校验，从 MainActivity 搬出。持有
 * MainActivity 引用以复用其导航状态（mContent/setFragment/navigator/
 * clearButtonState）——这些字段本身仍归 MainActivity 所有，本类不重复持有，
 * 见 openspec/changes/archive/2026-11-02-split-main-activity §2 关于 mContent/Fragment 缓存字段
 * 外部耦合过广、未随导航机制搬移的决定。
 *
 * <p>4.2 核查：本组搬移的方法均不直接访问 DBDataManager/OilTankRepository/
 * StockRepository，没有需要标注 TODO 的持久化依赖。</p>
 */
public class MainActions {

    private final MainActivity main;

    public MainActions(MainActivity main) {
        this.main = main;
    }

    public void but_daoChuRuKu(View v) {
        String idName = main.getResources().getResourceEntryName(v.getId());
    }

    @RequiresApi(api = Build.VERSION_CODES.O)
    public void main_but_set(View v) {
        // 有点多，需要优化
        String pwd = SharePrefUtil.getString(MyApplication.getInstance().getApplicationContext(), Constans.SetPWD, "123456");
        if (!"123456".equals(pwd)) {
            LoginDialog dialog = new LoginDialog();
            dialog.setOnConfirmListener(input -> {

                if (input.equals(pwd) || input.equals(PWDTools.getSetPwd())) {
                    showInputPsw(main.mainFragment);
                    dialog.dismiss();

                } else {
                    Toast.makeText(main, SystemUtils.getRsStr(R.string.wrong_password_input, input), Toast.LENGTH_SHORT).show();
                }

            });
            dialog.show(main.getFragmentManager(), "LoginDialog");
        } else {
            showInputPsw(main.mainFragment);
        }

    }


    private void showInputPsw(final BaseFragment fragment) {
        if (main.setFragment == null) {
            main.setFragment = new SetFragment();

        }
        main.switchContent(main.mContent, main.setFragment);
        main.clearButtonState();
        main.navigator.setMainButton(false);
    }

    public void but_startJiaoBan(View v) {
        SparseArray<OilCan> oilCan = main.myApplication.getmOilcanDataManager().getData();
        if (main.myApplication.getmOilcanDataManager().getData().size() > 0) {
            CommitWorkManager.getInstance()
                    .commitWork(
                            main.myApplication.getmOilcanDataManager()
                                    .getData());
            SystemUtils.showRsToast(R.string.commitwork_success);
            SharePrefUtil.saveInt(MyApplication.getInstance().getApplicationContext(), Constans.IsStartJiaoBan, 1);

            main.commitWorkRecortFragment.setUIShow(true);
        } else {
            SystemUtils.showRsToast(R.string.commitwork_errpr);
        }
    }

    public void but_endJiaoBan(View v) {
        if (main.myApplication.getmOilcanDataManager().getData().size() > 0) {
            CommitWorkManager.getInstance()
                    .commitWork(
                            main.myApplication.getmOilcanDataManager()
                                    .getData(), "");
            SystemUtils.showRsToast(R.string.commitwork_success);
            SharePrefUtil.saveInt(MyApplication.getInstance().getApplicationContext(), Constans.IsStartJiaoBan, 0);
            main.commitWorkRecortFragment.setUIShow(false);

        } else {
            SystemUtils.showRsToast(R.string.commitwork_errpr);
        }


    }
}
