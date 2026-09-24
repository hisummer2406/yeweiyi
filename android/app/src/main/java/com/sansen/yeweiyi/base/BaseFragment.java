package com.sansen.yeweiyi.base;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.drawable.ColorDrawable;
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
import android.widget.LinearLayout;
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
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;

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
			String path= Environment.getExternalStorageDirectory().toString() + "/zhongChengDrawable/logo.png";
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
	
	// [不可达-2026-09-21] 这个重载、下面的 getSaveListener()/私有 showInputDialog() 全仓库零调用者，
	// 没有任何子类真正用到（西语翻译覆盖率核查时发现）。input_alertdialog.xml 的字符串资源、
	// 以及 main_toast_saveerror/input_remarks/main_toast_savesuccess 这几个字符串不用优先翻。
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
					// [已屏蔽-2026-09-23] main_toast_saveerror 已删除（getSaveListener() 全仓库零调用者，不可达）。
					// SystemUtils.showRsToast(R.string.main_toast_saveerror);
				}
			}
		};
		return saveListener;
	}
	
	
	
	

	private void showInputDialog() {
		final SaveOilManager saveOilManager = myApplication.getmSaveOilManager();
		LayoutInflater inflater = LayoutInflater.from(ct);
		View layout = inflater.inflate(R.layout.input_alertdialog, null);
		final AlertDialog dialog = new AlertDialog.Builder(ct).create();
	
		dialog.setView((((Activity) ct).getLayoutInflater().inflate(R.layout.input_alertdialog, null)));
		dialog.show();
		dialog.getWindow().setContentView(layout);

		// 3. 标题
		TextView dialog_msg = (TextView) layout.findViewById(R.id.main_dialog_title);
		// [已屏蔽-2026-09-23] input_remarks 已删除（showInputDialog() 全仓库零调用者，不可达）。
		// dialog_msg.setText(SystemUtils.getRsStr(R.string.input_remarks));
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
		// 1. 使用 Activity 的 Context 而非 Application Context
		View view = LayoutInflater.from(main).inflate(R.layout.custom_toast, null);
		dialog = new AlertDialog.Builder(main, R.style.Transparent)
				.setView(view)
				.create();

		// 2. 设置窗口属性（宽高建议用 dp 转 px）
		Window window = dialog.getWindow();
		if (window != null) {
			WindowManager.LayoutParams params = window.getAttributes();
			params.gravity = Gravity.CENTER;
			// 将 dp 转换为 px
			int width = (int) (300 * main.getResources().getDisplayMetrics().density);
			int height = (int) (300 * main.getResources().getDisplayMetrics().density);
			params.width = width;
			params.height = height;
			window.setAttributes(params);

			// 3. 不设置特殊窗口类型，使用默认类型即可
			// window.setType(WindowManager.LayoutParams.TYPE_SYSTEM_ERROR);  // 删除这行

			// 背景透明（如果 Transparent 主题已处理，此行可选）
			window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
		}
		   
	}
	public void showWindDialog(){
		if (dialog != null && !dialog.isShowing()) {
			dialog.setCancelable(false);
			dialog.show();
		}
	}
	Timer timer = new Timer();
	public void showWindDialog(int time){

		if (dialog != null && !dialog.isShowing()) {
			dialog.setCancelable(false);
			dialog.show();
		}
		timer.schedule(new TimerTask() {
			@Override
			public void run() {
				// 后台线程
				closeWindowDialog();
			}
		}, time);


	}
	public void closeWindowDialog(){
		if (dialog != null ) {
			dialog.setCancelable(true);
			dialog.dismiss();
		}
	}

	/**
	 * 按给定 TextView 的字体/字号，实际测量一组文案里最长的那条需要多宽，返回刚好能容纳它的宽度
	 * （含左右各 paddingDp 的余量），并且不超过 maxWidthDp。
	 *
	 * 用于"同一列多个 label 需要统一宽度、且不因中英文长度差异换行"的场景——宽度是按当前语言
	 * 实际渲染出来的文案量出来的，不需要为每种语言各猜一个固定 dp 值。
	 *
	 * @param sampleTextView 提供字体/字号信息的样板 TextView（这一列随便一个即可，只取它的 Paint）
	 * @param texts          这一列所有 label 的实际文案
	 * @param paddingDp      文案两侧预留的余量（dp）
	 * @param maxWidthDp     宽度上限（dp），防止某条异常长的文案把布局撑爆；&lt;= 0 表示不限制
	 * @return               这一列应该统一设置的宽度（px），可以直接赋给 LayoutParams.width
	 */
	protected int measureMaxLabelWidth(TextView sampleTextView, List<String> texts, float paddingDp, float maxWidthDp) {
		Paint paint = sampleTextView.getPaint();
		float maxTextWidth = 0f;
		if (texts != null) {
			for (String text : texts) {
				if (text == null) {
					continue;
				}
				float w = paint.measureText(text);
				if (w > maxTextWidth) {
					maxTextWidth = w;
				}
			}
		}
		float density = ct.getResources().getDisplayMetrics().density;
		int width = (int) Math.ceil(maxTextWidth + paddingDp * density);
		if (maxWidthDp > 0) {
			width = Math.min(width, (int) (maxWidthDp * density));
		}
		return width;
	}

	/**
	 * 把一组"外层 LinearLayout(width=0dp, weight&gt;0) 包一个 label TextView"的列统一成同一个
	 * 固定宽度：宽度按 labelTextViews 里最长的文案实际测量得出，应用在 wrapperViews（label 外层的
	 * LinearLayout）上——把 0dp+weight 换成测量出来的固定宽度、weight 清零。
	 *
	 * 之所以要改外层 wrapper 而不是直接改 TextView 自己的宽度：TextView 是 0dp+weight 的
	 * LinearLayout 的子 View，宽度由外层的 weight 分配决定，只改 TextView 自身宽度不会让外层
	 * 分到更多空间，文案会直接溢出/被裁切。改外层宽度后，同一行里另一个 weight&gt;0 的兄弟
	 * （值列）会自动吃掉多出来的剩余空间，原有"值列占满剩余空间"的弹性布局不受影响。
	 *
	 * 对应的 label TextView 在 XML 里要用 layout_width="match_parent"，靠 gravity="right" 在
	 * 外层给定的宽度里右对齐——而不是自己再声明一个固定宽度。
	 *
	 * @param wrapperViews    label 外层的 LinearLayout（0dp + weight），和 labelTextViews 一一对应
	 * @param labelTextViews  wrapperViews 里包着的 label TextView，和 wrapperViews 一一对应
	 * @param paddingDp       文案两侧预留的余量（dp）
	 * @param maxWidthDp      宽度上限（dp），防止某条异常长的文案把布局撑爆；&lt;= 0 表示不限制
	 */
	protected void unifyWeightedLabelWidth(List<? extends View> wrapperViews, List<TextView> labelTextViews, float paddingDp, float maxWidthDp) {
		if (wrapperViews == null || labelTextViews == null || wrapperViews.isEmpty()
				|| wrapperViews.size() != labelTextViews.size()) {
			return;
		}
		List<String> texts = new java.util.ArrayList<>();
		for (TextView tv : labelTextViews) {
			texts.add(tv.getText() == null ? null : tv.getText().toString());
		}
		int width = measureMaxLabelWidth(labelTextViews.get(0), texts, paddingDp, maxWidthDp);
		for (View wrapper : wrapperViews) {
			ViewGroup.LayoutParams lp = wrapper.getLayoutParams();
			lp.width = width;
			if (lp instanceof LinearLayout.LayoutParams) {
				((LinearLayout.LayoutParams) lp).weight = 0f;
			}
			wrapper.setLayoutParams(lp);
		}
	}
}
