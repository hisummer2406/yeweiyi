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
import android.widget.LinearLayout;

import com.sansen.yeweiyi.R;
import com.sansen.yeweiyi.base.BaseFragment;
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

	}
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

			Button iv_set = (Button) v.findViewById(R.id.base_button);
			iv_set.setBackgroundResource(ivs[position]);
			iv_set.setText(listName [position]);
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
