package com.sansen.yeweiyi.base;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.os.Environment;
import android.support.v4.app.Fragment;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;

import com.baidu.mobstat.StatService;
import com.sansen.yeweiyi.R;
import com.sansen.yeweiyi.activity.MainActivity;
import com.sansen.yeweiyi.application.MyApplication;
import com.sansen.yeweiyi.manager.DBDataManager;
import com.sansen.yeweiyi.manager.OilcanDataManager;
import com.sansen.yeweiyi.manager.SaveOilManager;
import com.sansen.yeweiyi.utils.SystemUtils;

import java.io.File;

public abstract class BaseFragment extends Fragment implements OnClickListener {
	protected Context ct;
	public View rootView;
	public MyApplication myApplication;
	public OilcanDataManager mOilcanDataManager;
	public DBDataManager dbManager;
	public MainActivity main;
	public BaseActivity baseActivity;

	
	@Override
	public void onCreate(Bundle savedInstanceState) {

		super.onCreate(savedInstanceState);
		ct = getActivity();
		main = (MainActivity) ct;
		baseActivity =main;
		initWindowDialog();
	}
	
	public abstract void  initTitle();
	@Override
	public void onResume() {
		// TODO Auto-generated method stub
		super.onResume();
		StatService.onResume(this);	

	}


	@Override
	public void onPause() {
		// TODO Auto-generated method stub
		super.onPause();
		StatService.onPause(this);	

	}

	@Override
	public View onCreateView(LayoutInflater inflater, ViewGroup container,
							 Bundle savedInstanceState) {
		rootView = initView(inflater);
		ImageView sasen_logo = (ImageView) rootView.findViewById(R.id.bar_iv_icon);
		if (sasen_logo!=null){
			String path= Environment.getExternalStorageDirectory().toString() + "/sanSenDrawable/logo.png";
			File mFile=new File(path);
			//若该文件存在
			if (mFile.exists()) {
				Bitmap bitmap= BitmapFactory.decodeFile(path);
				sasen_logo.setImageBitmap(bitmap);
			}
		}
		return rootView;
	}


	@Override
	public void onActivityCreated(Bundle savedInstanceState) {

		super.onActivityCreated(savedInstanceState);
		myApplication = MyApplication.getInstance();
		mOilcanDataManager = myApplication.getmOilcanDataManager();
		dbManager = myApplication.getmDBManager();
		initData(savedInstanceState);
		initTitle();
	}

	protected AlertDialog inputDialog;

	

	public View getRootView() {
		return rootView;
	}
//
	@Override
	public void onClick(View arg0) {
		processClick(arg0);
	}
	
	public TextView showInputDialog(String title, String leftButText, String rightButText, OnClickListener leftClickListener, OnClickListener rightClickListener ) {
		
		// 1. 布局文件转换为View对象
		LayoutInflater inflater = LayoutInflater.from(ct);
		View layout = inflater.inflate(R.layout.input_alertdialog, null);

		inputDialog = new AlertDialog.Builder(ct).create();
	
		inputDialog.setView((((Activity) ct).getLayoutInflater().inflate(R.layout.input_alertdialog, null)));
		inputDialog.show();
		inputDialog.getWindow().setContentView(layout);
		
		// 3. 标题
		TextView dialog_msg = (TextView) layout.findViewById(R.id.main_dialog_title);
		dialog_msg.setText(title);
		// 4.对话框
		final EditText dialog_remark = (EditText) layout.findViewById(R.id.main_dialog_remark);

		// 4. 确定按钮
		Button btnOK = (Button) layout.findViewById(R.id.main_dialog_ok);
		btnOK.setText(leftButText);
		btnOK.setOnClickListener(leftClickListener);

		// 5. 取消按钮
		Button btnCancel = (Button) layout.findViewById(R.id.main_dialog_cancel);
		btnCancel.setText(rightButText);
		btnCancel.setOnClickListener(rightClickListener);
		return dialog_remark;
	}
	
	
	
	
	public void updateData() {
		OilcanDataManager.getInstance(null).refresh();
	}

	protected abstract View initView(LayoutInflater inflater);

	protected abstract void initData(Bundle savedInstanceState);

	protected abstract void processClick(View v);
	
