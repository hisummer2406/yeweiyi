package com.sansen.yeweiyi.fragment;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.AdapterView.OnItemClickListener;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.GridView;

import com.sansen.yeweiyi.R;
import com.sansen.yeweiyi.base.BaseFragment;
import com.sansen.yeweiyi.utils.SystemUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class DataManagerListFragment extends BaseFragment implements OnItemClickListener {

	private GridView gridview;
	private int[] icon = {R.drawable.sansen_recordmanager_but_selector, R.drawable.sansen_datastronge_but_selector};
	private String[] data = {SystemUtils.getRsStr(R.string.record_manager),SystemUtils.getRsStr(R.string.data_storge)};

	private ArrayList<Map<String, Object>> data_list;
	
	@Override
	public void initTitle() {
		if (main != null) {

//			main.iv_right.setImageResource(R.drawable.laststep);
//			main.tv_left.setText(SystemUtils.getRsStr(R.string.back_page));
//			main.iv_right.setVisibility(View.VISIBLE);
//			main.tv_left.setVisibility(View.VISIBLE);
			OnClickListener backSetListener = new OnClickListener() {

				@Override
				public void onClick(View v) {
					if (main.setFragment == null) {
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
	protected View initView(LayoutInflater inflater) {
		// TODO Auto-generated method stub
		return inflater.inflate(R.layout.fragment_datamanager, null);
	}

	@Override
	protected void initData(Bundle savedInstanceState) {
		gridview = (GridView) rootView.findViewById(R.id.datamanager_gridview_data);
		gridview.setNumColumns(2);
		data_list = new ArrayList<Map<String, Object>>();
		// 获取数据
		getData();
		// 新建适配器
		String[] from = { "icon" };
		int[] to = { R.id.image};
		// 配置适配器
		gridview.setAdapter( new MyAdapter());
		gridview.setOnItemClickListener(this);
	}

	@Override
	protected void processClick(View v) {
		// TODO Auto-generated method stub
		
	}
	public List<Map<String, Object>> getData() {
		// cion和iconName的长度是相同的，这里任选其一都可以
		for (int i = 0; i < icon.length; i++) {
			Map<String, Object> map = new HashMap<String, Object>();

			map.put("icon", icon[i]);
			data_list.add(map);
		}

		return data_list;
	}

	@Override
	public void onItemClick(AdapterView<?> arg0, View arg1, int position, long arg3) {
		
		switch (position) {
		case 0:
			RecordManagerFragment recordManagerFragment = new RecordManagerFragment();

			main.switchContent(main.mContent,
					recordManagerFragment);
			break;

		case 1:
			DataStorageManagerFragment dataStorageManagerFragment = new DataStorageManagerFragment();

			main.switchContent(main.mContent,
					dataStorageManagerFragment);
			break;
		}
	}
	class MyAdapter extends BaseAdapter {

		@Override
		public int getCount() {
			// TODO Auto-generated method stub
			return icon.length;
		}

		@Override
		public Object getItem(int position) {
			// TODO Auto-generated method stub
			return icon[position];
		}

		@Override
		public long getItemId(int position) {
			// TODO Auto-generated method stub
			return 0;
		}

		@Override
		public View getView(int position, View convertView, ViewGroup parent) {
			
			
			View v = LayoutInflater.from(ct).inflate(R.layout.item_base_set, null);

			Button iv_set = (Button) v.findViewById(R.id.base_button);
			iv_set.setBackgroundResource(icon[position]);
			iv_set.setText(data [position]);

			return v;
		}
		
	}
}
