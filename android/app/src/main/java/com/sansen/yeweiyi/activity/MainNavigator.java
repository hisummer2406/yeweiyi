package com.sansen.yeweiyi.activity;

import android.support.v4.app.FragmentManager;
import android.support.v4.app.FragmentTransaction;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;

import com.sansen.yeweiyi.R;
import com.sansen.yeweiyi.base.BaseFragment;
import com.sansen.yeweiyi.fragment.MainFragment;

/**
 * Fragment 切换机制（事务、容器 margin 规则、底部导航栏显隐），从 MainActivity
 * 搬出。{@code mContent} 与各 Fragment 缓存字段仍留在 MainActivity——外部有
 * 57+ 处直接读写这些字段（如 {@code main.switchContent(main.mContent, main.xxxFragment)}），
 * 搬移会波及全项目 fragment/ 包下数十个文件，超出本组"搬 Fragment 切换机制"的范围；
 * MainActivity.switchContent 保留为委托方法，外部调用方式不变。
 * 见 openspec/changes/archive/2026-11-02-split-main-activity §2。
 */
public class MainNavigator {

    private final FragmentManager fragmentManager;
    private final LinearLayout radioBar;
    private final RelativeLayout.LayoutParams params;

    public MainNavigator(FragmentManager fragmentManager, FrameLayout fragmentContainer, LinearLayout radioBar) {
        this.fragmentManager = fragmentManager;
        this.radioBar = radioBar;
        RelativeLayout.LayoutParams p = (RelativeLayout.LayoutParams) fragmentContainer.getLayoutParams();
        if (p == null) {
            p = new RelativeLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        this.params = p;
    }

    public void setDefaultFragment(BaseFragment mainFragment) {
        FragmentTransaction transaction = fragmentManager.beginTransaction();
        transaction.add(R.id.fragment_container, mainFragment).commit();
    }

    /**
     * @return 是否真正发生了切换（from != to）。调用方（MainActivity）在返回
     * true 时应把自己的 mContent 更新为 to——这个字段仍由调用方持有。
     */
    public boolean switchContent(BaseFragment from, BaseFragment to) {
        if (to instanceof MainFragment) {
            params.addRule(RelativeLayout.BELOW, R.id.title_bar);
            params.addRule(RelativeLayout.ABOVE, R.id.radio_bar);
        } else {
            params.removeRule(RelativeLayout.BELOW);
            params.removeRule(RelativeLayout.ABOVE);
        }

        boolean changed = from != to;
        if (changed) {
            FragmentTransaction transaction = fragmentManager.beginTransaction();
            if (!to.isAdded()) { // 先判..断是否被add过
                transaction.hide(from).add(R.id.fragment_container, to)
                        .commit(); // 隐藏当前的fragment，add下一个到Activity中
            } else {
                transaction.hide(from).show(to).commit(); // 隐藏当前的fragment，显示下一个
            }
        }
        to.initTitle();
        return changed;
    }

    public void setMainButton(boolean isShow) {
        radioBar.setVisibility(isShow ? View.VISIBLE : View.INVISIBLE);
    }
}
