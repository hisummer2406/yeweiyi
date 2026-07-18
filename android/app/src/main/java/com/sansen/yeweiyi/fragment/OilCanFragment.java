package com.sansen.yeweiyi.fragment;

import android.annotation.SuppressLint;
import android.os.Bundle;
import android.os.Handler;
import android.view.LayoutInflater;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.TextView;

import com.sansen.yeweiyi.R;
import com.sansen.yeweiyi.application.MyApplication;
import com.sansen.yeweiyi.base.BaseFragment;
import com.sansen.yeweiyi.bean.OilCan;
import com.sansen.yeweiyi.utils.Constans;
import com.sansen.yeweiyi.utils.DataUtils;
import com.sansen.yeweiyi.utils.SharePrefUtil;
import com.sansen.yeweiyi.view.CustomProgressView;

@SuppressLint("ValidFragment")
public class OilCanFragment extends BaseFragment {

	private CustomProgressView oilcan_view_progress;

	private TextView oilcan_tv_tankno;

	private TextView oilcan_tv_oiltype;

	private TextView oilcan_tv_oillevel;

	private TextView oilcan_tv_oilvolume;

	private TextView oilcan_tv_oilweight;

	private TextView oilcan_tv_oiladdible;

	private TextView oilcan_tv_waterlevel;


	private TextView oilcan_tv_temperature;

	private TextView oilcan_tv_zhiliang;

	private TextView oilcan_tv_midu;
	private int oilCanPos;
	private TextView tv_main_return;

	private LayoutInflater inflater;

	Handler handler = new Handler() {
		public void handleMessage(android.os.Message msg) {
			setData();
			handler.sendEmptyMessageDelayed(0, 1000);
		};
	};

	public OilCanFragment(int oilCanPos) {
		this.oilCanPos = oilCanPos;

	}

	@Override
	protected View initView(LayoutInflater inflater) {
		// TODO Auto-generated method stub
		return inflater.inflate(R.layout.fragment_oil_can, null, false);

	}

	@Override
	protected void processClick(View v) {
		if (v.getId()==R.id.tv_main_return){
			if (main.mainFragment == null) {
				main.mainFragment = new MainFragment();
			}
			main.switchContent(main.mContent, main.mainFragment);
		}

	}

	@Override
	protected void initData(Bundle savedInstanceState) {

		findView();
		setData();
		handler.sendEmptyMessageDelayed(0, 1000);

	}

