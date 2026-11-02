package com.sansen.yeweiyi.utils;

import android.support.v4.app.Fragment;
import android.view.View;
import android.view.WindowManager;

/**
 * 给挂载在 MainActivity 上、含较多 EditText 的设置类 Fragment（OilCanSetFragment /
 * SetBaseFragment / SetPawFragment / ThingsBoardSetFragment）复用的软键盘遮挡处理。这些页面
 * 的共同结构是：title_bar + weight=1 的表单内容区 + 紧跟在后面、固定高度的底部操作栏
 * （保存/返回上页之类的按钮）。
 *
 * 只在这类页面用得到——SetSeniorFragment 等其它 MainActivity 挂载的页面要么没有 EditText，
 * 要么本身就没有键盘遮挡的问题，不需要套这层逻辑。
 */
public class KeyboardAdjustHelper {

    private KeyboardAdjustHelper() {
    }

    /**
     * 页面可见期间把 Activity 的 Window 设成 adjustPan，让键盘弹出时表单内容区收缩、
     * 而不是整块盖住输入框。不用在页面隐藏时自己还原——MainActivity.switchContent 在切到
     * 任何页面前都会统一把 softInputMode 复位成系统默认值。
     */
    public static void applyPanWhileVisible(Fragment fragment) {
        if (fragment.getActivity() == null || fragment.getActivity().getWindow() == null) {
            return;
        }
        fragment.getActivity().getWindow().setSoftInputMode(
                WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN
                        | WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN);
    }

    /**
     * 键盘弹出、内容区收缩时，这排固定高度的底部操作栏会跟着被顶到收缩区域最底部，正好卡在
     * 键盘上方。这几个按钮跟当前聚焦的输入框无关，键盘弹出期间没必要强行可见，故键盘弹出时
     * 隐藏，收起后再恢复。
     *
     * @param rootView       页面的根视图，用来监听高度变化判断键盘是否弹出
     * @param actionBarViews 键盘弹出时要隐藏、收起后要恢复显示的底部操作栏视图
     */
    public static void hideActionBarWhileKeyboardVisible(View rootView, View... actionBarViews) {
        rootView.getViewTreeObserver().addOnGlobalLayoutListener(() -> {
            // 参照 AndroidAdjustResizeBugFix 的判定方式：用 getRootView()（DecorView）的高度
            // 作为"没有键盘时的整屏高度"基准——这个值不会随 Fragment 自身的多轮测量/布局波动，
            // 比自己记录"目前见过的最大高度"更稳，不会在页面刚显示、还没测量到最终高度时
            // 把中间态误判成键盘弹出。
            int fullHeight = rootView.getRootView().getHeight();
            int height = rootView.getHeight();
            if (fullHeight <= 0 || height <= 0) {
                return;
            }
            boolean keyboardVisible = height < fullHeight * 3 / 4;
            int visibility = keyboardVisible ? View.GONE : View.VISIBLE;
            for (View actionBarView : actionBarViews) {
                if (actionBarView.getVisibility() != visibility) {
                    actionBarView.setVisibility(visibility);
                }
            }
        });
    }
}
