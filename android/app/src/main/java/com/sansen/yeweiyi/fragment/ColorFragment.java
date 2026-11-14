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
import android.widget.ImageView;

import com.sansen.yeweiyi.R;
import com.sansen.yeweiyi.activity.MainActivity;
import com.sansen.yeweiyi.base.BaseFragment;
import com.sansen.yeweiyi.db.OilCanSet;
import com.sansen.yeweiyi.logic.Const;
import com.sansen.yeweiyi.repository.OilTankRepository;
import com.sansen.yeweiyi.repository.OilTankRepositoryImpl;
import com.sansen.yeweiyi.utils.DateUtils;

public class ColorFragment extends BaseFragment implements OnItemClickListener {

	private final OilTankRepository oilTankRepository = OilTankRepositoryImpl.getInstance();

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
		oilTankRepository.updateOilCanSet(oilCanSet);
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
	
	/** 色板见 {@link com.sansen.yeweiyi.logic.OilColors}（与云端共用，远程改色只接受这些颜色）。 */
	public static int yanseId(int yanse){
		return com.sansen.yeweiyi.logic.OilColors.argbOf(yanse);
	}

}

