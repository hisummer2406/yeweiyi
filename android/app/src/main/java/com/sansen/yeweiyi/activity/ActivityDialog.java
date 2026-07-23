package com.sansen.yeweiyi.activity;

import android.app.Activity;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.AdapterView.OnItemClickListener;
import android.widget.BaseAdapter;
import android.widget.GridView;
import android.widget.TextView;

import com.sansen.yeweiyi.R;

public class ActivityDialog extends Activity implements OnItemClickListener {
	
	private GridView activity_dialog_gridview;
	String[]  valueData = null;
	private int value;
	@Override
	protected void onCreate(Bundle savedInstanceState) {
		// TODO Auto-generated method stub
		super.onCreate(savedInstanceState);
		setContentView(R.layout.activity_dialog);
		value = getIntent().getIntExtra("value", 0);
		 if (value == 0) {
			valueData=getResources().getStringArray(R.array.oilcanArray);
		}else if(value == 1){
			setTitle(R.string.oil_type);
			valueData= getResources().getStringArray(R.array.oiltypeArray);//DataUtils.getOilType();
		}else if (value==5) {
			setTitle(R.string.oilcanset_info);
			valueData = getResources().getStringArray(R.array.oilcanSetArray);
		}else if (value==6) {
			setTitle(R.string.oilcantable_info);
			valueData = getResources().getStringArray(R.array.oilcanTableArray);
		}
		else if (value == 7) {
			setTitle("传感器设置");
			valueData=getResources().getStringArray(R.array.tanZhenTableArray);
		}
		
		overridePendingTransition(0,0);
		initView();
	}

	private void initView() {
		activity_dialog_gridview = (GridView)findViewById(R.id.activity_dialog_gridview);
		activity_dialog_gridview.setAdapter(new MyAdapter());
		activity_dialog_gridview.setOnItemClickListener(this);
	}
	class MyAdapter extends BaseAdapter {

		@Override
		public int getCount() {
			return valueData.length;
		}

		@Override
		public Object getItem(int position) {
			return null;
		}

		@Override
		public long getItemId(int position) {
			// TODO Auto-generated method stub
			return 0;
		}

		@Override
		public View getView(int position, View convertView, ViewGroup parent) {
			View v = LayoutInflater.from(ActivityDialog.this).inflate(R.layout.iteam_activity_dialog_gridview, null);
			TextView tv=(TextView) v.findViewById(R.id.item_activity_dialog_tv);
			tv.setText(valueData[position]);
			return v;
		}
	}
	@Override
	public void onItemClick(AdapterView<?> parent, View view, int position,
							long id) {
		//由于点击框的外面默认返回为0，因此在这里不能返回0应该在此基础上+上某些数字
		setResult(position+100);
		finish();
	}
	@Override
	protected void onPause() {
		// TODO Auto-generated method stub
		super.onPause();
		overridePendingTransition(0,0);
	}
}
