package com.sansen.yeweiyi.fragment;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.TextView;

import com.sansen.yeweiyi.R;
import com.sansen.yeweiyi.activity.MainActivity;
import com.sansen.yeweiyi.base.BaseFragment;
import com.sansen.yeweiyi.db.OilCanSet;
import com.sansen.yeweiyi.utils.DateUtils;
import com.sansen.yeweiyi.view.colorpicker.ColorPicker;
import com.sansen.yeweiyi.view.colorpicker.ColorPicker.OnColorChangedListener;
import com.sansen.yeweiyi.view.colorpicker.OpacityBar;
import com.sansen.yeweiyi.view.colorpicker.OpacityBar.OnOpacityChangedListener;
import com.sansen.yeweiyi.view.colorpicker.SVBar;
import com.sansen.yeweiyi.view.colorpicker.SaturationBar;
import com.sansen.yeweiyi.view.colorpicker.SaturationBar.OnSaturationChangedListener;
import com.sansen.yeweiyi.view.colorpicker.ValueBar;
import com.sansen.yeweiyi.view.colorpicker.ValueBar.OnValueChangedListener;

public class ColorPickerFragment extends BaseFragment implements
		OnColorChangedListener {

	private TextView color_text;
	private int color;
	private OilCanSet oilCanSet;

	public void setCurrOilCan(OilCanSet oilCanSet) {
		this.oilCanSet = oilCanSet;
	}

	@Override
	protected View initView(LayoutInflater inflater) {
		return inflater.inflate(R.layout.fragment_colorpicker, null);
	}

	@Override
	protected void initData(Bundle savedInstanceState) {

		ColorPicker color_picker = (ColorPicker) rootView
				.findViewById(R.id.color_picker);
		SVBar color_svbar = (SVBar) rootView.findViewById(R.id.color_svbar);
		// 透明
		OpacityBar color_opacitybar = (OpacityBar) rootView
				.findViewById(R.id.color_opacitybar);
		// 饱和度
		SaturationBar color_saturationbar = (SaturationBar) rootView
				.findViewById(R.id.color_saturationbar);
		ValueBar color_valuebar = (ValueBar) rootView
				.findViewById(R.id.color_valuebar);
		color_text = (TextView) rootView.findViewById(R.id.color_text);

		color_picker.addSVBar(color_svbar);
		color_picker.addOpacityBar(color_opacitybar);
		color_picker.addSaturationBar(color_saturationbar);
		color_picker.addValueBar(color_valuebar);

		// To get the color
		color_picker.getColor();

		// To set the old selected color u can do it like this
		color_picker.setOldCenterColor(color_picker.getColor());
		// adds listener to the colorpicker which is implemented
		// in the activity
		color_picker.setOnColorChangedListener(this);

		// to turn of showing the old color
		color_picker.setShowOldCenterColor(false);

		// adding onChangeListeners to bars
		color_opacitybar
				.setOnOpacityChangedListener(new OnOpacityChangedListener() {

					@Override
					public void onOpacityChanged(int opacity) {
						// TODO Auto-generated method stub

					}
				});
		color_valuebar.setOnValueChangedListener(new OnValueChangedListener() {

			@Override
			public void onValueChanged(int value) {
				// TODO Auto-generated method stub

			}
		});
		color_saturationbar
				.setOnSaturationChangedListener(new OnSaturationChangedListener() {

					@Override
					public void onSaturationChanged(int saturation) {

					}
				});
		color_text.setOnClickListener(new OnClickListener() {

			 	
			public void onClick(View v) {
			
				oilCanSet.setOilColor(color);
				oilCanSet.setGmt_modified(DateUtils.getYYYMMddHHmmss());
				dbManager.UpdateObject(oilCanSet);
				MainActivity main = (MainActivity) getActivity();
				if (main.oilCanSetFragment == null) {
					main.oilCanSetFragment = new OilCanSetFragment();
				}
				main.switchContent(main.mContent, main.oilCanSetFragment);
				updateData();
			}
		});

	}
	public void initTitle() {
		if (main!=null) {  
//			main.iv_right.setImageResource(R.drawable.laststep);
//			main.tv_left.setText(SystemUtils.getRsStr(R.string.back_page));
//			main.iv_right.setOnClickListener(new OnClickListener() {
//
//				@Override
//				public void onClick(View v) {
//					if (main.oilCanSetFragment==null) {
//						main.oilCanSetFragment =new OilCanSetFragment();
//					}
//					main.switchContent(main.mContent, main.oilCanSetFragment);
//				}
//			});
		}
		
		
	}
	/**
	 * hidden false 为不隐藏 true 为隐藏
	 */
	@Override
	public void onHiddenChanged(boolean hidden) {
		// TODO Auto-generated ,.m, ,,,,kjk method stub
		super.onHiddenChanged(hidden);
	}
	
	@Override
	protected void processClick(View v) {

	}

	@Override
	public void onColorChanged(int color) {
		color_text.setTextColor(color);
		this.color = color;
	}


}
