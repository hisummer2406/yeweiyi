package com.sansen.yeweiyi.fragment;

import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.text.method.ScrollingMovementMethod;
import android.util.SparseArray;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.CompoundButton.OnCheckedChangeListener;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.sansen.yeweiyi.R;
import com.sansen.yeweiyi.application.MyApplication;
import com.sansen.yeweiyi.base.BaseFragment;
import com.sansen.yeweiyi.bean.OilCan;
import com.sansen.yeweiyi.db.Sideleakage;
import com.sansen.yeweiyi.listener.SideleakageListener;
import com.sansen.yeweiyi.logic.SideleakageDetection;
import com.sansen.yeweiyi.utils.DateUtils;
import com.sansen.yeweiyi.utils.SharePrefUtil;
import com.sansen.yeweiyi.utils.SystemUtils;

import java.util.ArrayList;
import java.util.List;

public class SideleaFragment extends BaseFragment implements
		OnCheckedChangeListener {

	private ProgressBar pb_progressbar;

	private List<Integer> oilCans;

	private Button celou_bt_begin;
	private Button celou_bt_end;
	private CheckBox celou_cb_1;
	private CheckBox celou_cb_2;
	private CheckBox celou_cb_3;
	private CheckBox celou_cb_4;
	private CheckBox celou_cb_5;
	private CheckBox celou_cb_6;
	private CheckBox celou_cb_7;
	private CheckBox celou_cb_8;
	private CheckBox celou_cb_9;
	private CheckBox celou_cb_10;
	private CheckBox celou_cb_11;
	private CheckBox celou_cb_12;
	private CheckBox celou_cb_13;
	private CheckBox celou_cb_14;
	private CheckBox celou_cb_15;
	private CheckBox celou_cb_16;
	private CheckBox celou_cb_all;
	private View[] checkBoxs;

	TextView celou_tv_result;

	SideleakageDetection sideleakageDetection;

	@Override
	protected View initView(LayoutInflater inflater) {

		return inflater.inflate(R.layout.fragment_celou, null, false);
	}

	public void findView() {
		pb_progressbar = (ProgressBar) rootView
				.findViewById(R.id.celou_pb_progressbar);
		celou_cb_1 = (CheckBox) rootView.findViewById(R.id.celou_cb_1);
		celou_cb_1.setText(SystemUtils.getRsStr(R.string.oiltank)+1);
		celou_cb_2 = (CheckBox) rootView.findViewById(R.id.celou_cb_2);
		celou_cb_2.setText(SystemUtils.getRsStr(R.string.oiltank)+2);
		celou_cb_3 = (CheckBox) rootView.findViewById(R.id.celou_cb_3);
		celou_cb_3.setText(SystemUtils.getRsStr(R.string.oiltank)+3);
		celou_cb_4 = (CheckBox) rootView.findViewById(R.id.celou_cb_4);
		celou_cb_4.setText(SystemUtils.getRsStr(R.string.oiltank)+4);
		celou_cb_5 = (CheckBox) rootView.findViewById(R.id.celou_cb_5);
		celou_cb_5.setText(SystemUtils.getRsStr(R.string.oiltank)+5);
		celou_cb_6 = (CheckBox) rootView.findViewById(R.id.celou_cb_6);
		celou_cb_6.setText(SystemUtils.getRsStr(R.string.oiltank)+6);
		celou_cb_7 = (CheckBox) rootView.findViewById(R.id.celou_cb_7);
		celou_cb_7.setText(SystemUtils.getRsStr(R.string.oiltank)+7);
		celou_cb_8 = (CheckBox) rootView.findViewById(R.id.celou_cb_8);
		celou_cb_8.setText(SystemUtils.getRsStr(R.string.oiltank)+8);
		celou_cb_9 = (CheckBox) rootView.findViewById(R.id.celou_cb_9);
		celou_cb_9.setText(SystemUtils.getRsStr(R.string.oiltank)+9);
		celou_cb_10 = (CheckBox) rootView.findViewById(R.id.celou_cb_10);
		celou_cb_10.setText(SystemUtils.getRsStr(R.string.oiltank)+10);
		celou_cb_11 = (CheckBox) rootView.findViewById(R.id.celou_cb_11);
		celou_cb_11.setText(SystemUtils.getRsStr(R.string.oiltank)+11);
		celou_cb_12 = (CheckBox) rootView.findViewById(R.id.celou_cb_12);
		celou_cb_12.setText(SystemUtils.getRsStr(R.string.oiltank)+12);
		celou_cb_13 = (CheckBox) rootView.findViewById(R.id.celou_cb_13);
		celou_cb_13.setText(SystemUtils.getRsStr(R.string.oiltank)+13);
		celou_cb_14 = (CheckBox) rootView.findViewById(R.id.celou_cb_14);
		celou_cb_14.setText(SystemUtils.getRsStr(R.string.oiltank)+14);
		celou_cb_15 = (CheckBox) rootView.findViewById(R.id.celou_cb_15);
		celou_cb_15.setText(SystemUtils.getRsStr(R.string.oiltank)+15);
		celou_cb_16 = (CheckBox) rootView.findViewById(R.id.celou_cb_16);
		celou_cb_16.setText(SystemUtils.getRsStr(R.string.oiltank)+16);
		
		celou_cb_all = (CheckBox) rootView.findViewById(R.id.celou_cb_all);
		celou_bt_begin = (Button) rootView.findViewById(R.id.celou_bt_begin);
		celou_bt_end = (Button) rootView.findViewById(R.id.celou_bt_end);
		celou_tv_result = (TextView) rootView
				.findViewById(R.id.celou_tv_result);
		celou_tv_result
				.setMovementMethod(ScrollingMovementMethod.getInstance());
		celou_bt_begin.setOnClickListener(this);
		celou_bt_end.setOnClickListener(this);
		celou_bt_end.setEnabled(false);

		celou_cb_1.setOnCheckedChangeListener(this);
		celou_cb_2.setOnCheckedChangeListener(this);
		celou_cb_3.setOnCheckedChangeListener(this);
		celou_cb_4.setOnCheckedChangeListener(this);
		celou_cb_5.setOnCheckedChangeListener(this);
		celou_cb_6.setOnCheckedChangeListener(this);
		celou_cb_7.setOnCheckedChangeListener(this);
		celou_cb_8.setOnCheckedChangeListener(this);
		celou_cb_9.setOnCheckedChangeListener(this);
		celou_cb_10.setOnCheckedChangeListener(this);
		celou_cb_11.setOnCheckedChangeListener(this);
		celou_cb_12.setOnCheckedChangeListener(this);
		celou_cb_13.setOnCheckedChangeListener(this);
		celou_cb_14.setOnCheckedChangeListener(this);
		celou_cb_15.setOnCheckedChangeListener(this);
		celou_cb_16.setOnCheckedChangeListener(this);
		celou_cb_all.setOnCheckedChangeListener(this);

	}

	@Override
	protected void initData(Bundle savedInstanceState) {
		findView();
		oilCans = new ArrayList<Integer>();
		checkBoxs = new View[] { celou_cb_1, celou_cb_2, celou_cb_3,
				celou_cb_4, celou_cb_5, celou_cb_6, celou_cb_7, celou_cb_8,
				celou_cb_9 ,celou_cb_10,celou_cb_11,celou_cb_12,celou_cb_13,celou_cb_14,
				celou_cb_15,celou_cb_16};
	}

	@Override
	protected void processClick(View v) {
		switch (v.getId()) {
		case R.id.celou_bt_begin:
			celou_bt_begin(v);
			break;
		case R.id.celou_bt_end:
			celou_bt_end(v);
			break;
		}

	}

	private void celou_bt_end(View v) {
		pb_progressbar.setProgress(0);
		sideleakageDetection.endDection();

		StringBuilder stringBuilder = new StringBuilder();
		for (Integer index : oilCans) {
			if (MyApplication.alarmStates.containsKey(index)) {

				if (MyApplication.alarmStates.get(index).getConnectError() == 1) {
					Sideleakage sideleakage = new Sideleakage();
					if ("en".equals(SharePrefUtil.getString(main,SharePrefUtil.LANGUAGE,"zh"))){
						stringBuilder.append("No."+index + "tank communication interruption...\n");
						sideleakage.setInfo("No."+index + "tank communication interruption...\n");
					}else if("zh".equals(SharePrefUtil.getString(main,SharePrefUtil.LANGUAGE,"zh"))) {
						stringBuilder.append(index + "号油罐通讯中断...\n");
						sideleakage.setInfo(index + "号油罐通讯中断");
					}else if("th".equals(SharePrefUtil.getString(main,SharePrefUtil.LANGUAGE,"zh"))){
						stringBuilder.append("การหยุดชะงักของการสื่อสารรถถังหมายเลข "+index+"\n");
						sideleakage.setInfo("การหยุดชะงักของการสื่อสารรถถังหมายเลข "+index);
					}

					sideleakage.setTime(DateUtils.getYYYMMddHHmmss());
					dbManager.saveObject(sideleakage);

				} else {
					Sideleakage sideleakage = new Sideleakage();

					if ("en".equals(SharePrefUtil.getString(main,SharePrefUtil.LANGUAGE,"zh"))){
						stringBuilder.append("The No. "+index + " tank ends the test and the tank is normal.\n");
						sideleakage.setInfo("The No. "+index + " tank ends the test and the tank is normal.\n");
					}else if("zh".equals(SharePrefUtil.getString(main,SharePrefUtil.LANGUAGE,"zh"))){
						stringBuilder.append(index + "号油罐结束检测，油罐正常...\n");
						sideleakage.setInfo(index + "号油罐测试正常");
					}else if("th".equals(SharePrefUtil.getString(main,SharePrefUtil.LANGUAGE,"zh"))){
						stringBuilder.append("ถังน้ำมันหมายเลข "+index+" สิ้นสุดการทดสอบและถังน้ำมันเป็นปกติ\n");
						sideleakage.setInfo("การทดสอบรถถังหมายเลข "+index+" เป็นเรื่องปกติ");
					}

					sideleakage.setTime(DateUtils.getYYYMMddHHmmss());
					dbManager.saveObject(sideleakage);
				}
			}

		}
		Message msg = Message.obtain();
		msg.what = 0;
		msg.obj = stringBuilder.toString();
		handler.sendMessage(msg);
	}

	private void celou_bt_begin(View v) {
		if (oilCans.size() > 0) {
			celou_bt_begin.setEnabled(false);
			celou_bt_end.setEnabled(true);
			chekBoxIsEnable(false);
			sideleakageDetection = SideleakageDetection.getInstance(oilCans, 1,
					new SideleakageListener() {
						@Override
						public void onRadio(int radio) {
							pb_progressbar.setProgress(radio);
							if (radio==100){

								celou_bt_end(null);
							}
						}
					});

			StringBuilder stringBuilder = new StringBuilder();
	
			for (Integer index : oilCans) {
				if (MyApplication.alarmStates.containsKey(index)) {

					if (MyApplication.alarmStates.get(index).getConnectError() == 1) {
						if ("en".equals(SharePrefUtil.getString(main,SharePrefUtil.LANGUAGE,"zh"))){
							stringBuilder.append("No."+index + "tank communication interruption...\n");
						}else if("zh".equals(SharePrefUtil.getString(main,SharePrefUtil.LANGUAGE,"zh"))) {
							stringBuilder.append(index + "号油罐通讯中断...\n");
						}else if("th".equals(SharePrefUtil.getString(main,SharePrefUtil.LANGUAGE,"zh"))){
							stringBuilder.append("การหยุดชะงักของการสื่อสารรถถังหมายเลข "+index+"\n");
						}
					} else {
						if ("en".equals(SharePrefUtil.getString(main,SharePrefUtil.LANGUAGE,"zh"))){
							stringBuilder.append("No. "+index+" oil tank inspection...\n");
						}else if ("zh".equals(SharePrefUtil.getString(main,SharePrefUtil.LANGUAGE,"zh"))){
							stringBuilder.append(index + "号油罐检测中...\n");
						}else  if ("th".equals(SharePrefUtil.getString(main,SharePrefUtil.LANGUAGE,"zh"))){
							stringBuilder.append("การตรวจสอบถังน้ำมันหมายเลข "+index+"\n");
						}

					}
				}

			}
			celou_tv_result.setText(stringBuilder.toString());

		} else {
			SystemUtils.showRsToast(R.string.no_select_oilcan);
		}
	}

	@Override
	public void initTitle() {
	}

	Handler handler = new Handler() {
		public void handleMessage(android.os.Message msg) {
			switch (msg.what) {
			case 0:
				celou_tv_result.setText((CharSequence) msg.obj);
				celou_bt_begin.setEnabled(true);
				celou_bt_end.setEnabled(false);
				chekBoxIsEnable(true);
				pb_progressbar.setProgress(0);
				break;

			default:
				break;
			}
		};
	};

	@Override
	public void onCheckedChanged(CompoundButton arg0, boolean arg1) {

		switch (arg0.getId()) {
		case R.id.celou_cb_1:
			if (mOilcanDataManager.getData(1) == null) {
				SystemUtils.showRsToast(R.string.no_set_cur_oilcan);
				celou_cb_1.setChecked(false);
			} else if (arg1) {
				oilCans.add(1);
			} else {
				oilCans.remove(new Integer(1));
			}

			break;
		case R.id.celou_cb_2:
			if (mOilcanDataManager.getData(2) == null) {
				SystemUtils.showRsToast(R.string.no_set_cur_oilcan);
				celou_cb_2.setChecked(false);

			} else if (arg1) {
				oilCans.add(2);
			} else {
				oilCans.remove(new Integer(2));
			}
			break;
		case R.id.celou_cb_3:
			if (mOilcanDataManager.getData(3) == null) {
				SystemUtils.showRsToast(R.string.no_set_cur_oilcan);
				celou_cb_3.setChecked(false);
			} else if (arg1) {
				oilCans.add(3);
			} else {
				oilCans.remove(new Integer(3));
			}
			break;
		case R.id.celou_cb_4:
			if (mOilcanDataManager.getData(4) == null) {
				SystemUtils.showRsToast(R.string.no_set_cur_oilcan);
				celou_cb_4.setChecked(false);
			} else if (arg1) {
				oilCans.add(4);
			} else {
				oilCans.remove(new Integer(4));
			}
			break;
		case R.id.celou_cb_5:
			if (mOilcanDataManager.getData(5) == null) {
				SystemUtils.showRsToast(R.string.no_set_cur_oilcan);
				celou_cb_5.setChecked(false);
			} else if (arg1) {
				oilCans.add(5);
			} else {
				oilCans.remove(new Integer(5));
			}
			break;
		case R.id.celou_cb_6:
			if (mOilcanDataManager.getData(6) == null) {
				SystemUtils.showRsToast(R.string.no_set_cur_oilcan);
				celou_cb_6.setChecked(false);
			} else if (arg1) {
				oilCans.add(6);
			} else {
				oilCans.remove(new Integer(6));
			}
			break;
		case R.id.celou_cb_7:
			if (mOilcanDataManager.getData(7) == null) {
				SystemUtils.showRsToast(R.string.no_set_cur_oilcan);
				celou_cb_7.setChecked(false);
			} else if (arg1) {
				oilCans.add(7);
			} else {
				oilCans.remove(new Integer(7));
			}
			break;
		case R.id.celou_cb_8:
			if (mOilcanDataManager.getData(8) == null) {
				SystemUtils.showRsToast(R.string.no_set_cur_oilcan);
				celou_cb_8.setChecked(false);
			} else if (arg1) {
				oilCans.add(8);
			} else {
				oilCans.remove(new Integer(8));
			}
			break;
		case R.id.celou_cb_9:
			if (mOilcanDataManager.getData(9) == null) {
				SystemUtils.showRsToast(R.string.no_set_cur_oilcan);
				celou_cb_9.setChecked(false);
			} else if (arg1) {
				oilCans.add(9);
			} else {
				oilCans.remove(new Integer(9));
			}
			break;
		case R.id.celou_cb_10:
			if (mOilcanDataManager.getData(10) == null) {
				SystemUtils.showRsToast(R.string.no_set_cur_oilcan);
				celou_cb_10.setChecked(false);
			} else if (arg1) {
				oilCans.add(10);
			} else {
				oilCans.remove(new Integer(10));
			}
			break;
		case R.id.celou_cb_11:
			if (mOilcanDataManager.getData(11) == null) {
				SystemUtils.showRsToast(R.string.no_set_cur_oilcan);
				celou_cb_11.setChecked(false);
			} else if (arg1) {
				oilCans.add(11);
			} else {
				oilCans.remove(new Integer(11));
			}
			break;
		case R.id.celou_cb_12:
			if (mOilcanDataManager.getData(12) == null) {
				SystemUtils.showRsToast(R.string.no_set_cur_oilcan);
				celou_cb_12.setChecked(false);
			} else if (arg1) {
				oilCans.add(12);
			} else {
				oilCans.remove(new Integer(12));
			}
			break;
		case R.id.celou_cb_13:
			if (mOilcanDataManager.getData(13) == null) {
				SystemUtils.showRsToast(R.string.no_set_cur_oilcan);
				celou_cb_13.setChecked(false);
			} else if (arg1) {
				oilCans.add(13);
			} else {
				oilCans.remove(new Integer(13));
			}
			break;
		case R.id.celou_cb_14:
			if (mOilcanDataManager.getData(14) == null) {
				SystemUtils.showRsToast(R.string.no_set_cur_oilcan);
				celou_cb_14.setChecked(false);
			} else if (arg1) {
				oilCans.add(14);
			} else {
				oilCans.remove(new Integer(14));
			}
			break;
		case R.id.celou_cb_15:
			if (mOilcanDataManager.getData(15) == null) {
				SystemUtils.showRsToast(R.string.no_set_cur_oilcan);
				celou_cb_15.setChecked(false);
			} else if (arg1) {
				oilCans.add(15);
			} else {
				oilCans.remove(new Integer(15));
			}
			break;
		case R.id.celou_cb_16:
			if (mOilcanDataManager.getData(16) == null) {
				SystemUtils.showRsToast(R.string.no_set_cur_oilcan);
				celou_cb_16.setChecked(false);
			} else if (arg1) {
				oilCans.add(16);
			} else {
				oilCans.remove(new Integer(16));
			}
			break;
		case R.id.celou_cb_all:

			SparseArray<OilCan> data = mOilcanDataManager.getData();
			CheckBox checkBox;
			int key=0;
			for (int i=0;i<data.size();i++) {
				key = data.keyAt(i);
				checkBox = (CheckBox) checkBoxs[key-1];
				checkBox.setOnCheckedChangeListener(null);
				checkBox.setChecked(arg1);
				checkBox.setOnCheckedChangeListener(this);
				if (arg1) {
					oilCans.add(key);
				}else {
					if (oilCans.size()>key){
						oilCans.remove(key-1);
					}
				}
			}
		
			break;
		}

	}
	public void chekBoxIsEnable(boolean checked){
		for (int i = 0; i<checkBoxs.length; i++) {
			
			checkBoxs[i].setEnabled(checked);
			
		}
		celou_cb_all.setEnabled(checked);
	}
}
