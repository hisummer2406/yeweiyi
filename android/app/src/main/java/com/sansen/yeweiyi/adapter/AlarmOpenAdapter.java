package com.sansen.yeweiyi.adapter;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.CompoundButton.OnCheckedChangeListener;

import com.sansen.yeweiyi.R;
import com.sansen.yeweiyi.application.MyApplication;
import com.sansen.yeweiyi.bean.OilCan;
import com.sansen.yeweiyi.db.OilCanSet;
import com.sansen.yeweiyi.utils.DateUtils;
import com.sansen.yeweiyi.utils.SharePrefUtil;
import com.sansen.yeweiyi.utils.SystemUtils;

import java.util.List;

public class AlarmOpenAdapter extends BaseAdapter implements
		OnCheckedChangeListener {

	private List<OilCanSet> lists;
	private Context ct;

	public AlarmOpenAdapter(Context ct) {
		this.ct = ct;
	}

	public void setData(List<OilCanSet> lists) {
		this.lists = lists;
	}

	@Override
	public int getCount() {
		return 10;
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

	private OilCanSet oilcanSet;

	@Override
	public View getView(final int position, View convertView, ViewGroup parent) {

		View view = LayoutInflater.from(ct).inflate(
				R.layout.popuwindow_alarm_items, null);
		view.setPadding(5,2, 5, 2);
		final CheckBox popuwindow_cb_alarm = (CheckBox) view
				.findViewById(R.id.popuwindow_cb_alarm);
		if (position != 9) {
			oilcanSet = lists.get(position);
			int alarmByte = 0;
			if (oilcanSet != null) {

				alarmByte = oilcanSet.getAlarmByte();

				popuwindow_cb_alarm.setChecked(alarmByte == 1);
				popuwindow_cb_alarm.setText(position + 1
						+ SystemUtils.getRsStr(R.string.oil_no));
				popuwindow_cb_alarm
						.setOnCheckedChangeListener(new OnCheckedChangeListener() {

							@Override
							public void onCheckedChanged(
									CompoundButton buttonView, boolean isChecked) {

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
								oilCanSet.setGmt_modified(DateUtils.getYYYMMddHHmmss());
								MyApplication.getInstance().getmDBManager()
										.UpdateObject(oilCanSet);
							}
						});
			}

		}
		
		if (position == lists.size()) {
			popuwindow_cb_alarm.setText(SystemUtils.getRsStr(R.string.all));
			popuwindow_cb_alarm.setChecked(SharePrefUtil.getBoolean(ct,
					"isAllAlarm", false));
			popuwindow_cb_alarm.setOnCheckedChangeListener(this);
		}
		return view;
	}

	@Override
	public void onCheckedChanged(CompoundButton arg0, boolean isChecked) {
		int size =lists.size();
		for (int position = 1; position <= size; position++) {
			int alarmByte = isChecked == true ? 1 : 0;

			OilCanSet oilCanSet = (OilCanSet) MyApplication.getInstance()
					.getmDBManager().findObjectByID(OilCanSet.class, position);
			oilCanSet.setAlarmByte(alarmByte);
			OilCan oilCan = MyApplication.getInstance().getmOilcanDataManager()
					.getData(position);
			if (oilCan != null) {

				oilCan.setAlarmByte(alarmByte);
			}
			oilCanSet.setGmt_modified(DateUtils.getYYYMMddHHmmss());
			MyApplication.getInstance().getmDBManager().UpdateObject(oilCanSet);
			SharePrefUtil.saveBoolean(ct, "isAllAlarm", isChecked);
		}

	}

}