	@Override
	public void findView() {

		oilcan_view_progress = (CustomProgressView) rootView
				.findViewById(R.id.oilcan_view_progress);
		oilcan_tv_tankno = (TextView) rootView
				.findViewById(R.id.oilcan_tv_tankno);
		oilcan_tv_oiltype = (TextView) rootView
				.findViewById(R.id.oilcan_tv_oiltype);
		oilcan_tv_oillevel = (TextView) rootView
				.findViewById(R.id.oilcan_tv_oillevel);
		oilcan_tv_oilvolume = (TextView) rootView
				.findViewById(R.id.oilcan_tv_oilvolume);
		oilcan_tv_oilweight = (TextView) rootView
				.findViewById(R.id.oilcan_tv_oilweight);
		oilcan_tv_oiladdible = (TextView) rootView
				.findViewById(R.id.oilcan_tv_oiladdible);
		oilcan_tv_waterlevel = (TextView) rootView
				.findViewById(R.id.oilcan_tv_waterlevel);
		oilcan_tv_temperature = (TextView) rootView
				.findViewById(R.id.oilcan_tv_temperature);
		oilcan_tv_zhiliang = (TextView) rootView
				.findViewById(R.id.oilcan_tv_zhiliang);
		oilcan_tv_midu = (TextView) rootView
				.findViewById(R.id.oilcan_tv_midu);
		tv_main_return=rootView.findViewById(R.id.tv_main_return);
		tv_main_return.setOnClickListener(this);



	}
	private String YeWeiUnit,RongJiUnit,ZhiLiangUnit;
	public void setData() {
		OilCan oilCan ;
		YeWeiUnit=SharePrefUtil.getString(MyApplication.getInstance().getApplicationContext(), Constans.YeWeiUnit,"毫米");
		RongJiUnit=SharePrefUtil.getString(MyApplication.getInstance().getApplicationContext(), Constans.RongJiUnit,"升");
		ZhiLiangUnit=SharePrefUtil.getString(MyApplication.getInstance().getApplicationContext(), Constans.ZhiLiangUnit,"千克");
		if (SharePrefUtil.getBoolean(ct, SharePrefUtil.TEST_DATA, false)) {
			oilCan = mOilcanDataManager.getTestData(oilCanPos);
		}else {
			oilCan = mOilcanDataManager.getData(oilCanPos);
		}
		
		if (oilCan != null) {
			oilcan_tv_zhiliang.setText(oilCan.getOilVolume()*oilCan.getDensity()+" 千克");

			oilcan_tv_midu.setText(oilCan.getDensity()+" kg/m³"
				);//+ " 地址：" + oilCan.getProbeAdr()

		if (oilCan.getErrorTimes()<4) {

			oilcan_view_progress.setCoord(oilCan.getOilRatio(),
					oilCan.getWaterRatio(), oilCan.getOilColor());
//			oilCan.setErrorTimes(0);
		} else {
			oilcan_view_progress.setCoord(0, 0, oilCan.getOilColor());
			oilCan.setOilLevel(0.0);
			oilCan.setWaterLevel(0.0);
			oilCan.setOilVolume(0.0);
			oilCan.setWaterVolume(0.0);
			oilCan.setOilWeight(0.0);
			oilCan.setCanAddOilWeight(0.0);
			oilCan.setTemper(0.0);
		}
		oilcan_view_progress.invalidate();
		oilcan_tv_tankno.setText(oilCan.getOilCanPos() + "#");
		oilcan_tv_oiltype.setText(oilCan.getOilCanPos()+"号  "+DataUtils.getOilType()[oilCan.getOilType()]);
		oilcan_tv_oillevel.setText(("毫米".equals(YeWeiUnit)?oilCan.getOilLevel():oilCan.getOilLevel()/1000) + "  "+YeWeiUnit);
		oilcan_tv_oilvolume.setText( ("升".equals(RongJiUnit)?oilCan.getOilVolume():oilCan.getOilVolume()/1000) + "  "+RongJiUnit);
		oilcan_tv_oilweight.setText( ("千克".equals(RongJiUnit)?oilCan.getOilWeight() :oilCan.getOilWeight() /1000) + "  "+ZhiLiangUnit);
		oilcan_tv_oiladdible.setText( ("升".equals(RongJiUnit)?oilCan.getSurPlus():oilCan.getSurPlus()/1000) + "  "+RongJiUnit);
		oilcan_tv_waterlevel.setText( ("毫米".equals(YeWeiUnit)?oilCan.getWaterLevel():oilCan.getWaterLevel()/1000) + "  "+YeWeiUnit);
		oilcan_tv_temperature.setText(oilCan.getTemper() + " ℃");
		}
	}

	public String getProbeType(int type) {
		return  ct.getResources().getStringArray(R.array.probeTypeArray)[type];
	}

	@Override
	public void initTitle() {
		if (main != null) {
//			main.iv_right.setImageResource(R.drawable.laststep);
//			main.tv_left.setText(SystemUtils.getRsStr(R.string.back_page));
//			main.tv_unlogin.setVisibility(View.GONE);
//			main.tempButton.setEnabled(true);
			OnClickListener backListener = new OnClickListener() {

				@Override
				public void onClick(View v) {

					if (main.mainFragment == null) {
						main.mainFragment = new MainFragment();
					}
					main.switchContent(main.mContent, main.mainFragment);

				}
			};
//			main.iv_right.setOnClickListener(backListener);
//			main.tv_left.setOnClickListener(backListener);
		}
	}

	public void UpdatePostion(int position) {

		oilCanPos = position;
	}

}
