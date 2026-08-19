package com.sansen.yeweiyi.fragment;

import android.databinding.Observable;
import android.databinding.ObservableField;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.support.v4.content.ContextCompat;
import android.view.LayoutInflater;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.AdapterView.OnItemClickListener;
import android.widget.BaseAdapter;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.sansen.yeweiyi.R;
import com.sansen.yeweiyi.base.BaseFragment;
import com.sansen.yeweiyi.bean.SerialPortEntrity;
import com.sansen.yeweiyi.fragment.CreateOilTableFragment.TableType;
import com.sansen.yeweiyi.utils.SystemUtils;

public class LoadingCreateTableFragment extends BaseFragment implements OnItemClickListener {

	private GridView loading_gridview_create;
	private int [] ivs={R.drawable.table_woguan, R.drawable.sansne_table_liguan, R.drawable.table_lifangti};
	private LinearLayout ly_upper_page;

	@Override
	protected View initView(LayoutInflater inflater) {
		return inflater.inflate(R.layout.fragment_loading_createtable, null);
	}

	@Override
	protected void initData(Bundle savedInstanceState) {
		findView();
		loading_gridview_create.setAdapter(new MyAdapter());
		loading_gridview_create.setOnItemClickListener(this);
	}
	
	@Override
	public void findView() {
		loading_gridview_create = (GridView)rootView.findViewById(R.id.loading_gridview_create);
		ly_upper_page=rootView.findViewById(R.id.ly_upper_page);
		ly_upper_page.setOnClickListener(this);
		View headerView = rootView.findViewById(R.id.title_bar);
		// 再从该 View 中 findViewById 获取内部的 TextView
		tvTitle = headerView.findViewById(R.id.tv_title);
		tv_time=headerView.findViewById(R.id.main_time);
		tvTitle.setText("罐表设置");
		SerialPortEntrity.time.addOnPropertyChangedCallback(callback);

	}
	Observable.OnPropertyChangedCallback callback = new Observable.OnPropertyChangedCallback() {
		@Override
		public void onPropertyChanged(Observable sender, int propertyId) {
			// 如果需要区分是哪个字段，可通过 propertyId 判断
			// 直接获取当前值
			String newValue = ((ObservableField<String>) sender).get();
			// Log.d("监听", "新值：" + newValue);
			tv_time.setText(newValue);
		}
	};
	TextView tvTitle,tv_time;
	public void initTitle() {

		if (main != null) {
			OnClickListener backSetListener = new OnClickListener() {

				@Override
				public void onClick(View v) {
					if (main.setFragment == null) {
						main.setFragment = new SetFragment();
					}
					main.switchContent(main.mContent, main.setFragment);

				}
			};

		}

	}

	public void loading_horizontal_tank(TableType tableType) {
		CreateOilTableFragment createOilTableFragment = new CreateOilTableFragment(tableType);	
		main.switchContent(main.mContent, createOilTableFragment);
	}

	public void loading_the_tank(TableType tableType) {
		
		CreateOilTableFragment createOilTableFragment = new CreateOilTableFragment(tableType);	
		main.switchContent(main.mContent, createOilTableFragment);
	}

	@Override
	public void onHiddenChanged(boolean hidden) {
		// TODO Auto-gMenerated method stub
		super.onHiddenChanged(hidden);
		if (!hidden) {
			initData(null);
		}
	}

	@Override
	protected void processClick(View v) {
		if (v.getId()==R.id.ly_upper_page){
			if (main.setFragment == null) {
				main.setFragment = new SetFragment();
			}
			main.switchContent(main.mContent, main.setFragment);
		}
		
	}
	class MyAdapter extends BaseAdapter {

		private String[] listName ={SystemUtils.getRsStr(R.string.createtable_horizontal_tank),SystemUtils.getRsStr(R.string.createtable_vertical_tank),SystemUtils.getRsStr(R.string.createtable_changfangti_tank)};

		@Override
		public int getCount() {
			// TODO Auto-generated method stub
			return listName.length;
		}	

		@Override
		public Object getItem(int arg0) {
			// TODO Auto-generated method stub
			return null;
		}

		@Override
		public long getItemId(int arg0) {
			// TODO Auto-generated method stub
			return 0;
		}

		@Override
		public View getView(int position, View arg1, ViewGroup arg2) {
			View v = LayoutInflater.from(ct).inflate(R.layout.item_base_set2, null);
			ImageView iv_set = (ImageView)v.findViewById(R.id.base_button);
			TextView tv_name=(TextView) v.findViewById(R.id.tv_name);
			//iv_set.setBackgroundResource(ivs[position]);
			Drawable drawable = ContextCompat.getDrawable(main, ivs[position]);
			iv_set.setImageDrawable(drawable);
			tv_name.setText(listName [position]);
			return v;
		}
		
	}
	@Override
	public void onItemClick(AdapterView<?> arg0, View v, int pos, long arg3) {
	
		switch (pos) {
		case 0:
			loading_horizontal_tank(TableType.HORIZONTALTANK);
			break;
		case 1:
			loading_the_tank(TableType.THETANK);
			break;
			case 2:
				//长方体
				loading_the_tank(TableType.CHANGFANGTI);
				break;
		}
	}
}
