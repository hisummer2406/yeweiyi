package com.sansen.yeweiyi.view;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.CompoundButton.OnCheckedChangeListener;
import android.widget.GridView;

import com.lidroid.xutils.util.LogUtils;
import com.sansen.yeweiyi.R;
import com.sansen.yeweiyi.application.MyApplication;
import com.sansen.yeweiyi.bean.OilCan;
import com.sansen.yeweiyi.db.OilCanSet;
import com.sansen.yeweiyi.manager.DBDataManager;
import com.sansen.yeweiyi.manager.SoundLightManager;
import com.sansen.yeweiyi.sharepre.manager.SharePreManager;
import com.sansen.yeweiyi.utils.DateUtils;
import com.sansen.yeweiyi.utils.SharePrefUtil;
import com.sansen.yeweiyi.utils.SystemUtils;

import java.util.List;
import java.util.Timer;
import java.util.TimerTask;

public class AlarmOpenControlDialog extends Activity {

	private GridView gridView;
	private List<OilCanSet> lists;
	private MyAdapter myAdapter;
	Button button;
	private Handler handler=new Handler();

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.activity_alarm_open_control_dialog);
		overridePendingTransition(0,0);
		lists = DBDataManager.getInstance().findAllObject(OilCanSet.class);
		gridView = (GridView) findViewById(R.id.dialog_alarm_open_gridview);
		myAdapter = new MyAdapter();
		final Timer timer = new Timer();
		gridView.setAdapter(myAdapter);
		button = findViewById(R.id.sound_close_but);
		button.setOnClickListener(new View.OnClickListener() {
			@Override
			public void onClick(View v) {
				//Log.e("TAG","-------设定要指定任务--------"+SharePreManager.getAlarmTime()*1000);
				SoundLightManager.getInstance().stopAlarm();
				//UpdateState(false);
				timer.schedule(new TimerTask() {
					public void run() {
						handler.post(new Runnable() {
							@Override
							public void run() {
								SharePreManager.saveAlarm(false);
								//Log.e("TAG","-------开启报警--------"+SharePreManager.getAlarmTime()*1000);
								//1UpdateState(true);

							}
						});

						//System.out.println("-------设定要指定任务--------");
					}
				}, SharePreManager.getAlarmTime()*1000*60);// 设定指定的时间time,此处为2000毫秒
			}
		});
	}

	private void UpdateState(boolean isChecked){
		int size = lists.size();
		for (int position = 1; position <= size; position++) {
			int alarmByte = isChecked == true ? 1 : 0;
			OilCanSet oilCanSet = (OilCanSet) MyApplication.getInstance()
					.getmDBManager()
					.findObjectByID(OilCanSet.class, position);
			oilCanSet.setAlarmByte(alarmByte);
			OilCan oilCan = MyApplication.getInstance()
					.getmOilcanDataManager().getData(position);
			if (oilCan != null) {

				oilCan.setAlarmByte(alarmByte);
			}
			myAdapter.oilcanSet.setGmt_modified(DateUtils.getYYYMMddHHmmss());
			MyApplication.getInstance().getmDBManager()
					.UpdateObject(oilCanSet);
			SharePrefUtil.saveBoolean(AlarmOpenControlDialog.this, "isAllAlarm", isChecked);
		}
		reflushData();
	}

	@Override
	public boolean onCreateOptionsMenu(Menu menu) {
		getMenuInflater().inflate(R.menu.alarm_open_control_dialog, menu);
		return true;
	}
	public void reflushData(){
		lists = DBDataManager.getInstance().findAllObject(OilCanSet.class);
		myAdapter.notifyDataSetChanged();
	}

	class MyAdapter extends BaseAdapter implements
			OnCheckedChangeListener {
		@Override
		public int getCount() {
			return 17;
		}

		@Override
		public Object getItem(int position) {
			// TODO Auto-generated method stub
			return null;
		}

		@Override
		public long getItemId(int position) {
			// TODO Auto-generated method stub
			return position;
		}

		public OilCanSet oilcanSet;
		public void updateState(boolean alarmByte){
			SharePrefUtil.saveBoolean(AlarmOpenControlDialog.this, "isAllAlarm", alarmByte);
			reflushData();
		}

		@Override
		public View getView(final int position, View convertView,
							ViewGroup parent) {

			View view = LayoutInflater.from(AlarmOpenControlDialog.this).inflate(
					R.layout.popuwindow_alarm_items, null);
		
			final CheckBox popuwindow_cb_alarm = (CheckBox) view
					.findViewById(R.id.popuwindow_cb_alarm);
			if (position != 16) {
				oilcanSet = lists.get(position);
				int alarmByte = 0;
				if (oilcanSet != null) {

					alarmByte = oilcanSet.getAlarmByte();

					popuwindow_cb_alarm.setChecked(alarmByte == 1);

					if ("en".equals(SharePrefUtil.getString(AlarmOpenControlDialog.this,SharePrefUtil.LANGUAGE,"zh"))){
						popuwindow_cb_alarm.setText("No."+(position + 1)
								+ SystemUtils.getRsStr(R.string.oil_no));
					}else if ("zh".equals(SharePrefUtil.getString(AlarmOpenControlDialog.this,SharePrefUtil.LANGUAGE,"zh"))){
						popuwindow_cb_alarm.setText((position + 1)
								+ SystemUtils.getRsStr(R.string.oil_no));
					}else if ("th".equals(SharePrefUtil.getString(AlarmOpenControlDialog.this,SharePrefUtil.LANGUAGE,"zh"))){
						popuwindow_cb_alarm.setText(SystemUtils.getRsStr(R.string.oil_no)+" "+(position + 1));
					}


					popuwindow_cb_alarm
							.setOnCheckedChangeListener(new OnCheckedChangeListener() {

								@Override
								public void onCheckedChanged(
										CompoundButton buttonView,
										boolean isChecked) {

									int alarmByte = isChecked == true ? 1 : 0;

									OilCanSet oilCanSet = (OilCanSet) MyApplication
											.getInstance()
											.getmDBManager()
											.findObjectByID(OilCanSet.class,
													position + 1);
									oilCanSet.setAlarmByte(alarmByte);
									OilCan oilCan = MyApplication.getInstance()
											.getmOilcanDataManager()
											.getData(position + 1);
									if (oilCan != null) {

										oilCan.setAlarmByte(alarmByte);
									}
									oilcanSet.setGmt_modified(DateUtils.getYYYMMddHHmmss());
									MyApplication.getInstance().getmDBManager()
											.UpdateObject(oilCanSet);
								}
							});
				}

			}

			if (position == lists.size()) {
				popuwindow_cb_alarm.setText(SystemUtils.getRsStr(R.string.all));
				popuwindow_cb_alarm.setChecked(SharePrefUtil.getBoolean(AlarmOpenControlDialog.this,
						"isAllAlarm", false));
				popuwindow_cb_alarm.setOnCheckedChangeListener(this);
			}
			return view;
		}

		@Override
		public void onCheckedChanged(CompoundButton arg0, boolean isChecked) {
			int size = lists.size();
			for (int position = 1; position <= size; position++) {
				int alarmByte = isChecked == true ? 1 : 0;

				OilCanSet oilCanSet = (OilCanSet) MyApplication.getInstance()
						.getmDBManager()
						.findObjectByID(OilCanSet.class, position);
				oilCanSet.setAlarmByte(alarmByte);
				OilCan oilCan = MyApplication.getInstance()
						.getmOilcanDataManager().getData(position);
				if (oilCan != null) {

					oilCan.setAlarmByte(alarmByte);
				}
				oilcanSet.setGmt_modified(DateUtils.getYYYMMddHHmmss());
				MyApplication.getInstance().getmDBManager()
						.UpdateObject(oilCanSet);
				SharePrefUtil.saveBoolean(AlarmOpenControlDialog.this, "isAllAlarm", isChecked);
			}
			reflushData();
		}

	}
	@Override
	protected void onPause() {
		// TODO Auto-generated method stub
		super.onPause();
		overridePendingTransition(0,0);
	}
}
