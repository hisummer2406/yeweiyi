package com.sansen.yeweiyi.view;

import android.app.Activity;
import android.os.Bundle;
import android.widget.TextView;

import com.sansen.yeweiyi.R;
import com.sansen.yeweiyi.logic.Const;

/**
 * 自定义圆形进度条对话框
 */
public class CustomProgressDialog extends Activity {

	private String content ="加载数据...";
	private TextView progress_dialog_content;

	

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.custom_progress_dialog);
		overridePendingTransition(0,0);
		initView();
		initData();
		Const.ProgressDialog = CustomProgressDialog.this;
		
	}

	private void initData() {
		progress_dialog_content.setText(content);
	}

	public void setContent(String str) {
		progress_dialog_content.setText(str);
	}

	private void initView() {
		
		progress_dialog_content = (TextView) findViewById(R.id.progress_dialog_content);
	}
	public void deostry(){
		finish();
	}
	@Override
	protected void onPause() {
		// TODO Auto-generated method stub
		super.onPause();
		overridePendingTransition(0,0);
	}
}
