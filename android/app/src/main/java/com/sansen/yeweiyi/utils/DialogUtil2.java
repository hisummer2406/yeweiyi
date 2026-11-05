package com.sansen.yeweiyi.utils;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.drawable.ColorDrawable;
import android.os.CountDownTimer;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

import com.sansen.yeweiyi.R;

/**
 * Created by liliang on 2020/5/31.
 */
// [范围外-2026-09-23，用户确认] saveTest() 弹出的 view_dialog_test.xml 是真实可达的（OilCanSetFragment
// 连点 item_tv_oilheight 9 次触发，不是死代码——西语翻译覆盖率核查时曾被怀疑是漏标进去的，代码复核
// 确认是真调用），但这是个彩蛋功能，用户确认不需要多语言支持，save/probe_height_offset_mm/
// tank_default_volume_l 这三个字符串不用翻。
public class DialogUtil2 {

    private Context context;
    private WindowManager windowManager;
    private AlertDialog alertDialog;


    public DialogUtil2(final Context context, WindowManager windowManager) {
        this.context = context;
        this.windowManager = windowManager;
        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        alertDialog = builder.create();
        alertDialog.getWindow().setBackgroundDrawable(new ColorDrawable());
    }


    public void dismissDialog(){
        if (alertDialog.isShowing()) {
            alertDialog.dismiss();
        }
    }


    private void showDialog(View view) {
        if (!alertDialog.isShowing()) {
            alertDialog.setView(view);
            alertDialog.show();
        }else {
            alertDialog.dismiss();
            showDialog(view);
        }
    }


    public void saveTest(final String how){
        View view = LayoutInflater.from(context).inflate(R.layout.view_dialog_test, null);
        final EditText height_edit = view.findViewById(R.id.height_edit);
        final EditText volume_edit = view.findViewById(R.id.volume_edit);
        TextView save_text = view.findViewById(R.id.save_text);
        height_edit.setText(SharePrefUtil.getInt(context,"testH" + how,0)+"");
        volume_edit.setText(SharePrefUtil.getInt(context,"testV" + how,0)+"");
        save_text.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                SharePrefUtil.saveInt(context,"testH" + how,height_edit.getText().length() == 0?0:Integer.valueOf(height_edit.getText().toString()));
                SharePrefUtil.saveInt(context,"testV" + how,volume_edit.getText().length() == 0?0:Integer.valueOf(volume_edit.getText().toString()));
                ToastUtils.showToastLong("保存成功");
                dismissDialog();
            }
        });


        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        alertDialog = builder.create();
        alertDialog.getWindow().setBackgroundDrawable(new ColorDrawable());
        alertDialog.setView(view);
        alertDialog.show();
    }



}
