package com.sansen.yeweiyi.view;


import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.text.method.PasswordTransformationMethod;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

import com.sansen.yeweiyi.R;
import com.sansen.yeweiyi.application.MyApplication;
import com.sansen.yeweiyi.utils.SystemUtils;

public class InputDialogActivity extends Activity implements OnClickListener {

	private TextView dialog_msg;
	private EditText dialog_remark;
	private Button dialog_ok;
	private Button dialog_cancel;
	private MyApplication myApplication;

	private String title;
	private String msg;
	private boolean isPsw;
	private boolean isNoEditext;
	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.input_alertdialog);
		overridePendingTransition(0,0);
		myApplication = MyApplication.getInstance();
		dialog_msg = (TextView)findViewById(R.id.main_dialog_title);
		dialog_remark = (EditText)findViewById(R.id.main_dialog_remark);
		dialog_ok = (Button)findViewById(R.id.main_dialog_ok);
		dialog_cancel =(Button)findViewById(R.id.main_dialog_cancel);
		
		Bundle bundleExtra = getIntent().getBundleExtra("inputDialog");
		title = bundleExtra.getString("title");
		msg = bundleExtra.getString("msg");
		isPsw =bundleExtra.getBoolean("ispsw");
		isNoEditext = bundleExtra.getBoolean("isNoEditext");
		if (isPsw) {
			dialog_remark.setTransformationMethod(PasswordTransformationMethod.getInstance());
		}
		if (isNoEditext) {
			dialog_remark.setVisibility(View.GONE);
		}
		setTitle(title);
		dialog_msg.setText(msg);
		
		dialog_ok.setText(SystemUtils.getRsStr(R.string.confirm));
		dialog_cancel.setText(SystemUtils.getRsStr(R.string.cancle));
		dialog_ok.setOnClickListener(this);
		dialog_cancel.setOnClickListener(this);
	}


	@Override
	public void onClick(View v) {
		switch (v.getId()) {
		case R.id.main_dialog_ok:

			
			if (isNoEditext) {
				setResult(500);
				System.out.println("Resutl 500");
			}else {
				Intent intent = new Intent();
				intent.putExtra("value",dialog_remark.getText().toString());
				setResult(200, intent);
			}
			finish();
			break;

		case R.id.main_dialog_cancel:
			setResult(404);
			finish();
			break;
		}
		
	}
	@Override
	protected void onPause() {
		// TODO Auto-generated method stub
		super.onPause();
		overridePendingTransition(0,0);
	}
}
