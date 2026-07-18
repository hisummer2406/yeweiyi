package com.sansen.yeweiyi.fragment;

import android.graphics.Color;
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
import android.widget.ImageView;

import com.sansen.yeweiyi.R;
import com.sansen.yeweiyi.activity.MainActivity;
import com.sansen.yeweiyi.base.BaseFragment;
import com.sansen.yeweiyi.db.OilCanSet;
import com.sansen.yeweiyi.logic.Const;
import com.sansen.yeweiyi.utils.DateUtils;

public class ColorFragment extends BaseFragment implements OnItemClickListener {

	private GridView color_gridview_color;
	
	private OilCanSet oilCanSet;

	private Button color_but_custom;

	public void setCurrOilCan(OilCanSet oilCanSet) {
		this.oilCanSet = oilCanSet;
	}

	
	@Override
	public void initTitle() {
		if (main!=null) {
//			main.iv_right.setImageResource(R.drawable.laststep);
//			main.tv_left.setText(SystemUtils.getRsStr(R.string.back_page));
//			main.iv_right.setVisibility(View.VISIBLE);
//			main.tv_left.setVisibility(View.VISIBLE);
			OnClickListener backSetListener  = new OnClickListener() {
				
				@Override
				public void onClick(View v) {
				
					if (main.oilCanSetFragment==null) {
						main.oilCanSetFragment = new OilCanSetFragment();
					}
					main.switchContent(main.mContent, main.oilCanSetFragment);
					
				}
			};
//			main.tv_left.setOnClickListener(backSetListener);
//			main.iv_right.setOnClickListener(backSetListener);
		}
	}

	@Override
	protected View initView(LayoutInflater inflater) {
		return inflater.inflate(R.layout.fragment_color, null);
	}
	
	@Override
	public void findView() {
		color_gridview_color = (GridView) rootView.findViewById(R.id.color_gridview_color);
//		color_but_custom = (Button) rootView.findViewById(R.id.color_but_custom);
//		color_but_custom.setOnClickListener(this);
	}
	
	@Override
	protected void initData(Bundle savedInstanceState) {
		findView();
		color_gridview_color.setAdapter(new MyAdapter());
		color_gridview_color.setOnItemClickListener(this);
	}

	@Override
	protected void processClick(View v) {
//		switch (v.getId()) {
//		case R.id.color_but_custom:
//			if (main.colorPickerFragment==null) {
//				main.colorPickerFragment = new ColorPickerFragment();
//			}
//			main.colorPickerFragment.setCurrOilCan(oilCanSet);
//			main.switchContent(main.mContent,main.colorPickerFragment);
//			break;
//
//		default:
//			break;
//		}
	}
	
	@Override
	public void onItemClick(AdapterView<?> arg0, View arg1, int arg2, long arg3) {
		oilCanSet.setOilColor(yanseId(arg2+1));
		oilCanSet.setGmt_modified(DateUtils.getYYYMMddHHmmss());
		dbManager.UpdateObject(oilCanSet);
		Const.isUpdate=true;
		MainActivity main = (MainActivity) getActivity();
		if (main.oilCanSetFragment == null) {
			main.oilCanSetFragment = new OilCanSetFragment();
		}
		main.switchContent(main.mContent, main.oilCanSetFragment);
		updateData();
	}
	
	class MyAdapter extends BaseAdapter {

		@Override
		public int getCount() {
			// TODO Auto-generated method stub
			return 20;
		}

		@Override
		public Object getItem(int arg0) {

			return yanseId(arg0+1);
		}

		@Override
		public long getItemId(int arg0) {
			// TODO Auto-generated method stub
			return arg0;
		}

		@Override
		public View getView(int pos, View arg1, ViewGroup arg2) {
			LayoutInflater inflater = LayoutInflater.from(ct);
			View v= inflater.inflate(R.layout.simple_list_item_1, null);
			ImageView iv= (ImageView) v.findViewById(R.id.simple_iv_list);
			iv.setBackgroundColor(yanseId(pos+1));
			return v;
		}
		
	}
	
	public static int yanseId(int yanse){
		
		Color color =new Color();
		int id=0;
		switch (yanse) {
			case 1:
				id=color.rgb(255,0,0);
			break;
			
			case 2:
				id=color.rgb( 255,255,0);
			break;
			case 3:
				id=color.rgb(0,0,255);
			break;
			case 4:
				id=color.rgb(0,128,0);
			break;
			case 5:
				id=color.rgb(128,0,128);
			break;
			case 6:
				id=color.rgb(37,77,42);
			break;
			case 7:
				id=color.rgb(111,18,0);
			break;
			case 8:
				id=color.rgb(0,0,52);
			break;
			case 9:
				id=color.rgb(244,178,214);
			break;
			case 10:
				id=color.rgb(76,11,113);
			break;
			case 11:
				id=color.rgb(0,69,108);
			break;
			case 12:
				id=color.rgb(0,173,239);
			break;
			case 13:
				id=color.rgb(52,47,147);
			break;
			case 14:
				id=color.rgb(244,240,141);
			break;
			case 15:
				id=color.rgb(241,100,93);
			break;
			
			case 16:
				id=color.rgb(255,0,205);
			break;
			case 17:
				id=color.rgb(65,49,0);
			break;
			case 18:
				id=color.rgb(19,147,0);
			break;
			
			case 19:
				id=color.rgb(193,205,35);
			break;
			
			case 20:
				id=color.rgb(255,153,0);
			break;

		default:
			break;
		}
		return id;
	}

}

