package com.sansen.yeweiyi.fragment;

import android.app.DialogFragment;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;

import com.sansen.yeweiyi.R;

public class LoginDialog extends DialogFragment {
    private EditText etInput;
    private OnConfirmListener listener;

    // 回调接口
    public interface OnConfirmListener {
        void onConfirm(String input);
    }

    public void setOnConfirmListener(OnConfirmListener listener) {
        this.listener = listener;
    }

    @Override
    public View onCreateView( LayoutInflater inflater,  ViewGroup container,
                              Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.dialog_login, container, false);

        etInput = view.findViewById(R.id.et_psw_input);

        Button btnConfirm = view.findViewById(R.id.btn_enter);
        btnConfirm.setOnClickListener(v -> {
            String input = etInput.getText().toString().trim();
            // 调用方（SetFragment/MainActivity）拿到密码后通常会立刻 switchContent 切换
            // Activity 内容，此时如果键盘还占着 etInput 的焦点没被主动收起，Android 并不保证
            // 弹窗 dismiss() 时会同步收起键盘，导致键盘状态遗留到切换后的新页面上，把跟输入
            // 完全无关的页面布局也顶起来。这里在回调 onConfirm 之前先主动收起键盘。
            hideSoftInput();
            if (listener != null) {
                listener.onConfirm(input);
            }
          //  dismiss();
        });
        etInput.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                btnConfirm.performClick();
                return true;
            }
            return false;
        });

        return view;
    }

    private void hideSoftInput() {
        if (getActivity() == null || etInput == null) {
            return;
        }
        InputMethodManager imm = (InputMethodManager) getActivity().getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) {
            imm.hideSoftInputFromWindow(etInput.getWindowToken(), 0);
        }
    }

    @Override
    public void onStart() {
        super.onStart();
        // 设置窗口大小和背景透明
        if (getDialog() != null && getDialog().getWindow() != null) {
            getDialog().getWindow().setLayout(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            getDialog().getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            getDialog().getWindow().setSoftInputMode(
                    WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN
                            | WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN);
        }
    }
}
