package com.sansen.yeweiyi.utils;

import android.app.Activity;
import android.graphics.Rect;
import android.view.View;
import android.view.ViewTreeObserver;
import android.widget.FrameLayout;

import java.lang.reflect.Field;

public class AndroidAdjustResizeBugFix {
    private View mChildOfContent;
    private int usableHeightPrevious;
    private int statusBarHeight;
    private FrameLayout.LayoutParams frameLayoutParams;
    private Activity mActivity;

    private AndroidAdjustResizeBugFix(Activity activity) {
        mActivity = activity;
        FrameLayout content = (FrameLayout) activity.findViewById(android.R.id.content);
        mChildOfContent = content.getChildAt(0);
        statusBarHeight = getStatusBarHeight();
        mChildOfContent.getViewTreeObserver().addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
            public void onGlobalLayout() {
                possiblyResizeChildOfContent();
            }
        });
        frameLayoutParams = (FrameLayout.LayoutParams) mChildOfContent.getLayoutParams();
    }
    public static void assistActivity(Activity activity) {
        new AndroidAdjustResizeBugFix(activity);
    }
    private void possiblyResizeChildOfContent() {
        int usableHeightNow = computeUsableHeight();
        if (usableHeightNow != usableHeightPrevious) {
            int usableHeightSansKeyboard = mChildOfContent.getRootView().getHeight();
            int heightDifference = usableHeightSansKeyboard - usableHeightNow;
            if (heightDifference > (usableHeightSansKeyboard / 4)) {
                // keyboard probably just became visible
                // 收缩到扣除键盘遮挡后的可视高度，让被遮挡的输入框/按钮露出来
                frameLayoutParams.height = usableHeightSansKeyboard - heightDifference;
            } else {
                // keyboard probably just became hidden
                frameLayoutParams.height = usableHeightSansKeyboard;
            }
            mChildOfContent.requestLayout();
            usableHeightPrevious = usableHeightNow;
        }
    }
    private int computeUsableHeight() {
        Rect r = new Rect();
        mChildOfContent.getWindowVisibleDisplayFrame(r);
        return r.bottom - r.top + statusBarHeight;
    }
    private int getStatusBarHeight() {
        try {
            Class<?> c = Class.forName("com.android.internal.R$dimen");
            Object obj = c.newInstance();
            Field field = c.getField("status_bar_height");
            int x = Integer.parseInt(field.get(obj).toString());
            int dimensionPixelSize = mActivity.getResources().getDimensionPixelSize(x);
            return dimensionPixelSize;
        } catch (Exception e) {
            // 刻意留空：这里用反射读 com.android.internal.R$dimen 的私有资源，
            // 部分 ROM 上取不到属于预期情况，下面 return 0 即为兜底值。
            // 不打日志是为了避免在这些 ROM 上每次调用都刷一条错误。
        }
        return 0;
    }
}