	public String getRs(int id){
		return SystemUtils.getRsStr(id);
		
	}
	/**
	 * 隐藏键盘
	 */
	public boolean onTouchEvent(MotionEvent event) {
		if(event.getAction() == MotionEvent.ACTION_DOWN){
		     if(main.getCurrentFocus()!=null && main.getCurrentFocus().getWindowToken()!=null){
		    	 main.manager.hideSoftInputFromWindow(main.getCurrentFocus().getWindowToken(), InputMethodManager.HIDE_NOT_ALWAYS);
		     }  
		  }  
		  return main.onTouchEvent(event);  
	};
	
	public OnClickListener getUserListener(){
		OnClickListener userClick= new OnClickListener() {
			
			@Override
			public void onClick(View v) {
				if (myApplication.getUser()!=null) {
					//跳转到用户界面
//					if (v.getId() == R.id.bar_imbut_left || v.getId() == R.id.bar_tv_left) {
//							UserManagerFragment userManagerFragment = new UserManagerFragment();
//
//						main.switchContent(main.mContent,userManagerFragment);
//					}
					main.tempButton.setEnabled(true);
				}else {

				}
				
			}
		};
		return userClick;
	}
	public OnClickListener getSaveListener(){
		OnClickListener saveListener  = new OnClickListener() {
			
			@Override
			public void onClick(View v) {

				if (mOilcanDataManager.getData().size() > 0) {
					showInputDialog();
				} else {
					SystemUtils.showRsToast(R.string.main_toast_saveerror);
				}
			}
		};
		return saveListener;
	}
	
	
	
	

	private void showInputDialog() {
		final SaveOilManager saveOilManager = myApplication.getmSaveOilManager();
		// 1. 布局文件转换为View对象
		LayoutInflater inflater = LayoutInflater.from(ct);
		View layout = inflater.inflate(R.layout.input_alertdialog, null);

		// 2. 新建对话框对象
		final AlertDialog dialog = new AlertDialog.Builder(ct).create();
	
		dialog.setView((((Activity) ct).getLayoutInflater().inflate(R.layout.input_alertdialog, null)));
		dialog.show();
		dialog.getWindow().setContentView(layout);

		// 3. 标题
		TextView dialog_msg = (TextView) layout.findViewById(R.id.main_dialog_title);
		dialog_msg.setText(SystemUtils.getRsStr(R.string.input_remarks));
		// 4.对话框
		final EditText dialog_remark = (EditText) layout.findViewById(R.id.main_dialog_remark);

		// 4. 确定按钮
		Button btnOK = (Button) layout.findViewById(R.id.main_dialog_ok);
		btnOK.setOnClickListener(new OnClickListener() {
			
			@Override
			public void onClick(View v) {
				saveOilManager.SaveOilDB(dialog_remark.getText().toString());
				SystemUtils.showRsToast(R.string.main_toast_savesuccess);
				dialog.dismiss();
			}

		});

		// 5. 取消按钮
		Button btnCancel = (Button) layout.findViewById(R.id.main_dialog_cancel);
		btnCancel.setOnClickListener(new OnClickListener()
		{

			@Override
			public void onClick(View v)
			{
				saveOilManager.SaveOilDB("");
				SystemUtils.showRsToast(R.string.main_toast_savesuccess);
				dialog.dismiss();
			}
			
		});
	
	}
	public void onReflushData() {
	
		
	}
	public void findView() {
	
		
	}
	Dialog dialog ;
	public void initWindowDialog(){
		View view = LayoutInflater.from(main).inflate(R.layout.custom_progress_dialog, null);
		dialog = new AlertDialog.Builder(main.getApplicationContext(), R.style.Transparent)
		.setView(view)  
		.create();  
		   
		Window window = dialog.getWindow();
		WindowManager.LayoutParams params = window.getAttributes();
		params.gravity = Gravity.CENTER;
		params.width = 300;  
		params.height = 300;  
		window.setAttributes(params);  
		window.setType(WindowManager.LayoutParams.TYPE_SYSTEM_ERROR);
		   
	}
	public void showWindDialog(){
		
		dialog.setCancelable(false);  
		dialog.show();  
	}
	public void closeWindowDialog(){
		dialog.setCancelable(true); 
		dialog.dismiss();
	}
}
