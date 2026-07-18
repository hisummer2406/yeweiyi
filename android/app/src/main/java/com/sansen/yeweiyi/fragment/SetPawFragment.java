package com.sansen.yeweiyi.fragment;

import android.annotation.SuppressLint;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.sansen.yeweiyi.R;
import com.sansen.yeweiyi.application.MyApplication;
import com.sansen.yeweiyi.base.BaseFragment;
import com.sansen.yeweiyi.utils.Constans;
import com.sansen.yeweiyi.utils.SharePrefUtil;
import com.sansen.yeweiyi.utils.ToastUtils;


/**
 * 权限管理
 */
@SuppressLint("ValidFragment")
public class SetPawFragment extends BaseFragment implements  TextWatcher {


	private TextView tv_title,tv_tip;
	private EditText et_psw,et_confirm_psw;
	private LinearLayout ly_saveData,ly_upper_page;
	private SetType setType;
	@Override
	protected View initView(LayoutInflater inflater) {
		return inflater.inflate(R.layout.fragment_paw_set, null);
	}
	public enum  SetType{
		GAOJISET ,SET;
	}
	public SetPawFragment(SetType setType){
		this.setType =setType;
	}
	@Override
	protected void initData(Bundle savedInstanceState) {
		findView();

	}
	@Override
	public void findView() {
		tv_title = rootView.findViewById(R.id.tv_title);
		tv_tip = rootView.findViewById(R.id.tv_tip);
		et_psw = rootView.findViewById(R.id.et_psw);
		et_confirm_psw = rootView.findViewById(R.id.et_confirm_psw);
		ly_saveData=rootView.findViewById(R.id.ly_saveData);
		ly_upper_page=rootView.findViewById(R.id.ly_upper_page);
		ly_saveData.setOnClickListener(this);
		ly_upper_page.setOnClickListener(this);
		et_psw.addTextChangedListener(this);
		et_confirm_psw.addTextChangedListener(this);
	}

	private String psw,confirmPsw;

	@Override
	protected void processClick(View v) {
		switch (v.getId()){
			case R.id.ly_saveData:
				//Log.i("TAG","点击了保存数据");

				if (!psw.isEmpty()&&psw.equals(confirmPsw)){
					if (setType==SetType.SET){
						SharePrefUtil.saveString(MyApplication.getInstance().getApplicationContext(), Constans.SetPWD,et_confirm_psw.getText().toString());
					}
					else {
						SharePrefUtil.saveString(MyApplication.getInstance().getApplicationContext(), Constans.GJPWD,et_confirm_psw.getText().toString());
					}

					if (main.setFragment == null) {
						main.setFragment = new SetFragment();
					}
					main.switchContent(main.mContent, main.setFragment);
				}
				else {
					ToastUtils.showToast("密码或者确认密码不能为空",5);
				}

				break;
			case R.id.ly_upper_page:
				if (main.setFragment == null) {
					main.setFragment = new SetFragment();
				}
				main.switchContent(main.mContent, main.setFragment);
				break;
		}

	}



	@Override
	public void initTitle() {
	
		if (main!=null) {
//			main.tv_left.setText(SystemUtils.getRsStr(R.string.back_page));
//			main.iv_right.setVisibility(View.VISIBLE);
//			main.tv_left.setVisibility(View.VISIBLE);
//			main.iv_right.setImageResource(R.drawable.laststep);
			OnClickListener backSetListener  = new OnClickListener() {
				
				@Override
				public void onClick(View v) {
					
					if (main.setFragment==null) {
						main.setFragment = new SetFragment();
					}
					main.switchContent(main.mContent, main.setFragment);
					
				}
			};
//			main.tv_left.setOnClickListener(backSetListener);
//			main.iv_right.setOnClickListener(backSetListener);

		}
	}

	@Override
	public void onReflushData() {



	}


	@Override
	public void beforeTextChanged(CharSequence charSequence, int i, int i1, int i2) {

	}

	@Override
	public void onTextChanged(CharSequence charSequence, int i, int i1, int i2) {

	}

	@Override
	public void afterTextChanged(Editable editable) {
		if (et_psw.hasFocus()){
			//Log.i("TAG","输入密码"+editable.toString());
			psw=editable.toString().trim();
		}
		else if (et_confirm_psw.hasFocus())
		{
			//Log.i("TAG","确认密码"+editable.toString());
			confirmPsw=editable.toString().trim();
		}

	}
}
