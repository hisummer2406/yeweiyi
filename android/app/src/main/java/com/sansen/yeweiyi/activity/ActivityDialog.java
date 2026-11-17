package com.sansen.yeweiyi.activity;

import android.app.Activity;
import android.content.Context;
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
import com.sansen.yeweiyi.applog.AppLog;
import com.sansen.yeweiyi.languageUtil.LanguageUtil;
import com.sansen.yeweiyi.logic.applog.LogModule;

import java.util.Arrays;

public class ActivityDialog extends Activity implements OnItemClickListener {

	private String[] optionNames = new String[0];
	private int[] oilTypeIndices;

	@Override
	protected void attachBaseContext(Context newBase) {
		super.attachBaseContext(LanguageUtil.wrap(newBase));
	}

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.activity_dialog);

		int selectionType = getIntent().getIntExtra("value", 0);
		if (selectionType == 0) {
			setTitle(R.string.oilCanSelect);
			optionNames = getResources().getStringArray(R.array.oilcanArray);
		} else if (selectionType == 1) {
			setTitle(R.string.oil_type);
			optionNames = buildOilTypeOptions();
		} else {
			finish();
			return;
		}

		if (optionNames.length == 0) {
			finish();
			return;
		}

		GridView gridView = (GridView) findViewById(R.id.activity_dialog_gridview);
		gridView.setAdapter(new MyAdapter());
		gridView.setOnItemClickListener(this);
		overridePendingTransition(0, 0);
	}

	private String[] buildOilTypeOptions() {
		String[] allNames = getResources().getStringArray(R.array.oiltypeArray);
		int[] indices = getResources().getIntArray(R.array.oiltype_selection_indices);
		String[] names = new String[indices.length];
		int[] originalIndices = new int[indices.length];
		int count = 0;

		for (int index : indices) {
			if (index < 0 || index >= allNames.length || allNames[index] == null) {
				AppLog.w(LogModule.SYSTEM, "跳过无效介质编号", "index", index);
				continue;
			}
			names[count] = allNames[index];
			originalIndices[count] = index;
			count++;
		}
		// 显示列表可以精简，但回传编号必须保留；“空白”仍为 32。
		oilTypeIndices = Arrays.copyOf(originalIndices, count);
		return Arrays.copyOf(names, count);
	}

	class MyAdapter extends BaseAdapter {

		@Override
		public int getCount() {
			return optionNames.length;
		}

		@Override
		public String getItem(int position) {
			if (position < 0 || position >= optionNames.length) {
				return null;
			}
			return optionNames[position];
		}

		@Override
		public long getItemId(int position) {
			return position;
		}

		@Override
		public View getView(int position, View convertView, ViewGroup parent) {
			if (convertView == null) {
				convertView = LayoutInflater.from(ActivityDialog.this)
						.inflate(R.layout.iteam_activity_dialog_gridview, parent, false);
			}
			TextView textView = (TextView) convertView.findViewById(R.id.item_activity_dialog_tv);
			textView.setText(getItem(position));
			return convertView;
		}
	}

	@Override
	public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
		if (position < 0 || position >= optionNames.length) {
			AppLog.w(LogModule.SYSTEM, "忽略无效选择位置", "position", position);
			return;
		}
		int sourceIndex = position;
		if (oilTypeIndices != null) {
			sourceIndex = oilTypeIndices[position];
		}
		// 0 表示取消，选择结果沿用调用方约定的“原编号 + 100”。
		setResult(sourceIndex + 100);
		finish();
	}

	@Override
	protected void onPause() {
		super.onPause();
		overridePendingTransition(0, 0);
	}
}
