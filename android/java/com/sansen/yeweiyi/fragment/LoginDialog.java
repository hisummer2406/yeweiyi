package com.sansen.yeweiyi.fragment;

import android.app.DialogFragment;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
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
            if (listener != null) {
                listener.onConfirm(input);
            }
          //  dismiss();
        });

        return view;
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
        }
    }
}
